/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.speech.android

import dev.agentrelay.speech.api.SpeechModelCapability
import dev.agentrelay.speech.api.SpeechModelDescriptor
import dev.agentrelay.speech.api.SpeechModelId
import dev.agentrelay.speech.api.SpeechModelLicense
import dev.agentrelay.speech.api.SpeechModelPackage
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SpeechPackagePolicyTest {
    @Test
    fun meteredChoiceAndStorageAreFailClosed() {
        val descriptor = descriptor()
        assertEquals(
            SpeechPackagePolicyDecision.MeteredNetwork,
            SpeechPackageInstallPolicy(true, false, 1_000).check(descriptor),
        )
        assertEquals(
            SpeechPackagePolicyDecision.InsufficientStorage(120),
            SpeechPackageInstallPolicy(false, false, 119).check(descriptor),
        )
        assertEquals(
            SpeechPackagePolicyDecision.Allowed(70),
            SpeechPackageInstallPolicy(false, false, 70, persistedDownloadBytes = 50).check(descriptor),
        )
    }

    @Test
    fun policyDownloaderDoesNotOpenDelegateWhenBlocked() = runTest {
        var opened = false
        val delegate = SpeechPackageDownloader { _, _ -> opened = true }
        val downloader = PolicySpeechPackageDownloader(delegate) { _, offset ->
            SpeechPackageInstallPolicy(true, false, 1_000, offset)
        }

        val failure = assertFailsWith<SpeechPackageDeliveryException> {
            downloader.download(descriptor(), ByteArrayOutputStream())
        }
        assertEquals("MODEL_METERED_NETWORK", failure.code)
        assertEquals(false, opened)
    }

    private fun descriptor() = SpeechModelDescriptor(
        SpeechModelId("synthetic-policy"), "Synthetic", "1", setOf("en"),
        setOf(SpeechModelCapability.TRANSCRIPTION),
        SpeechModelLicense("Synthetic", "MIT", "https://example.com/license"),
        SpeechModelPackage("https://models.example.com/model", "1".repeat(64), 50, 70),
    )
}
