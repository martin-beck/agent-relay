/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.api

import java.security.MessageDigest

/** Whether extension code is supplied by the signed host or must be isolated. */
enum class ExtensionExecutionClass {
    TRUSTED_BUILT_IN,
    SANDBOXED,
}

enum class ExtensionNetworkAccess {
    NONE,
    ALLOWLIST,
}

enum class ExtensionModelAccess {
    NONE,
    DECLARED,
}

/** Hard resource and authority limits applied before extension code is started. */
data class ExtensionSandboxLimits(
    val maxCpuMillis: Long,
    val maxWallMillis: Long,
    val maxMemoryBytes: Long,
    val maxStorageBytes: Long,
    val maxInputBytes: Int,
    val maxOutputBytes: Int,
    val maxInvocations: Int,
    val networkAccess: ExtensionNetworkAccess = ExtensionNetworkAccess.NONE,
    val allowedHosts: Set<String> = emptySet(),
    val subprocessAllowed: Boolean = false,
    val filesystemRoot: String = "workspace",
    val modelAccess: ExtensionModelAccess = ExtensionModelAccess.NONE,
) {
    init {
        require(maxCpuMillis in 1..300_000) { "CPU limit must be between 1 ms and 5 minutes" }
        require(maxWallMillis in 1..300_000) { "Wall limit must be between 1 ms and 5 minutes" }
        require(maxCpuMillis <= maxWallMillis) { "CPU limit must not exceed wall limit" }
        require(maxMemoryBytes in 1_048_576..1_073_741_824) { "Memory limit must be between 1 MiB and 1 GiB" }
        require(maxStorageBytes in 1..10_737_418_240) { "Storage limit must be between 1 byte and 10 GiB" }
        require(maxInputBytes in 1..1_048_576) { "Input limit must be between 1 byte and 1 MiB" }
        require(maxOutputBytes in 1..1_048_576) { "Output limit must be between 1 byte and 1 MiB" }
        require(maxInvocations in 1..10_000) { "Invocation limit must be between 1 and 10000" }
        require(networkAccess != ExtensionNetworkAccess.NONE || allowedHosts.isEmpty()) {
            "Network hosts require allowlisted network access"
        }
        require(allowedHosts.all { it.matches(Regex("[a-zA-Z0-9.-]{1,253}")) }) {
            "Network host is invalid"
        }
        require(filesystemRoot.matches(Regex("[a-zA-Z0-9._/-]{1,128}"))) {
            "Filesystem root is invalid"
        }
        require(!filesystemRoot.startsWith("/") && !filesystemRoot.contains("..")) {
            "Filesystem root must be relative and confined"
        }
    }
}

/** Policy is an admission boundary; it is not an authorization to bypass the host. */
data class ExtensionSandboxPolicy(
    val executionClass: ExtensionExecutionClass,
    val limits: ExtensionSandboxLimits,
    val requireApprovalForExternalEffects: Boolean = true,
    val allowExternalEffects: Boolean = false,
) {
    init {
        require(executionClass == ExtensionExecutionClass.SANDBOXED || !allowExternalEffects) {
            "Built-in extensions cannot opt into unreviewed external effects"
        }
        require(!allowExternalEffects || requireApprovalForExternalEffects) {
            "External effects require explicit approval"
        }
    }
}

enum class ExtensionApprovalState {
    NOT_REQUIRED,
    PENDING,
    APPROVED,
    DENIED,
}

enum class ExtensionExecutionOutcome {
    ACCEPTED,
    REJECTED,
    COMPLETED,
    FAILED,
    CANCELLED,
    UNCERTAIN,
}

enum class ExtensionSandboxRejection {
    POLICY_DENIED,
    APPROVAL_REQUIRED,
    APPROVAL_DENIED,
    RESOURCE_LIMIT_EXCEEDED,
    NETWORK_DENIED,
    SUBPROCESS_DENIED,
    FILESYSTEM_DENIED,
    MODEL_ACCESS_DENIED,
    CANCELLED,
    STALE_REVISION,
}

data class ExtensionAgentRequest(
    val invocation: ExtensionInvocation,
    val policy: ExtensionSandboxPolicy,
    val requiresExternalEffect: Boolean = false,
    val requestedHost: String? = null,
    val requestsSubprocess: Boolean = false,
    val requestsModel: Boolean = false,
) {
    init {
        requestedHost?.let {
            require(it.matches(Regex("[a-zA-Z0-9.-]{1,253}"))) { "Requested host is invalid" }
        }
    }
}

sealed interface ExtensionAdmission {
    data class Accepted(
        val permissions: Set<ExtensionPermission>,
        val approval: ExtensionApprovalState,
    ) : ExtensionAdmission

    data class Rejected(val reason: ExtensionSandboxRejection) : ExtensionAdmission
}

/** Provider-neutral adapter seam for optional extension-backed agent execution. */
fun interface ExtensionAgentAdapter {
    suspend fun execute(request: ExtensionAgentRequest, cancellationRequested: () -> Boolean): ExtensionAgentResult
}

data class ExtensionAgentResult(
    val outcome: ExtensionExecutionOutcome,
    val output: String = "",
    val externalEffectObserved: Boolean = false,
    val cpuMillis: Long = 0,
    val wallMillis: Long = 0,
    val memoryBytes: Long = 0,
    val storageBytes: Long = 0,
) {
    init {
        require(outcome == ExtensionExecutionOutcome.COMPLETED || output.isEmpty() || output.length <= 1_048_576) {
            "Extension output exceeds the maximum result size"
        }
        require(outcome != ExtensionExecutionOutcome.UNCERTAIN || externalEffectObserved) {
            "Uncertain extension results require an observed external-effect boundary"
        }
        require(cpuMillis >= 0 && wallMillis >= 0 && memoryBytes >= 0 && storageBytes >= 0) {
            "Extension resource observations must not be negative"
        }
    }
}

/** Redacted, deterministic evidence; raw prompts, credentials and content never enter this record. */
data class ExtensionExecutionEvidence(
    val extensionId: ExtensionId,
    val idempotencyKey: String,
    val inputDigest: String,
    val outputDigest: String,
    val policyDigest: String,
    val outcome: ExtensionExecutionOutcome,
    val approval: ExtensionApprovalState,
    val rejection: ExtensionSandboxRejection? = null,
    val inputBytes: Int,
    val outputBytes: Int,
    val cancellationRequested: Boolean,
) {
    init {
        require(idempotencyKey.matches(Regex("[A-Za-z0-9._-]{8,128}"))) { "Invalid evidence idempotency key" }
        listOf(inputDigest, outputDigest, policyDigest).forEach {
            require(it.matches(Regex("[0-9a-f]{64}"))) { "Evidence digest must be SHA-256" }
        }
        require(inputBytes >= 0 && outputBytes >= 0) { "Evidence sizes must not be negative" }
        require(outcome == ExtensionExecutionOutcome.REJECTED == (rejection != null)) {
            "Rejected evidence must carry exactly one rejection reason"
        }
    }
}

/**
 * Admission and evidence coordinator. It never executes code itself, and therefore cannot
 * accidentally grant a connector more authority than the host policy selected.
 */
class ExtensionSandboxCoordinator(
    private val invocationLedger: ExtensionInvocationLedger = ExtensionInvocationLedger(),
) {
    fun admit(
        manifest: ExtensionManifest,
        request: ExtensionAgentRequest,
        hostApi: ExtensionApiVersion,
        hostSchema: Int,
        grantedPermissions: Set<ExtensionPermission>,
        currentRevision: Long,
        revoked: Boolean = false,
        cancelled: Boolean = false,
    ): ExtensionAdmission {
        val rejection = policyRejection(request, cancelled)
        if (rejection != null) return ExtensionAdmission.Rejected(rejection)
        val decision = invocationLedger.evaluate(
            manifest,
            request.invocation,
            hostApi,
            hostSchema,
            grantedPermissions,
            request.invocation.input.entries.sumOf { it.key.value.length + it.value.length },
            currentRevision,
            revoked,
            cancelled,
        )
        if (decision is ExtensionInvocationDecision.Rejected) {
            return ExtensionAdmission.Rejected(
                if (decision.reason == ExtensionRejection.CANCELLED) {
                    ExtensionSandboxRejection.CANCELLED
                } else if (decision.reason == ExtensionRejection.STALE_REVISION) {
                    ExtensionSandboxRejection.STALE_REVISION
                } else {
                    ExtensionSandboxRejection.POLICY_DENIED
                },
            )
        }
        val approval = if (request.requiresExternalEffect && request.policy.requireApprovalForExternalEffects) {
            ExtensionApprovalState.PENDING
        } else {
            ExtensionApprovalState.NOT_REQUIRED
        }
        return ExtensionAdmission.Accepted((decision as ExtensionInvocationDecision.Accepted).permissions, approval)
    }

    fun evidence(
        request: ExtensionAgentRequest,
        admission: ExtensionAdmission,
        result: ExtensionAgentResult? = null,
        cancellationRequested: Boolean = false,
    ): ExtensionExecutionEvidence {
        val rejected = (admission as? ExtensionAdmission.Rejected)?.reason
        val outcome = when {
            rejected != null -> ExtensionExecutionOutcome.REJECTED
            cancellationRequested -> ExtensionExecutionOutcome.CANCELLED
            result != null -> result.outcome
            else -> ExtensionExecutionOutcome.ACCEPTED
        }
        val approval = (admission as? ExtensionAdmission.Accepted)?.approval ?: ExtensionApprovalState.DENIED
        val output = result?.output.orEmpty()
        val resourceExceeded = result?.let {
            it.cpuMillis > request.policy.limits.maxCpuMillis ||
                it.wallMillis > request.policy.limits.maxWallMillis ||
                it.memoryBytes > request.policy.limits.maxMemoryBytes ||
                it.storageBytes > request.policy.limits.maxStorageBytes ||
                output.toByteArray().size > request.policy.limits.maxOutputBytes
        } == true
        val effectiveRejection = rejected ?: if (resourceExceeded) {
            ExtensionSandboxRejection.RESOURCE_LIMIT_EXCEEDED
        } else {
            null
        }
        return ExtensionExecutionEvidence(
            request.invocation.extensionId,
            request.invocation.idempotencyKey,
            digest(
                request.invocation.input.entries
                    .sortedBy { it.key.value }
                    .joinToString("|") { "${it.key.value}=${it.value}" },
            ),
            digest(output),
            digest(policyCanonical(request.policy)),
            if (effectiveRejection != null) ExtensionExecutionOutcome.REJECTED else outcome,
            approval,
            effectiveRejection,
            request.invocation.input.entries.sumOf { it.key.value.length + it.value.length },
            output.toByteArray().size,
            cancellationRequested,
        )
    }

    private fun policyRejection(request: ExtensionAgentRequest, cancelled: Boolean): ExtensionSandboxRejection? {
        if (cancelled) return ExtensionSandboxRejection.CANCELLED
        val limits = request.policy.limits
        if (request.requestedHost != null &&
            (limits.networkAccess == ExtensionNetworkAccess.NONE || request.requestedHost !in limits.allowedHosts)
        ) {
            return ExtensionSandboxRejection.NETWORK_DENIED
        }
        if (request.requestsSubprocess && !limits.subprocessAllowed) return ExtensionSandboxRejection.SUBPROCESS_DENIED
        if (request.requestsModel && limits.modelAccess == ExtensionModelAccess.NONE) {
            return ExtensionSandboxRejection.MODEL_ACCESS_DENIED
        }
        if (request.requiresExternalEffect && !request.policy.allowExternalEffects) {
            return ExtensionSandboxRejection.POLICY_DENIED
        }
        return null
    }
}

private fun policyCanonical(policy: ExtensionSandboxPolicy): String = buildString {
    append(policy.executionClass).append('|')
    append(policy.requireApprovalForExternalEffects).append('|')
    append(policy.allowExternalEffects).append('|')
    with(policy.limits) {
        append(maxCpuMillis).append('|').append(maxWallMillis).append('|')
        append(maxMemoryBytes).append('|').append(maxStorageBytes).append('|')
        append(maxInputBytes).append('|').append(maxOutputBytes).append('|')
        append(maxInvocations).append('|').append(networkAccess).append('|')
        append(allowedHosts.sorted().joinToString(",")).append('|')
        append(subprocessAllowed).append('|').append(filesystemRoot).append('|').append(modelAccess)
    }
}

private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }
