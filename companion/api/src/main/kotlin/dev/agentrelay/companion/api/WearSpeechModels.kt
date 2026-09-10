/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.companion.api

enum class WearSpeechPermission { UNKNOWN, DENIED, GRANTED }
enum class WearSpeechCaptureState { IDLE, LISTENING, TRANSFERRING, REVIEW, CANCELLED, FAILED }

data class WearSpeechCapture(
    val captureId: String,
    val generation: Long,
    val localeTag: String,
    val permission: WearSpeechPermission,
    val state: WearSpeechCaptureState,
    val startedAtEpochMillis: Long,
    val maxDurationMillis: Long = 30_000,
) {
    init {
        require(captureId.matches(Regex("speech_v1_[A-Za-z0-9_-]{8,64}"))) { "Capture id is invalid" }
        require(generation > 0) { "Capture generation must be positive" }
        require(localeTag.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{2,8})?"))) { "Locale is invalid" }
        require(startedAtEpochMillis >= 0) { "Capture start time is invalid" }
        require(maxDurationMillis in 1_000..30_000) { "Capture duration is invalid" }
        require(state != WearSpeechCaptureState.LISTENING || permission == WearSpeechPermission.GRANTED) {
            "Listening requires microphone permission"
        }
    }

    fun timedOutAt(nowEpochMillis: Long): Boolean =
        nowEpochMillis >= startedAtEpochMillis + maxDurationMillis
}

/** Only bounded encrypted transfer chunks cross the phone boundary; raw audio is not retained here. */
data class WearSpeechAudioChunk(
    val captureId: String,
    val generation: Long,
    val sequence: Int,
    val encryptedBytes: ByteArray,
    val finalChunk: Boolean,
) {
    init {
        require(sequence >= 0) { "Chunk sequence is invalid" }
        require(encryptedBytes.isNotEmpty() && encryptedBytes.size <= 64 * 1024) {
            "Chunk size is invalid"
        }
    }
}

data class WearSpeechTranscriptReview(
    val captureId: String,
    val generation: Long,
    val transcript: String,
    val confidencePercent: Int?,
    val reviewed: Boolean = false,
) {
    init {
        require(transcript.isNotBlank() && transcript.length <= 4_000) { "Transcript is invalid" }
        require(confidencePercent == null || confidencePercent in 0..100) {
            "Transcript confidence is invalid"
        }
    }
}
