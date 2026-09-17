/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.speech.android

import dev.agentrelay.speech.api.SpeechModelDescriptor

data class SpeechPackageInstallPolicy(
    val networkIsMetered: Boolean,
    val allowMeteredNetwork: Boolean,
    val availableStorageBytes: Long,
    val persistedDownloadBytes: Long = 0L,
) {
    init {
        require(availableStorageBytes >= 0L) { "Available storage must not be negative" }
        require(persistedDownloadBytes >= 0L) { "Persisted download bytes must not be negative" }
    }

    fun check(descriptor: SpeechModelDescriptor): SpeechPackagePolicyDecision {
        if (networkIsMetered && !allowMeteredNetwork) {
            return SpeechPackagePolicyDecision.MeteredNetwork
        }
        val remaining = descriptor.modelPackage.downloadSizeBytes - persistedDownloadBytes
        if (remaining < 0L) {
            return SpeechPackagePolicyDecision.InvalidPersistedDownload
        }
        val required = runCatching {
            Math.addExact(remaining, descriptor.modelPackage.installedSizeBytes)
        }.getOrElse { return SpeechPackagePolicyDecision.InvalidPackageSize }
        return if (availableStorageBytes < required) {
            SpeechPackagePolicyDecision.InsufficientStorage(required)
        } else {
            SpeechPackagePolicyDecision.Allowed(required)
        }
    }
}

sealed interface SpeechPackagePolicyDecision {
    data class Allowed(val requiredBytes: Long) : SpeechPackagePolicyDecision
    data object MeteredNetwork : SpeechPackagePolicyDecision
    data class InsufficientStorage(val requiredBytes: Long) : SpeechPackagePolicyDecision
    data object InvalidPersistedDownload : SpeechPackagePolicyDecision
    data object InvalidPackageSize : SpeechPackagePolicyDecision
}
