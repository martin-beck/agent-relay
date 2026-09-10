/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.api

@JvmInline
value class WorkflowId(val value: String) {
    init {
        requireId(value, "Workflow id")
    }
}

@JvmInline
value class WorkflowRunId(val value: String) {
    init {
        requireId(value, "Workflow run id")
    }
}

@JvmInline
value class WorkflowStepId(val value: String) {
    init {
        requireId(value, "Workflow step id")
    }
}

enum class WorkflowTaskState { PROPOSED, APPROVED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }
enum class WorkflowRunState { QUEUED, RUNNING, PAUSED, SUCCEEDED, FAILED, UNCERTAIN, CANCELLED }
enum class WorkflowStepState { PENDING, RUNNING, SUCCEEDED, FAILED, RETRYING, UNCERTAIN, CANCELLED }
enum class WorkflowRisk { R0, R1, R2, R3 }
enum class WorkflowEffectState { NONE, IDEMPOTENT, UNCERTAIN }

data class WorkflowBudget(
    val maxCpuMillis: Long,
    val maxMemoryBytes: Long,
    val maxWallClockMillis: Long,
    val maxSteps: Int,
) {
    init {
        require(maxCpuMillis > 0 && maxMemoryBytes > 0 && maxWallClockMillis > 0) { "Workflow budgets must be positive" }
        require(maxSteps in 1..MAX_STEPS) { "Workflow step budget is invalid" }
    }
}

data class WorkflowCompletionContract(
    val requiredChecks: Set<String>,
    val requiredArtifacts: Set<String>,
    val requiresHumanAcceptance: Boolean,
) {
    init {
        require(requiredChecks.size <= MAX_CONTRACT_ITEMS && requiredArtifacts.size <= MAX_CONTRACT_ITEMS)
        (requiredChecks + requiredArtifacts).forEach { requireId(it, "Completion contract item") }
    }
}

data class WorkflowTask(
    val id: WorkflowId,
    val workflowId: WorkflowId,
    val projectId: String,
    val baseRevision: String,
    val risk: WorkflowRisk,
    val budget: WorkflowBudget,
    val completion: WorkflowCompletionContract,
    val state: WorkflowTaskState = WorkflowTaskState.PROPOSED,
    val revision: Long = 0,
) {
    init {
        requireId(projectId, "Project id")
        requireId(baseRevision, "Base revision")
        require(revision >= 0) { "Task revision must not be negative" }
        require(risk != WorkflowRisk.R3 || state == WorkflowTaskState.PROPOSED) {
            "R3 tasks are investigation-only"
        }
    }
}

data class WorkflowRun(
    val id: WorkflowRunId,
    val taskId: WorkflowId,
    val state: WorkflowRunState = WorkflowRunState.QUEUED,
    val effect: WorkflowEffectState = WorkflowEffectState.NONE,
    val stepCount: Int = 0,
) {
    init {
        require(stepCount in 0..MAX_STEPS)
        require(effect != WorkflowEffectState.UNCERTAIN || state == WorkflowRunState.UNCERTAIN) {
            "Uncertain effects require uncertain run state"
        }
    }
}

data class WorkflowStep(
    val id: WorkflowStepId,
    val runId: WorkflowRunId,
    val ordinal: Int,
    val state: WorkflowStepState = WorkflowStepState.PENDING,
    val attempt: Int = 0,
) {
    init {
        require(ordinal >= 0)
        require(attempt in 0..MAX_ATTEMPTS)
        require(state != WorkflowStepState.RETRYING || attempt > 0) {
            "Retrying steps require a prior attempt"
        }
    }
}

object WorkflowTransitions {
    fun task(from: WorkflowTaskState, to: WorkflowTaskState): Boolean = when (from) {
        WorkflowTaskState.PROPOSED -> to == WorkflowTaskState.APPROVED || to == WorkflowTaskState.CANCELLED
        WorkflowTaskState.APPROVED -> to == WorkflowTaskState.RUNNING || to == WorkflowTaskState.CANCELLED
        WorkflowTaskState.RUNNING -> to in setOf(
            WorkflowTaskState.PAUSED,
            WorkflowTaskState.COMPLETED,
            WorkflowTaskState.FAILED,
            WorkflowTaskState.CANCELLED,
        )
        WorkflowTaskState.PAUSED -> to == WorkflowTaskState.RUNNING || to == WorkflowTaskState.CANCELLED
        WorkflowTaskState.COMPLETED, WorkflowTaskState.FAILED, WorkflowTaskState.CANCELLED -> false
    }

    fun run(from: WorkflowRunState, to: WorkflowRunState): Boolean = when (from) {
        WorkflowRunState.QUEUED -> to == WorkflowRunState.RUNNING || to == WorkflowRunState.CANCELLED
        WorkflowRunState.RUNNING -> to in setOf(
            WorkflowRunState.PAUSED,
            WorkflowRunState.SUCCEEDED,
            WorkflowRunState.FAILED,
            WorkflowRunState.UNCERTAIN,
            WorkflowRunState.CANCELLED,
        )
        WorkflowRunState.PAUSED -> to == WorkflowRunState.RUNNING || to == WorkflowRunState.CANCELLED
        WorkflowRunState.SUCCEEDED, WorkflowRunState.FAILED, WorkflowRunState.UNCERTAIN, WorkflowRunState.CANCELLED -> false
    }
}

private fun requireId(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_ID_CHARS) { "$label is invalid" }
}

private const val MAX_ID_CHARS = 512
private const val MAX_STEPS = 10_000
private const val MAX_ATTEMPTS = 32
private const val MAX_CONTRACT_ITEMS = 128
