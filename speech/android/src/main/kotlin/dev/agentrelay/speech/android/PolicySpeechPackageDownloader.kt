/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.speech.android

import dev.agentrelay.speech.api.SpeechModelDescriptor
import java.io.OutputStream

/** Applies the user's metered-network choice before any network connection is opened. */
class PolicySpeechPackageDownloader(
    private val delegate: SpeechPackageDownloader,
    private val policy: (SpeechModelDescriptor, Long) -> SpeechPackageInstallPolicy,
) : ResumableSpeechPackageDownloader {
    override suspend fun download(descriptor: SpeechModelDescriptor, destination: OutputStream) {
        enforce(descriptor, 0L)
        delegate.download(descriptor, destination)
    }

    override suspend fun resumeDownload(
        descriptor: SpeechModelDescriptor,
        offsetBytes: Long,
        destination: OutputStream,
    ): SpeechPackageResumeResult {
        enforce(descriptor, offsetBytes)
        val resumable = delegate as? ResumableSpeechPackageDownloader
            ?: return SpeechPackageResumeResult.RESTART_REQUIRED
        return resumable.resumeDownload(descriptor, offsetBytes, destination)
    }

    private fun enforce(descriptor: SpeechModelDescriptor, offsetBytes: Long) {
        when (val decision = policy(descriptor, offsetBytes).check(descriptor)) {
            is SpeechPackagePolicyDecision.Allowed -> Unit
            SpeechPackagePolicyDecision.MeteredNetwork -> throw SpeechPackageDeliveryException(
                "MODEL_METERED_NETWORK",
                "Allow metered data or connect to an unmetered network to download this package.",
            )
            is SpeechPackagePolicyDecision.InsufficientStorage -> throw SpeechPackageDeliveryException(
                "MODEL_STORAGE_LIMIT",
                "Free app storage before downloading this speech package.",
            )
            SpeechPackagePolicyDecision.InvalidPersistedDownload,
            SpeechPackagePolicyDecision.InvalidPackageSize,
            -> throw SpeechPackageDeliveryException(
                "MODEL_DOWNLOAD_INVALID",
                "The speech package metadata is invalid. Update the app catalog.",
            )
        }
    }
}
