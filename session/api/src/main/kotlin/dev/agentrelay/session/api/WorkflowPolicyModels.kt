/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.api

/** Side-effect classes used by the host policy boundary. */
enum class WorkflowEffectClass { READ_ONLY, MUTATING, DESTRUCTIVE, EXTERNAL }

enum class WorkflowReversibility { REVERSIBLE, IRREVERSIBLE, UNKNOWN }

enum class WorkflowPolicyDecision {
    ALLOW,
    REQUIRE_APPROVAL,
    DENY,
    BUDGET_EXCEEDED,
    INSUFFICIENT_EVIDENCE,
}

/** A lease is narrower than a workflow and cannot grant a prohibited capability. */
data class WorkflowPermissionLease(
    val id: String,
    val workflowId: WorkflowId,
    val runId: WorkflowRunId,
    val capabilities: Set<String>,
    val prohibitedEffects: Set<WorkflowEffectClass> = emptySet(),
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val maxUses: Int,
    val uses: Int = 0,
) {
    init {
        requirePolicyId(id, "Lease id")
        require(capabilities.isNotEmpty() && capabilities.size <= MAX_POLICY_ITEMS) {
            "Lease capabilities are invalid"
        }
        capabilities.forEach { requirePolicyId(it, "Lease capability") }
        require(issuedAtMillis >= 0 && expiresAtMillis >= issuedAtMillis) { "Lease interval is invalid" }
        require(maxUses in 1..MAX_POLICY_USES && uses in 0..maxUses) { "Lease use bound is invalid" }
    }

    fun isActive(nowMillis: Long): Boolean = nowMillis in issuedAtMillis until expiresAtMillis && uses < maxUses

    fun permits(capability: String, effect: WorkflowEffectClass): Boolean =
        capability in capabilities && effect !in prohibitedEffects
}

data class WorkflowBudgetUsage(
    val cpuMillis: Long = 0,
    val memoryBytes: Long = 0,
    val wallClockMillis: Long = 0,
    val steps: Int = 0,
) {
    init {
        require(cpuMillis >= 0 && memoryBytes >= 0 && wallClockMillis >= 0 && steps >= 0) {
            "Budget usage must not be negative"
        }
    }

    fun within(budget: WorkflowBudget): Boolean =
        cpuMillis <= budget.maxCpuMillis &&
            memoryBytes <= budget.maxMemoryBytes &&
            wallClockMillis <= budget.maxWallClockMillis &&
            steps <= budget.maxSteps

    fun plus(delta: WorkflowBudgetUsage): WorkflowBudgetUsage = WorkflowBudgetUsage(
        cpuMillis = Math.addExact(cpuMillis, delta.cpuMillis),
        memoryBytes = Math.addExact(memoryBytes, delta.memoryBytes),
        wallClockMillis = Math.addExact(wallClockMillis, delta.wallClockMillis),
        steps = Math.addExact(steps, delta.steps),
    )
}

data class WorkflowPolicyRequest(
    val workflowId: WorkflowId,
    val runId: WorkflowRunId,
    val capability: String,
    val effect: WorkflowEffectClass,
    val reversibility: WorkflowReversibility,
    val risk: WorkflowRisk,
    val estimatedUsage: WorkflowBudgetUsage,
    val humanApproved: Boolean = false,
) {
    init {
        requirePolicyId(capability, "Requested capability")
    }
}

data class WorkflowEvidenceReference(
    val id: String,
    val kind: String,
    val digest: String,
    val recordedAtMillis: Long,
    val retainUntilMillis: Long,
    val redacted: Boolean,
) {
    init {
        requirePolicyId(id, "Evidence id")
        requirePolicyId(kind, "Evidence kind")
        requirePolicyId(digest, "Evidence digest")
        require(recordedAtMillis >= 0 && retainUntilMillis >= recordedAtMillis) {
            "Evidence retention interval is invalid"
        }
    }
}

data class WorkflowCompletionObservation(
    val checks: Set<String> = emptySet(),
    val artifacts: Set<String> = emptySet(),
    val evidence: List<WorkflowEvidenceReference> = emptyList(),
    val humanAccepted: Boolean = false,
    val observedAtMillis: Long,
) {
    init {
        require(observedAtMillis >= 0) { "Completion observation timestamp is invalid" }
        require(checks.size <= MAX_POLICY_ITEMS && artifacts.size <= MAX_POLICY_ITEMS) {
            "Completion observation is too large"
        }
        (checks + artifacts).forEach { requirePolicyId(it, "Completion observation item") }
        require(evidence.size <= MAX_POLICY_ITEMS) { "Completion evidence is too large" }
    }
}

data class WorkflowCompletionResult(
    val complete: Boolean,
    val missingChecks: Set<String>,
    val missingArtifacts: Set<String>,
    val missingEvidence: Set<String>,
    val humanAcceptanceMissing: Boolean,
)

object WorkflowCompletionVerifier {
    fun verify(
        contract: WorkflowCompletionContract,
        observation: WorkflowCompletionObservation,
        nowMillis: Long = observation.observedAtMillis,
    ): WorkflowCompletionResult {
        require(nowMillis >= 0) { "Completion verification timestamp is invalid" }
        val missingChecks = contract.requiredChecks - observation.checks
        val missingArtifacts = contract.requiredArtifacts - observation.artifacts
        val validEvidence = observation.evidence.filter { it.retainUntilMillis >= nowMillis }.map { it.id }.toSet()
        val missingEvidence = (contract.requiredChecks + contract.requiredArtifacts) - validEvidence
        val acceptanceMissing = contract.requiresHumanAcceptance && !observation.humanAccepted
        return WorkflowCompletionResult(
            complete = missingChecks.isEmpty() && missingArtifacts.isEmpty() &&
                missingEvidence.isEmpty() && !acceptanceMissing,
            missingChecks = missingChecks,
            missingArtifacts = missingArtifacts,
            missingEvidence = missingEvidence,
            humanAcceptanceMissing = acceptanceMissing,
        )
    }
}

object WorkflowPolicyEvaluator {
    fun evaluate(
        request: WorkflowPolicyRequest,
        lease: WorkflowPermissionLease?,
        budget: WorkflowBudget,
        usage: WorkflowBudgetUsage,
        nowMillis: Long,
    ): WorkflowPolicyDecision {
        require(nowMillis >= 0) { "Policy evaluation timestamp is invalid" }
        if (request.risk == WorkflowRisk.R3 || request.reversibility == WorkflowReversibility.UNKNOWN) {
            return WorkflowPolicyDecision.DENY
        }
        val nextUsage = try {
            usage.plus(request.estimatedUsage)
        } catch (_: ArithmeticException) {
            return WorkflowPolicyDecision.BUDGET_EXCEEDED
        }
        if (!nextUsage.within(budget)) return WorkflowPolicyDecision.BUDGET_EXCEEDED
        val matchingLease = lease?.let { it.workflowId == request.workflowId && it.runId == request.runId } == true
        val activeLease = lease?.isActive(nowMillis) == true
        val capabilityAllowed = lease?.permits(request.capability, request.effect) == true
        if (!matchingLease || !activeLease || !capabilityAllowed) {
            return WorkflowPolicyDecision.DENY
        }
        if (request.effect == WorkflowEffectClass.DESTRUCTIVE && !request.humanApproved) {
            return WorkflowPolicyDecision.REQUIRE_APPROVAL
        }
        return WorkflowPolicyDecision.ALLOW
    }
}

private fun requirePolicyId(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_POLICY_ID_CHARS) { "$label is invalid" }
}

private const val MAX_POLICY_ID_CHARS = 512
private const val MAX_POLICY_ITEMS = 128
private const val MAX_POLICY_USES = 10_000
