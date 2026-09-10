/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalAiDetectionModelsTest {
    @Test
    fun `virtual device validation never proves physical inference`() {
        val observation = observation(
            environment = LocalAiExecutionEnvironment.VIRTUAL_DEVICE,
            validation = LocalAiValidationLevel.MODEL_LOADABLE,
        )

        assertFalse(observation.provesPhysicalInference())
    }

    @Test
    fun `physical inference requires the strongest validation level`() {
        assertFalse(
            observation(
                environment = LocalAiExecutionEnvironment.PHYSICAL_DEVICE,
                validation = LocalAiValidationLevel.MODEL_LOADABLE,
            ).provesPhysicalInference(),
        )
        assertTrue(
            observation(
                environment = LocalAiExecutionEnvironment.PHYSICAL_DEVICE,
                validation = LocalAiValidationLevel.INFERENCE_VERIFIED,
            ).provesPhysicalInference(),
        )
    }

    @Test
    fun `virtual and unknown environments cannot claim verified inference`() {
        assertFailsWith<IllegalArgumentException> {
            observation(
                environment = LocalAiExecutionEnvironment.VIRTUAL_DEVICE,
                validation = LocalAiValidationLevel.INFERENCE_VERIFIED,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            observation(
                environment = LocalAiExecutionEnvironment.UNKNOWN,
                validation = LocalAiValidationLevel.INFERENCE_VERIFIED,
            )
        }
    }

    @Test
    fun `observation metadata is bounded and does not accept paths`() {
        assertFailsWith<IllegalArgumentException> {
            observation(dataLocationClass = "/data/local/tmp/model")
        }
        assertFailsWith<IllegalArgumentException> {
            observation(reasonCode = "model unavailable")
        }
        assertFailsWith<IllegalArgumentException> {
            observation(observedAtEpochSeconds = -1)
        }
    }

    private fun observation(
        environment: LocalAiExecutionEnvironment = LocalAiExecutionEnvironment.VIRTUAL_DEVICE,
        validation: LocalAiValidationLevel = LocalAiValidationLevel.API_AVAILABLE,
        dataLocationClass: String = "device.local",
        observedAtEpochSeconds: Long = 42,
        reasonCode: String? = null,
    ) = LocalAiModelObservation(
        modelId = CapabilityRecordId("phone-model"),
        runtimeId = CapabilityRecordId("android-runtime"),
        capabilities = setOf("text.generate"),
        dataLocationClass = dataLocationClass,
        environment = environment,
        validation = validation,
        observedAtEpochSeconds = observedAtEpochSeconds,
        reasonCode = reasonCode,
    )
}
