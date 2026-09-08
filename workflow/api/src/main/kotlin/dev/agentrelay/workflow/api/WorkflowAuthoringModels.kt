/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.workflow.api

enum class WorkflowPermission { READ_CONTEXT, NOTIFY_USER, REQUEST_APPROVAL, RUN_SAFE_COMMAND }

data class WorkflowCompletionContract(
    val requiredSignals: Set<String>,
    val failureSignals: Set<String> = emptySet(),
) {
    init {
        require(requiredSignals.isNotEmpty()) { "A completion contract needs a success signal" }
        require((requiredSignals + failureSignals).all { it.matches(SIGNAL_PATTERN) }) {
            "Completion signals must be stable identifiers"
        }
        require(requiredSignals.intersect(failureSignals).isEmpty()) {
            "A signal cannot mean both success and failure"
        }
    }
}

data class AuthoredWorkflowStep(
    val id: String,
    val action: WorkflowAction,
    val dependsOn: Set<String> = emptySet(),
    val permission: WorkflowPermission,
) {
    init {
        require(id.matches(STEP_PATTERN)) { "Step id is invalid" }
        require(dependsOn.none { it == id }) { "A step cannot depend on itself" }
        require(dependsOn.all { it.matches(STEP_PATTERN) }) { "Dependency id is invalid" }
        require(action.permission() == permission) { "Step permission does not match its effect" }
    }
}

data class WorkflowAuthoringDraft(
    val workflow: WorkflowDefinition,
    val steps: List<AuthoredWorkflowStep>,
    val permissions: Set<WorkflowPermission>,
    val completion: WorkflowCompletionContract,
    val preview: String,
    val reviewed: Boolean = false,
) {
    init {
        require(preview.isNotBlank() && preview.length <= MAX_PREVIEW_CHARS) { "Preview is invalid" }
        require(steps.isNotEmpty() && steps.size <= MAX_STEPS) { "Authored steps are invalid" }
        val ids = steps.map(AuthoredWorkflowStep::id).toSet()
        require(ids.size == steps.size) { "Authored step ids must be unique" }
        require(steps.all { it.dependsOn.all(ids::contains) }) { "Dependency must reference a known step" }
        require(steps.all { it.permission in permissions }) { "Every effect needs an explicit permission" }
        require(isAcyclic(steps)) { "Workflow dependencies must be acyclic" }
        require(steps.maxOf { step -> steps.count { step.id in it.dependsOn } } <= MAX_FAN_OUT) {
            "Workflow fan-out is too broad"
        }
    }

    fun approve(): WorkflowAuthoringDraft = copy(reviewed = true)

    fun executionPlan(): WorkflowExecutionPlan {
        require(reviewed) { "Workflow must be reviewed before execution" }
        return WorkflowExecutionPlan(workflow, steps, completion)
    }

    private fun isAcyclic(nodes: List<AuthoredWorkflowStep>): Boolean {
        val byId = nodes.associateBy(AuthoredWorkflowStep::id)
        fun visit(id: String, visiting: Set<String>, visited: Set<String>): Boolean {
            if (id in visiting) return false
            if (id in visited) return true
            val next = byId.getValue(id).dependsOn
            return next.all { visit(it, visiting + id, visited + id) }
        }
        return nodes.all { visit(it.id, emptySet(), emptySet()) }
    }
}

data class WorkflowExecutionPlan(
    val workflow: WorkflowDefinition,
    val steps: List<AuthoredWorkflowStep>,
    val completion: WorkflowCompletionContract,
)

enum class WorkflowAuthoringStatus { READY, AMBIGUOUS, REJECTED }

data class WorkflowAuthoringResult(
    val status: WorkflowAuthoringStatus,
    val draft: WorkflowAuthoringDraft? = null,
    val questions: List<String> = emptyList(),
)

object WorkflowAuthoringParser {
    fun parse(goal: String): WorkflowAuthoringResult {
        val normalized = goal.trim().lowercase()
        if (normalized.isBlank() || normalized.length > MAX_GOAL_CHARS) {
            return WorkflowAuthoringResult(WorkflowAuthoringStatus.REJECTED)
        }
        val wantsApproval = "approval" in normalized || "approve" in normalized
        val wantsNotification = "notify" in normalized || "notification" in normalized
        if (wantsApproval == wantsNotification) {
            return WorkflowAuthoringResult(
                WorkflowAuthoringStatus.AMBIGUOUS,
                questions = listOf("Should this workflow notify you or request an approval?"),
            )
        }
        val action = if (wantsApproval) WorkflowAction.REQUEST_APPROVAL else WorkflowAction.NOTIFY
        val permission = action.permission()
        val step = AuthoredWorkflowStep("step-1", action, permission = permission)
        val definition = WorkflowDefinition(
            WorkflowId("wf_v1_authored"),
            goal.trim().take(MAX_WORKFLOW_NAME_CHARS),
            WorkflowTrigger.MANUAL,
            setOf("user.goal"),
            WorkflowBudget(1, 300, 86_400),
            listOf(WorkflowStep(step.id, action, WorkflowRisk.REQUIRES_APPROVAL.takeIf { wantsApproval } ?: WorkflowRisk.SAFE)),
            approvalRequired = wantsApproval,
        )
        return WorkflowAuthoringResult(
            WorkflowAuthoringStatus.READY,
            WorkflowAuthoringDraft(
                definition,
                listOf(step),
                setOf(permission),
                WorkflowCompletionContract(setOf("step.completed"), setOf("step.failed")),
                preview = "Review: ${goal.trim()}",
            ),
        )
    }
}

private fun WorkflowAction.permission(): WorkflowPermission = when (this) {
    WorkflowAction.NOTIFY -> WorkflowPermission.NOTIFY_USER
    WorkflowAction.PROPOSE -> WorkflowPermission.READ_CONTEXT
    WorkflowAction.REQUEST_APPROVAL -> WorkflowPermission.REQUEST_APPROVAL
    WorkflowAction.RUN_SAFE_COMMAND -> WorkflowPermission.RUN_SAFE_COMMAND
}

private val STEP_PATTERN = Regex("[a-z][a-z0-9-]{0,31}")
private val SIGNAL_PATTERN = Regex("[a-z][a-z0-9_.-]{0,63}")
private const val MAX_STEPS = 64
private const val MAX_FAN_OUT = 16
private const val MAX_PREVIEW_CHARS = 1_000
private const val MAX_GOAL_CHARS = 500
private const val MAX_WORKFLOW_NAME_CHARS = 80
