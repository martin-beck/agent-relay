/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.companion.api

import kotlin.test.Test
import kotlin.test.assertEquals

class WearConsentedInstallationTest {
    private val device = CompanionDeviceId("cd_v1_watch-consent")
    private val request = WearInstallationRequest(
        profile = WearDeviceProfile(34, "wear-emulator", "x86_64", debugAuthorized = true),
        artifact = WearDevelopmentArtifact(
            packageName = "dev.agentrelay.wear.companion",
            versionCode = 7,
            schemaVersion = 1,
            variant = WearArtifactVariant.DEBUG,
            sha256 = "a".repeat(64),
            sizeBytes = 10,
            signingFingerprint = "ab:".repeat(16),
        ),
        expectedPackageName = "dev.agentrelay.wear.companion",
        expectedSchemaVersion = 1,
        allowUpgrade = true,
    )

    @Test
    fun consentIsRequiredAndBoundToExactDevice() {
        val transport = FakeTransport()
        val coordinator = WearConsentedInstallationCoordinator(transport)
        assertEquals(WearConsentedInstallOutcome.CONSENT_REQUIRED, coordinator.install(null, device, request, 10))
        assertEquals(
            WearConsentedInstallOutcome.WRONG_DEVICE,
            coordinator.install(consent(), CompanionDeviceId("cd_v1_otherdev"), request, 10),
        )
        assertEquals(0, transport.installCalls)
    }

    @Test
    fun cancellationAndExpiredConsentDoNotReachDevice() {
        val transport = FakeTransport()
        val coordinator = WearConsentedInstallationCoordinator(transport)
        assertEquals(
            WearConsentedInstallOutcome.CANCELLED,
            coordinator.install(consent(), device, request, 10, cancelled = true),
        )
        assertEquals(
            WearConsentedInstallOutcome.CONSENT_EXPIRED,
            coordinator.install(consent(expires = 10), device, request, 10),
        )
        assertEquals(0, transport.installCalls)
    }

    @Test
    fun receiptMismatchRollsBackAndVerifiedReceiptSucceeds() {
        val transport = FakeTransport()
        val coordinator = WearConsentedInstallationCoordinator(transport)
        transport.receipt = receipt()
        transport.receipt = WearInstallReceipt(
            device,
            "dev.agentrelay.wear.other",
            7,
            request.artifact.signingFingerprint,
        )
        assertEquals(WearConsentedInstallOutcome.ROLLED_BACK, coordinator.install(consent(), device, request, 10))
        assertEquals(1, transport.rollbackCalls)
        transport.receipt = receipt()
        assertEquals(WearConsentedInstallOutcome.INSTALLED, coordinator.install(consent(), device, request, 10))
    }

    @Test
    fun transportFailureRollsBackAndRollbackFailureIsActionable() {
        val transport = FakeTransport(installFails = true)
        val coordinator = WearConsentedInstallationCoordinator(transport)
        assertEquals(WearConsentedInstallOutcome.ROLLED_BACK, coordinator.install(consent(), device, request, 10))
        transport.rollbackSucceeds = false
        assertEquals(WearConsentedInstallOutcome.ROLLBACK_FAILED, coordinator.install(consent(), device, request, 10))
    }

    private fun consent(expires: Long = 100) = WearInstallConsent(
        device,
        WearInstallPolicy("dev.agentrelay.wear.companion", 7),
        true,
        0,
        expires,
    )

    private fun receipt() = WearInstallReceipt(
        device,
        request.artifact.packageName,
        7,
        request.artifact.signingFingerprint,
    )

    private class FakeTransport(private val installFails: Boolean = false) : WearInstallTransport {
        var installCalls = 0
        var rollbackCalls = 0
        var rollbackSucceeds = true
        var receipt: WearInstallReceipt? = null
        override fun install(deviceId: CompanionDeviceId, request: WearInstallationRequest): WearInstallReceipt {
            installCalls++
            if (installFails) error("install failed")
            return checkNotNull(receipt)
        }
        override fun rollback(deviceId: CompanionDeviceId): Boolean {
            rollbackCalls++
            return rollbackSucceeds
        }
    }
}
