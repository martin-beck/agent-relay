/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.api

/** The environment in which a local-model observation was made. */
enum class LocalAiExecutionEnvironment {
    PHYSICAL_DEVICE,
    VIRTUAL_DEVICE,
    UNKNOWN,
}

/** What a bounded local-model probe was able to establish. */
enum class LocalAiValidationLevel {
    API_AVAILABLE,
    MODEL_METADATA_AVAILABLE,
    MODEL_LOADABLE,
    INFERENCE_VERIFIED,
    UNSUPPORTED,
    UNKNOWN,
}

/** A local-model observation deliberately excludes addresses, paths and hardware identifiers. */
data class LocalAiModelObservation(
    val modelId: CapabilityRecordId,
    val runtimeId: CapabilityRecordId,
    val capabilities: Set<String>,
    val dataLocationClass: String,
    val environment: LocalAiExecutionEnvironment,
    val validation: LocalAiValidationLevel,
    val observedAtEpochSeconds: Long,
    val reasonCode: String? = null,
) {
    init {
        require(capabilities.isNotEmpty()) { "A local model must expose a capability" }
        require(capabilities.size <= MAX_LOCAL_AI_CAPABILITIES) {
            "Local model capability set is too large"
        }
        require(capabilities.all(::isLocalAiIdentifier)) {
            "Local model capabilities must be bounded lowercase identifiers"
        }
        require(isLocalAiIdentifier(dataLocationClass)) {
            "Data location class must be a bounded lowercase identifier"
        }
        require(observedAtEpochSeconds >= 0) { "Observation time must not be negative" }
        require(reasonCode == null || isLocalAiIdentifier(reasonCode)) {
            "Reason code must be a bounded lowercase identifier"
        }
        require(
            validation != LocalAiValidationLevel.INFERENCE_VERIFIED ||
                environment == LocalAiExecutionEnvironment.PHYSICAL_DEVICE,
        ) {
            "Virtual or unknown environments cannot prove on-device inference"
        }
    }
}

/**
 * Whether the observation is strong enough to route real user data to a phone-local model.
 * API, metadata, loadability, mock and emulator evidence intentionally returns false.
 */
fun LocalAiModelObservation.provesPhysicalInference(): Boolean =
    environment == LocalAiExecutionEnvironment.PHYSICAL_DEVICE &&
        validation == LocalAiValidationLevel.INFERENCE_VERIFIED

const val MAX_LOCAL_AI_CAPABILITIES = 64

private fun isLocalAiIdentifier(value: String): Boolean =
    value.matches(LOCAL_AI_IDENTIFIER)

private val LOCAL_AI_IDENTIFIER = Regex("[a-z][a-z0-9._-]{0,63}")
