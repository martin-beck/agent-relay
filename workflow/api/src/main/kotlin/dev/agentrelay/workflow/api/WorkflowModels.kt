package dev.agentrelay.workflow.api

@JvmInline
value class WorkflowId(val value: String) {
    init { require(value.matches(Regex("wf_v1_[a-z0-9-]{1,48}"))) { "Workflow id is invalid" } }
}

enum class WorkflowTrigger { SCHEDULE, LOCATION, DEVICE_EVENT, POLL, MANUAL }
enum class WorkflowAction { NOTIFY, PROPOSE, REQUEST_APPROVAL, RUN_SAFE_COMMAND }
enum class WorkflowRisk { SAFE, REQUIRES_APPROVAL, HIGH_RISK }

data class WorkflowBudget(
    val maxActivations: Int,
    val maxRuntimeSeconds: Long,
    val retentionSeconds: Long,
) {
    init {
        require(maxActivations in 1..10_000) { "Activation budget is invalid" }
        require(maxRuntimeSeconds in 1..MAX_RUNTIME_SECONDS) { "Runtime budget is invalid" }
        require(retentionSeconds in 1..MAX_RETENTION_SECONDS) { "Retention budget is invalid" }
    }
}

data class WorkflowStep(
    val id: String,
    val action: WorkflowAction,
    val risk: WorkflowRisk,
    val requiredFields: Set<String> = emptySet(),
    val retryLimit: Int = 0,
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9-]{0,31}"))) { "Step id is invalid" }
        require(requiredFields.size <= MAX_FIELDS) { "Too many required fields" }
        requiredFields.forEach { require(it.matches(Regex("[a-z][a-z0-9_.-]{0,63}"))) { "Field is invalid" } }
        require(retryLimit in 0..MAX_RETRIES) { "Retry limit is invalid" }
        require(action != WorkflowAction.RUN_SAFE_COMMAND || risk == WorkflowRisk.SAFE) {
            "Safe command must declare safe risk"
        }
        require(risk != WorkflowRisk.HIGH_RISK) { "High-risk effects are not declarable" }
    }
}

data class WorkflowDefinition(
    val id: WorkflowId,
    val name: String,
    val trigger: WorkflowTrigger,
    val declaredSources: Set<String>,
    val budget: WorkflowBudget,
    val steps: List<WorkflowStep>,
    val approvalRequired: Boolean = false,
) {
    init {
        require(name.isNotBlank() && name.length <= MAX_NAME_CHARS) { "Workflow name is invalid" }
        require(declaredSources.isNotEmpty() && declaredSources.size <= MAX_SOURCES) {
            "Workflow must declare bounded data sources"
        }
        declaredSources.forEach { require(it.matches(Regex("[a-z][a-z0-9_.-]{0,63}"))) { "Source is invalid" } }
        require(steps.isNotEmpty() && steps.size <= MAX_STEPS) { "Workflow steps are invalid" }
        require(steps.map(WorkflowStep::id).toSet().size == steps.size) { "Workflow step ids must be unique" }
        if (approvalRequired) require(steps.any { it.action == WorkflowAction.REQUEST_APPROVAL }) {
            "Approval requirement must have an approval step"
        }
    }
}

private const val MAX_NAME_CHARS = 80
private const val MAX_SOURCES = 16
private const val MAX_STEPS = 64
private const val MAX_FIELDS = 32
private const val MAX_RETRIES = 5
private const val MAX_RUNTIME_SECONDS = 86_400L
private const val MAX_RETENTION_SECONDS = 31_536_000L
