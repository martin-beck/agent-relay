/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.speech.sherpa

import dev.agentrelay.speech.api.SpeechModelId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SherpaStreamingTransducerSpecTest {
    @Test
    fun boundedSupportedLayoutIsRetainedExactly() {
        val spec = spec(
            encoderFile = "acoustic/encoder.int8.onnx",
            decoderFile = "acoustic/decoder.onnx",
            joinerFile = "acoustic/joiner.onnx",
            tokensFile = "vocabulary/tokens.txt",
            modelType = "zipformer2",
            sampleRateHz = 22_050,
            featureDimension = 64,
            numThreads = 4,
        )

        assertEquals("acoustic/encoder.int8.onnx", spec.encoderFile)
        assertEquals("acoustic/decoder.onnx", spec.decoderFile)
        assertEquals("acoustic/joiner.onnx", spec.joinerFile)
        assertEquals("vocabulary/tokens.txt", spec.tokensFile)
        assertEquals("zipformer2", spec.modelType)
        assertEquals(22_050, spec.sampleRateHz)
        assertEquals(64, spec.featureDimension)
        assertEquals(4, spec.numThreads)
    }

    @Test
    fun unsafeOrUnnormalizedModelPathsAreRejected() {
        val unsafePaths = listOf(
            "/absolute/encoder.onnx",
            "../encoder.onnx",
            "models/../encoder.onnx",
            "./encoder.onnx",
            "models//encoder.onnx",
            "models/encoder file.onnx",
            "models/" + 92.toChar() + "encoder.onnx",
            "models/" + 10.toChar() + "encoder.onnx",
            (1..9).joinToString("/") { "segment" } + "/encoder.onnx",
            "a".repeat(513),
        )

        unsafePaths.forEach { encoder ->
            assertFailsWith<IllegalArgumentException>(encoder) {
                spec(encoderFile = encoder)
            }
        }
    }

    @Test
    fun ambiguousOrUnsupportedRuntimeSettingsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            spec(decoderFile = "encoder.onnx")
        }
        listOf("ctc", "Zipformer2", "").forEach { modelType ->
            assertFailsWith<IllegalArgumentException>(modelType) {
                spec(modelType = modelType)
            }
        }
        listOf(7_999, 48_001).forEach { sampleRate ->
            assertFailsWith<IllegalArgumentException>(sampleRate.toString()) {
                spec(sampleRateHz = sampleRate)
            }
        }
        listOf(0, 257).forEach { featureDimension ->
            assertFailsWith<IllegalArgumentException>(featureDimension.toString()) {
                spec(featureDimension = featureDimension)
            }
        }
        listOf(0, 5).forEach { numThreads ->
            assertFailsWith<IllegalArgumentException>(numThreads.toString()) {
                spec(numThreads = numThreads)
            }
        }
    }

    private fun spec(
        encoderFile: String = "encoder.onnx",
        decoderFile: String = "decoder.onnx",
        joinerFile: String = "joiner.onnx",
        tokensFile: String = "tokens.txt",
        modelType: String = "zipformer2",
        sampleRateHz: Int = 16_000,
        featureDimension: Int = 80,
        numThreads: Int = 2,
    ) = SherpaStreamingTransducerSpec(
        modelId = SpeechModelId("test-asr"),
        encoderFile = encoderFile,
        decoderFile = decoderFile,
        joinerFile = joinerFile,
        tokensFile = tokensFile,
        modelType = modelType,
        sampleRateHz = sampleRateHz,
        featureDimension = featureDimension,
        numThreads = numThreads,
    )
}
