package dev.agentrelay.session.api

/** A bounded, auditable request for creating a workflow task from an outcome. */
data class WorkflowTaskCreationRequest(
    val desiredOutcome: String,
    val triggers: Set<String> = emptySet(),
    val constraints: Set<String> = emptySet(),
    val prohibitedEffects: Set<String> = emptySet(),
    val completion: WorkflowCompletionContract,
    val budget: WorkflowBudget,
    val risk: WorkflowRisk,
) {
    init {
        requireToken(desiredOutcome, "Desired outcome")
        requireSet(triggers, "Trigger")
        requireSet(constraints, "Constraint")
        requireSet(prohibitedEffects, "Prohibited effect")
        require(constraints.intersect(prohibitedEffects).isEmpty()) {
            "A condition cannot be both a constraint and a prohibited effect"
        }
    }

    fun materialize(
        id: WorkflowId,
        workflowId: WorkflowId,
        projectId: String,
        baseRevision: String,
    ): WorkflowTaskProposal = WorkflowTaskProposal(
        task = WorkflowTask(
            id = id,
            workflowId = workflowId,
            projectId = projectId,
            baseRevision = baseRevision,
            risk = risk,
            budget = budget,
            completion = completion,
        ),
        desiredOutcome = desiredOutcome,
        triggers = triggers,
        constraints = constraints,
        prohibitedEffects = prohibitedEffects,
    )
}

/** The immutable proposal retained alongside the proposed task for review. */
data class WorkflowTaskProposal(
    val task: WorkflowTask,
    val desiredOutcome: String,
    val triggers: Set<String>,
    val constraints: Set<String>,
    val prohibitedEffects: Set<String>,
) {
    init {
        requireToken(desiredOutcome, "Desired outcome")
        requireSet(triggers, "Trigger")
        requireSet(constraints, "Constraint")
        requireSet(prohibitedEffects, "Prohibited effect")
        require(constraints.intersect(prohibitedEffects).isEmpty()) {
            "A condition cannot be both a constraint and a prohibited effect"
        }
        require(task.state == WorkflowTaskState.PROPOSED) {
            "Task proposals must remain proposed until explicitly approved"
        }
    }
}

private const val MAX_CREATION_ITEMS = 128
private const val MAX_TOKEN_CHARS = 512

private fun requireToken(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_TOKEN_CHARS) { "$label is invalid" }
}

private fun requireSet(values: Set<String>, label: String) {
    require(values.size <= MAX_CREATION_ITEMS) { "$label set is too large" }
    values.forEach { requireToken(it, label) }
}
