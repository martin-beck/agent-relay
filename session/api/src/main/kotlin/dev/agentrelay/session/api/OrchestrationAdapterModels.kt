/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.api

/** The adapter may describe delegation, but never becomes workflow authority. */
enum class OrchestrationMode { PARALLEL, PIPELINE }

enum class DelegatedRole { IMPLEMENTER, REVIEWER, TESTER, INVESTIGATOR }

data class ToolModelSelection(
    val modelId: String? = null,
    val toolIds: List<String> = emptyList(),
) {
    init {
        modelId?.let { requireStableOrchestrationValue(it, "Model id") }
        require(toolIds.size <= MAX_ORCHESTRATION_TOOLS) { "Too many orchestration tools" }
        require(toolIds.distinct().size == toolIds.size) { "Orchestration tools must be unique" }
        toolIds.forEach { requireStableOrchestrationValue(it, "Tool id") }
    }
}

data class OrchestrationAdapterPolicy(
    val enabled: Boolean = false,
    val allowedModes: Set<OrchestrationMode> = emptySet(),
    val maxDelegates: Int = 1,
    val maxParallelism: Int = 1,
    val allowedModels: Set<String> = emptySet(),
    val allowedTools: Set<String> = emptySet(),
) {
    init {
        require(maxDelegates in 1..MAX_ORCHESTRATION_DELEGATES) {
            "Delegated attempt bound is invalid"
        }
        require(maxParallelism in 1..maxDelegates) { "Parallelism bound is invalid" }
        require(allowedModels.all { it.matches(ORCHESTRATION_VALUE_PATTERN) }) {
            "Allowed model id is invalid"
        }
        require(allowedTools.all { it.matches(ORCHESTRATION_VALUE_PATTERN) }) {
            "Allowed tool id is invalid"
        }
        if (enabled) require(allowedModes.isNotEmpty()) { "Enabled policy needs an allowed mode" }
    }
}

data class DelegatedAttempt(
    val attemptId: String,
    val role: DelegatedRole,
    val operation: String,
    val inputDigest: String,
    val selection: ToolModelSelection = ToolModelSelection(),
) {
    init {
        requireStableOrchestrationValue(attemptId, "Delegated attempt id")
        requireStableOrchestrationValue(operation, "Delegated operation")
        require(inputDigest.matches(ORCHESTRATION_DIGEST_PATTERN)) {
            "Delegated input digest is invalid"
        }
    }
}

/** Input is limited to already delegated attempts; task and permission state stay outside. */
data class OrchestrationAdapterInput(
    val delegationId: String,
    val mode: OrchestrationMode,
    val attempts: List<DelegatedAttempt>,
    val selection: ToolModelSelection,
    val policy: OrchestrationAdapterPolicy,
) {
    init {
        requireStableOrchestrationValue(delegationId, "Delegation id")
        require(attempts.isNotEmpty()) { "At least one delegated attempt is required" }
        require(attempts.size <= policy.maxDelegates) { "Delegated attempt bound exceeded" }
        require(attempts.map { it.attemptId }.distinct().size == attempts.size) {
            "Delegated attempt ids must be unique"
        }
        require(policy.enabled) { "Orchestration adapters are disabled by default" }
        require(mode in policy.allowedModes) { "Orchestration mode is not enabled" }
    }
}

data class OrchestrationAdapterPlan(
    val delegationId: String,
    val mode: OrchestrationMode,
    val orderedAttemptIds: List<String>,
    val maxParallelism: Int,
    val selection: ToolModelSelection,
) {
    init {
        requireStableOrchestrationValue(delegationId, "Delegation id")
        require(orderedAttemptIds.isNotEmpty()) { "An orchestration plan needs attempts" }
        require(orderedAttemptIds.distinct().size == orderedAttemptIds.size) {
            "Orchestration plan attempt ids must be unique"
        }
        require(maxParallelism in 1..orderedAttemptIds.size) { "Plan parallelism is invalid" }
    }
}

fun interface OptionalOrchestrationAdapter {
    /** Plans delegated work only; execution, scheduling, and completion remain external. */
    fun plan(input: OrchestrationAdapterInput): OrchestrationAdapterPlan
}

/** Deterministic policy adapter for explicitly enabled parallel or pipeline delegation. */
class PolicyOrchestrationAdapter : OptionalOrchestrationAdapter {
    override fun plan(input: OrchestrationAdapterInput): OrchestrationAdapterPlan {
        validateSelection(input.selection, input.policy)
        input.attempts.forEach { validateSelection(it.selection, input.policy) }
        val parallelism = when (input.mode) {
            OrchestrationMode.PARALLEL -> minOf(input.policy.maxParallelism, input.attempts.size)
            OrchestrationMode.PIPELINE -> 1
        }
        return OrchestrationAdapterPlan(
            delegationId = input.delegationId,
            mode = input.mode,
            orderedAttemptIds = input.attempts.map { it.attemptId },
            maxParallelism = parallelism,
            selection = input.selection,
        )
    }

    private fun validateSelection(selection: ToolModelSelection, policy: OrchestrationAdapterPolicy) {
        selection.modelId?.let { require(it in policy.allowedModels) { "Model is not allowed" } }
        require(selection.toolIds.all { it in policy.allowedTools }) { "Tool is not allowed" }
    }
}

private const val MAX_ORCHESTRATION_DELEGATES = 16
private const val MAX_ORCHESTRATION_TOOLS = 16
private val ORCHESTRATION_DIGEST_PATTERN = Regex("[a-fA-F0-9]{64}")
private val ORCHESTRATION_VALUE_PATTERN = Regex("[A-Za-z0-9._:/-]{1,128}")

private fun requireStableOrchestrationValue(value: String, field: String) {
    require(value.matches(ORCHESTRATION_VALUE_PATTERN)) { "$field is invalid" }
}
