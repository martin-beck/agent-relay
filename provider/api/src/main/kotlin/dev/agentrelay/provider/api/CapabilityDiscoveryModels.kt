/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.api

enum class CapabilityPermission {
    READ_ONLY,
    EXECUTE,
    NETWORK,
    SENSITIVE_DATA,
}

data class CapabilityDiscoveryRequest(
    val subjectKind: CapabilitySubjectKind,
    val requiredCapabilities: Set<String>,
    val allowedDataLocationClasses: Set<String>,
    val allowedPermissions: Set<CapabilityPermission>,
) {
    init {
        require(requiredCapabilities.isNotEmpty()) { "At least one capability is required" }
        require(requiredCapabilities.all { it.matches(Regex("[a-z][a-z0-9._-]{0,63}")) }) {
            "Capabilities must be bounded lowercase identifiers"
        }
        require(allowedDataLocationClasses.all { it.matches(Regex("[a-z][a-z0-9._-]{0,63}")) }) {
            "Data location classes must be bounded lowercase identifiers"
        }
    }
}

data class DiscoveredCapability(
    val subjectId: CapabilityRecordId,
    val subjectKind: CapabilitySubjectKind,
    val capabilities: Set<String>,
    val dataLocationClass: String,
    val permissions: Set<CapabilityPermission>,
    val health: CapabilityHealthState,
) {
    init {
        require(capabilities.isNotEmpty()) { "Discovered subject must expose a capability" }
        require(capabilities.all { it.matches(Regex("[a-z][a-z0-9._-]{0,63}")) }) {
            "Capabilities must be bounded lowercase identifiers"
        }
        require(dataLocationClass.matches(Regex("[a-z][a-z0-9._-]{0,63}"))) {
            "Data location class must be a bounded lowercase identifier"
        }
    }
}

data class CapabilityDiscoveryResult(
    val matches: List<DiscoveredCapability>,
    val observedAtEpochSeconds: Long,
    val redactedReason: String? = null,
) {
    init {
        require(matches.size <= MAX_RECORDS) { "Discovery result is too large" }
        require(observedAtEpochSeconds >= 0) { "Observation time must not be negative" }
        require(redactedReason == null || redactedReason.matches(Regex("[a-z][a-z0-9._-]{0,63}"))) {
            "Redacted reason must be a bounded lowercase identifier"
        }
    }
}

fun DiscoveredCapability.matches(request: CapabilityDiscoveryRequest): Boolean =
    subjectKind == request.subjectKind &&
        request.requiredCapabilities.all(capabilities::contains) &&
        dataLocationClass in request.allowedDataLocationClasses &&
        permissions.all(request.allowedPermissions::contains) &&
        health == CapabilityHealthState.HEALTHY
