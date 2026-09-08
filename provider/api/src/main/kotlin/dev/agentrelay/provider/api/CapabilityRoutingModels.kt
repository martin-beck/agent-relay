/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.api

enum class RoutingAvailability {
    AVAILABLE,
    DEGRADED,
    UNAVAILABLE,
}

enum class RoutingPrivacyClass {
    PUBLIC_METADATA,
    REDACTED_CONTENT,
    SENSITIVE_CONTENT,
}

enum class RoutingRejectionReason {
    WRONG_SUBJECT,
    MISSING_CAPABILITY,
    DATA_LOCATION_NOT_ALLOWED,
    PRIVACY_NOT_ALLOWED,
    PERMISSION_NOT_ALLOWED,
    UNAVAILABLE,
    COST_LIMIT,
    CONTEXT_MISMATCH,
    LOAD_LIMIT,
}

data class RoutingPolicy(
    val subjectKind: CapabilitySubjectKind,
    val requiredCapabilities: Set<String>,
    val allowedDataLocationClasses: Set<String>,
    val allowedPrivacyClasses: Set<RoutingPrivacyClass>,
    val allowedPermissions: Set<CapabilityPermission>,
    val maxCostMicros: Long,
    val requiredContext: Set<String> = emptySet(),
    val maxLoadPercent: Int = 100,
    val maxAttempts: Int = 3,
) {
    init {
        require(requiredCapabilities.isNotEmpty()) { "At least one capability is required" }
        require(requiredCapabilities.all(::isRoutingIdentifier)) {
            "Capabilities must be bounded lowercase identifiers"
        }
        require(allowedDataLocationClasses.all(::isRoutingIdentifier)) {
            "Data location classes must be bounded lowercase identifiers"
        }
        require(requiredContext.all(::isRoutingIdentifier)) {
            "Context values must be bounded lowercase identifiers"
        }
        require(maxCostMicros >= 0) { "Maximum cost must not be negative" }
        require(maxLoadPercent in 0..100) { "Maximum load must be a percentage" }
        require(maxAttempts in 1..MAX_ROUTING_ATTEMPTS) { "Attempt bound is invalid" }
    }
}

data class RoutingCandidate(
    val subjectId: CapabilityRecordId,
    val subjectKind: CapabilitySubjectKind,
    val capabilities: Set<String>,
    val dataLocationClass: String,
    val privacyClass: RoutingPrivacyClass,
    val permissions: Set<CapabilityPermission>,
    val availability: RoutingAvailability,
    val costMicros: Long,
    val context: Set<String> = emptySet(),
    val loadPercent: Int = 0,
) {
    init {
        require(capabilities.isNotEmpty()) { "Routing candidate must expose a capability" }
        require(capabilities.all(::isRoutingIdentifier)) {
            "Capabilities must be bounded lowercase identifiers"
        }
        require(isRoutingIdentifier(dataLocationClass)) {
            "Data location class must be a bounded lowercase identifier"
        }
        require(context.all(::isRoutingIdentifier)) {
            "Context values must be bounded lowercase identifiers"
        }
        require(costMicros >= 0) { "Candidate cost must not be negative" }
        require(loadPercent in 0..100) { "Candidate load must be a percentage" }
    }
}

data class RoutingAttempt(
    val subjectId: CapabilityRecordId,
    val rejection: RoutingRejectionReason? = null,
)

data class RoutingDecision(
    val selected: RoutingCandidate?,
    val attempts: List<RoutingAttempt>,
) {
    init {
        require(attempts.size <= MAX_ROUTING_ATTEMPTS) { "Routing attempts are too large" }
        require(selected == null || attempts.lastOrNull()?.subjectId == selected.subjectId) {
            "Selected candidate must be the final routing attempt"
        }
        require(attempts.map { it.subjectId }.distinct().size == attempts.size) {
            "Routing attempts must have unique subjects"
        }
    }
}

fun route(policy: RoutingPolicy, candidates: List<RoutingCandidate>): RoutingDecision {
    require(candidates.size <= MAX_ROUTING_CANDIDATES) { "Too many routing candidates" }
    require(candidates.map { it.subjectId }.distinct().size == candidates.size) {
        "Routing candidates must have unique subjects"
    }
    val ordered = candidates.sortedWith(
        compareBy<RoutingCandidate> { availabilityRank(it.availability) }
            .thenBy { it.costMicros }
            .thenBy { it.loadPercent }
            .thenBy { it.subjectId.value },
    )
    val attempts = mutableListOf<RoutingAttempt>()
    for (candidate in ordered) {
        val rejection = candidate.rejectionFor(policy)
        if (rejection == null) {
            attempts += RoutingAttempt(candidate.subjectId)
            return RoutingDecision(candidate, attempts)
        }
        if (attempts.size == policy.maxAttempts) break
        attempts += RoutingAttempt(candidate.subjectId, rejection)
    }
    return RoutingDecision(null, attempts)
}

private fun RoutingCandidate.rejectionFor(policy: RoutingPolicy): RoutingRejectionReason? = when {
    subjectKind != policy.subjectKind -> RoutingRejectionReason.WRONG_SUBJECT
    !policy.requiredCapabilities.all(capabilities::contains) -> RoutingRejectionReason.MISSING_CAPABILITY
    dataLocationClass !in policy.allowedDataLocationClasses -> RoutingRejectionReason.DATA_LOCATION_NOT_ALLOWED
    privacyClass !in policy.allowedPrivacyClasses -> RoutingRejectionReason.PRIVACY_NOT_ALLOWED
    !permissions.all(policy.allowedPermissions::contains) -> RoutingRejectionReason.PERMISSION_NOT_ALLOWED
    availability == RoutingAvailability.UNAVAILABLE -> RoutingRejectionReason.UNAVAILABLE
    costMicros > policy.maxCostMicros -> RoutingRejectionReason.COST_LIMIT
    !context.containsAll(policy.requiredContext) -> RoutingRejectionReason.CONTEXT_MISMATCH
    loadPercent > policy.maxLoadPercent -> RoutingRejectionReason.LOAD_LIMIT
    else -> null
}

private fun availabilityRank(availability: RoutingAvailability): Int = when (availability) {
    RoutingAvailability.AVAILABLE -> 0
    RoutingAvailability.DEGRADED -> 1
    RoutingAvailability.UNAVAILABLE -> 2
}

private fun isRoutingIdentifier(value: String): Boolean = value.matches(ROUTING_IDENTIFIER)

private val ROUTING_IDENTIFIER = Regex("[a-z][a-z0-9._-]{0,63}")
private const val MAX_ROUTING_CANDIDATES = 256
private const val MAX_ROUTING_ATTEMPTS = 32
