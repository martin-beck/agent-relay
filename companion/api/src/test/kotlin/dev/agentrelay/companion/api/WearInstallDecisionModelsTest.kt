/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.companion.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WearInstallDecisionModelsTest {
    private val policy = WearInstallPolicy("dev.agentrelay.wear.companion", 7)

    @Test
    fun declineIsScopedToStableDeviceAndPolicyUntilExplicitReset() {
        val persistence = MemoryPersistence()
        val store = WearInstallDecisionStore(persistence)
        val device = device("cd_v1_watchone")

        assertEquals(WearInstallDecisionState.PENDING_OFFER, store.observe(device, policy, 100).state)
        store.recordDeclined(device.deviceId, policy, 101)
        assertEquals(WearInstallDecisionState.DECLINED, store.observe(device, policy, 102).state)
        assertEquals(
            WearInstallDecisionState.PENDING_OFFER,
            store.observe(device, policy.copy(versionCode = 8), 103).state,
        )
        assertTrue(store.reset(device.deviceId))
        assertEquals(WearInstallDecisionState.PENDING_OFFER, store.observe(device, policy, 104).state)
        assertTrue(store.reset(device.deviceId))
        assertFalse(store.reset(device.deviceId))
    }

    @Test
    fun installedAndUnsupportedStatesNeverOfferAnInstall() {
        val store = WearInstallDecisionStore(MemoryPersistence())
        val installed = device("cd_v1_installed", WearCompanionPackageState.INSTALLED)
        val unsupported = device("cd_v1_unsupported", WearCompanionPackageState.NOT_INSTALLED, apiLevel = 29)

        assertEquals(WearInstallDecisionState.INSTALLED, store.observe(installed, policy, 100).state)
        assertEquals(WearInstallDecisionState.UNAVAILABLE, store.observe(unsupported, policy, 100).state)
        assertFalse(store.observe(installed, policy, 101).state == WearInstallDecisionState.PENDING_OFFER)
        assertFalse(store.observe(unsupported, policy, 101).state == WearInstallDecisionState.PENDING_OFFER)
    }

    @Test
    fun documentRoundTripsAndMigratesLegacySchema() {
        val persistence = MemoryPersistence()
        val store = WearInstallDecisionStore(persistence)
        val device = device("cd_v1_roundtrip")
        store.recordDeclined(device.deviceId, policy, 200)
        val encoded = checkNotNull(persistence.value)
        assertTrue(encoded.startsWith("2|"))
        assertEquals(WearInstallDecisionState.DECLINED, WearInstallDecisionDocument.decode(encoded).decisions.single().state)

        persistence.value = encoded.replaceFirst("2|", "1|")
        assertEquals(WearInstallDecisionState.DECLINED, WearInstallDecisionStore(persistence).decision(device.deviceId)?.state)
    }

    @Test
    fun malformedPersistenceIsRejectedBeforeMutation() {
        val persistence = MemoryPersistence("2|cd_v1_bad,dev.agentrelay.wear.companion,7,UNKNOWN,1")
        val store = WearInstallDecisionStore(persistence)
        assertNull(runCatching { store.decision(CompanionDeviceId("cd_v1_other")) }.getOrNull())
        assertEquals("2|cd_v1_bad,dev.agentrelay.wear.companion,7,UNKNOWN,1", persistence.value)
    }

    private fun device(
        id: String,
        packageState: WearCompanionPackageState = WearCompanionPackageState.NOT_INSTALLED,
        apiLevel: Int = 34,
    ) = WearDeviceDiscoveryRecord(
        deviceId = CompanionDeviceId(id),
        alias = "Wear",
        apiLevel = apiLevel,
        model = "wear-emulator",
        abi = "x86_64",
        capabilities = setOf(CompanionCapability.NOTIFICATIONS),
        reachability = CompanionReachability.ONLINE,
        packageState = packageState,
    )

    private class MemoryPersistence(initial: String? = null) : WearInstallDecisionPersistence {
        var value: String? = initial
        override fun read(): String? = value
        override fun write(document: String) {
            value = document
        }
    }
}
