/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.companion.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Focused system-boundary assurance using synthetic, disposable device records.
 * No emulator identity, credential, raw audio, or user data is retained here.
 */
class AndroidSystemBoundaryAssuranceTest {
    private val device = CompanionDeviceId("cd_v1_watch-boundary")

    @Test
    fun revokedDeviceCannotResumeAfterGenerationChanges() {
        val ledger = CompanionSecurityRecoveryLedger()
        ledger.enroll(device, generation = 1)
        val message = message(generation = 1, revision = 1, id = "boundary-message-1")

        assertIs<CompanionRecoveryDecision.Accepted>(ledger.accept(message, nowEpochMillis = 1_500))
        assertTrue(ledger.revoke(device))
        assertEquals(
            CompanionRecoveryRejection.REVOKED_DEVICE,
            (
                ledger.accept(message.copy(messageId = "boundary-message-2"), 1_500)
                    as CompanionRecoveryDecision.Rejected
                ).reason,
        )
        assertEquals(2, ledger.snapshot(device)?.enrollmentGeneration)
        assertEquals(0, ledger.snapshot(device)?.replayEntries)
    }

    @Test
    fun staleAndHostileWearBoundariesFailClosed() {
        val request = wearRequest()
        assertTrue(request.isInstallable())
        assertEquals(
            WearInstallationFailure.DEBUG_AUTHORIZATION_REQUIRED,
            wearRequest(profile = WearDeviceProfile(34, "synthetic-watch", "x86_64", false)).failure(),
        )
        assertFails { wearArtifact(signingFingerprint = "RELEASE_ONLY") }
    }

    @Test
    fun microphonePermissionAndTimeoutAreExplicit() {
        val denied = assertFails {
            WearSpeechCapture(
                "speech_v1_boundary-01",
                1,
                "en-US",
                WearSpeechPermission.DENIED,
                WearSpeechCaptureState.LISTENING,
                1_000,
            )
        }
        assertEquals("Listening requires microphone permission", denied.message)
        val capture = WearSpeechCapture(
            "speech_v1_boundary-01",
            1,
            "en-US",
            WearSpeechPermission.GRANTED,
            WearSpeechCaptureState.LISTENING,
            1_000,
        )
        assertTrue(capture.timedOutAt(31_000))
    }

    private fun message(generation: Long, revision: Long, id: String) = CompanionMessageEnvelope(
        device,
        generation,
        revision,
        id,
        1_000,
        2_000,
        "opaque-boundary",
        "synthetic-tag",
    )

    private fun wearRequest(
        profile: WearDeviceProfile = WearDeviceProfile(34, "synthetic-watch", "x86_64", true),
        artifact: WearDevelopmentArtifact = wearArtifact(),
    ) = WearInstallationRequest(profile, artifact, "dev.agentrelay.wear.debug", 1, true)

    private fun wearArtifact(signingFingerprint: String = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99") =
        WearDevelopmentArtifact(
            "dev.agentrelay.wear.debug",
            1,
            1,
            WearArtifactVariant.DEBUG,
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            1_024,
            signingFingerprint,
        )

    private fun assertFails(block: () -> Unit): IllegalArgumentException = try {
        block()
        error("Expected IllegalArgumentException")
    } catch (error: IllegalArgumentException) {
        error
    }
}
