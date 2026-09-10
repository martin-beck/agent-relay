/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.speech.sherpa

import dev.agentrelay.speech.api.SpeechModelId
import java.nio.file.InvalidPathException
import java.nio.file.Paths

/**
 * Exact runtime layout for one admitted streaming transducer model.
 *
 * This is deliberately separate from public catalog metadata. Constructing a spec does not admit
 * a download source or bypass the model-store checksum, license, and device-evidence gates.
 */
data class SherpaStreamingTransducerSpec(
    val modelId: SpeechModelId,
    val encoderFile: String,
    val decoderFile: String,
    val joinerFile: String,
    val tokensFile: String,
    val modelType: String,
    val sampleRateHz: Int = 16_000,
    val featureDimension: Int = 80,
    val numThreads: Int = 2,
) {
    init {
        requireModelFile(encoderFile)
        requireModelFile(decoderFile)
        requireModelFile(joinerFile)
        requireModelFile(tokensFile)
        require(setOf(encoderFile, decoderFile, joinerFile, tokensFile).size == 4) {
            "Sherpa model files must be distinct"
        }
        require(modelType in SUPPORTED_MODEL_TYPES) {
            "Sherpa transducer model type is unsupported"
        }
        require(sampleRateHz in 8_000..48_000) {
            "Sherpa model sample rate is unsupported"
        }
        require(featureDimension in 1..256) {
            "Sherpa model feature dimension is unsupported"
        }
        require(numThreads in 1..4) {
            "Sherpa model thread count is unsupported"
        }
    }

    private companion object {
        val SUPPORTED_MODEL_TYPES = setOf("lstm", "zipformer", "zipformer2")
    }
}

private fun requireModelFile(value: String) {
    require(value.length in 1..MAX_MODEL_PATH_CHARS) {
        "Sherpa model file path length is invalid"
    }
    require(value.none { it.code == BACKSLASH_CODE || it.isISOControl() }) {
        "Sherpa model file path contains an unsafe character"
    }
    val path = try {
        Paths.get(value)
    } catch (_: InvalidPathException) {
        throw IllegalArgumentException("Sherpa model file path is invalid")
    }
    val segments = value.split('/')
    require(
        segments.size in 1..MAX_MODEL_PATH_SEGMENTS &&
            segments.all(MODEL_PATH_SEGMENT::matches),
    ) {
        "Sherpa model file path segment is invalid"
    }
    require(!path.isAbsolute && path.nameCount == segments.size) {
        "Sherpa model file path must be a bounded relative path"
    }
    require(path.normalize() == path) {
        "Sherpa model file path must be normalized"
    }
}

private const val BACKSLASH_CODE = 92
private const val MAX_MODEL_PATH_CHARS = 512
private const val MAX_MODEL_PATH_SEGMENTS = 8
private val MODEL_PATH_SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
