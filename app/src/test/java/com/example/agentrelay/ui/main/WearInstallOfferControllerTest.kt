/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import dev.agentrelay.companion.api.CompanionCapability
import dev.agentrelay.companion.api.CompanionDeviceId
import dev.agentrelay.companion.api.CompanionReachability
import dev.agentrelay.companion.api.WearCompanionPackageState
import dev.agentrelay.companion.api.WearDeviceDiscoveryRecord
import dev.agentrelay.companion.api.WearInstallDecisionPersistence
import dev.agentrelay.companion.api.WearInstallDecisionState
import dev.agentrelay.companion.api.WearInstallDecisionStore
import dev.agentrelay.companion.api.WearInstallPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearInstallOfferControllerTest {
    private val policy = WearInstallPolicy("dev.agentrelay.wear.companion", 7)

    @Test
    fun declineIsRememberedForTheExactDeviceAndPolicy() {
        val persistence = MemoryPersistence()
        val controller = controller(persistence)

        controller.observe(device())
        assertTrue(controller.state.value is WearInstallOfferUiState.Offer)
        assertTrue(controller.decline())
        assertEquals(WearInstallOfferUiState.Hidden, controller.state.value)

        val restored = controller(persistence)
        restored.observe(device())
        assertEquals(WearInstallOfferUiState.Hidden, restored.state.value)
        assertEquals(
            WearInstallDecisionState.DECLINED,
            WearInstallDecisionStore(persistence).decision(device().deviceId)?.state,
        )

        val newPolicyController = controller(persistence, policy.copy(versionCode = 8))
        newPolicyController.observe(device())
        assertTrue(newPolicyController.state.value is WearInstallOfferUiState.Offer)
    }

    @Test
    fun acceptanceCreatesShortLivedExactDeviceConsentAndSupportsCancellation() {
        var now = 1_000L
        val controller = controller(MemoryPersistence()) { now }
        controller.observe(device())

        val consent = controller.accept()

        requireNotNull(consent)
        assertEquals(device().deviceId, consent.deviceId)
        assertEquals(policy, consent.policy)
        assertEquals(1_000L, consent.issuedAtEpochMillis)
        assertEquals(121_000L, consent.expiresAtEpochMillis)
        assertTrue(controller.state.value is WearInstallOfferUiState.Installing)
        assertNull(controller.accept())

        now = 1_001L
        assertTrue(controller.cancel())
        assertTrue(controller.state.value is WearInstallOfferUiState.Offer)
        assertFalse(controller.cancel())
    }

    @Test
    fun failedInstallOffersRecoveryAndRetryBeforeRecordingSuccess() {
        var now = 2_000L
        val persistence = MemoryPersistence()
        val controller = controller(persistence) { now }
        controller.observe(device(alias = "Pixel Watch"))
        controller.accept()

        assertTrue(controller.fail(WearInstallRecoveryReason.CONNECTION_FAILED))
        assertEquals(
            WearInstallOfferUiState.Recovery(
                device().deviceId,
                "Pixel Watch",
                WearInstallRecoveryReason.CONNECTION_FAILED,
            ),
            controller.state.value,
        )

        now = 2_500L
        val retryConsent = controller.retry()
        requireNotNull(retryConsent)
        assertEquals(2_500L, retryConsent.issuedAtEpochMillis)
        assertTrue(controller.complete())
        assertEquals(WearInstallOfferUiState.Hidden, controller.state.value)
        assertEquals(
            WearInstallDecisionState.INSTALLED,
            WearInstallDecisionStore(persistence).decision(device().deviceId)?.state,
        )
    }

    @Test
    fun installedAndUnsupportedDevicesNeverCreateOffers() {
        val controller = controller(MemoryPersistence())

        controller.observe(device(packageState = WearCompanionPackageState.INSTALLED))
        assertEquals(WearInstallOfferUiState.Hidden, controller.state.value)

        controller.observe(device(apiLevel = 29))
        assertEquals(WearInstallOfferUiState.Hidden, controller.state.value)
    }

    @Test
    fun rediscoveryAndAnotherDeviceCannotReplaceAnActiveInstallation() {
        val controller = controller(MemoryPersistence())
        controller.observe(device(alias = "First watch"))
        controller.accept()

        controller.observe(device(alias = "Renamed watch"))
        controller.observe(
            device(
                deviceId = CompanionDeviceId("cd_v1_secondwatch"),
                alias = "Second watch",
            ),
        )

        assertEquals(
            WearInstallOfferUiState.Installing(device().deviceId, "First watch"),
            controller.state.value,
        )
    }

    private fun controller(
        persistence: MemoryPersistence,
        selectedPolicy: WearInstallPolicy = policy,
        clock: () -> Long = { 1_000L },
    ) = WearInstallOfferController(WearInstallDecisionStore(persistence), selectedPolicy, clock)

    private fun device(
        deviceId: CompanionDeviceId = CompanionDeviceId("cd_v1_testwatch"),
        alias: String = "Test watch",
        apiLevel: Int = 36,
        packageState: WearCompanionPackageState = WearCompanionPackageState.NOT_INSTALLED,
    ) = WearDeviceDiscoveryRecord(
        deviceId = deviceId,
        alias = alias,
        apiLevel = apiLevel,
        model = "Wear test device",
        abi = "x86_64",
        capabilities = setOf(CompanionCapability.NOTIFICATIONS),
        reachability = CompanionReachability.ONLINE,
        packageState = packageState,
    )

    private class MemoryPersistence : WearInstallDecisionPersistence {
        private var encoded: String? = null

        override fun read(): String? = encoded

        override fun write(document: String) {
            encoded = document
        }
    }
}
