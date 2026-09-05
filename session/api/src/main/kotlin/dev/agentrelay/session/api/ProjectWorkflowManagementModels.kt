package dev.agentrelay.session.api

@JvmInline
value class ProjectId(val value: String) {
    init {
        requireManagementId(value, "Project id")
    }
}

@JvmInline
value class EvidenceId(val value: String) {
    init {
        requireManagementId(value, "Evidence id")
    }
}

enum class ProjectWorkflowHealth {
    IDLE,
    RUNNING,
    NEEDS_ATTENTION,
    FAILED,
    COMPLETED,
}

data class ProjectRecord(
    val id: ProjectId,
    val name: String,
    val root: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val notificationPolicy: ProjectNotificationPolicy = ProjectNotificationPolicy(),
) {
    init {
        requireManagementText(name, "Project name", MAX_PROJECT_NAME_CHARS)
        requireManagementText(root, "Project root", MAX_PROJECT_ROOT_CHARS)
        require(createdAtEpochMillis >= 0L)
        require(updatedAtEpochMillis >= createdAtEpochMillis)
    }
}

data class ProjectNotificationPolicy(
    val enabledKinds: Set<NormalizedEventKind> = setOf(
        NormalizedEventKind.PERMISSION,
        NormalizedEventKind.ATTENTION,
        NormalizedEventKind.BUDGET,
    ),
    val quietHoursStartMinute: Int? = null,
    val quietHoursEndMinute: Int? = null,
) {
    init {
        require(enabledKinds.size <= MAX_NOTIFICATION_KINDS)
        val hours = quietHoursStartMinute to quietHoursEndMinute
        require(hours.first == null == (hours.second == null)) {
            "Quiet hours require both a start and end"
        }
        quietHoursStartMinute?.let { require(it in MINUTES_PER_DAY) }
        quietHoursEndMinute?.let { require(it in MINUTES_PER_DAY) }
    }

    fun allows(kind: NormalizedEventKind, minuteOfDay: Int): Boolean {
        require(minuteOfDay in MINUTES_PER_DAY)
        if (kind !in enabledKinds) return false
        val start = quietHoursStartMinute ?: return true
        val end = checkNotNull(quietHoursEndMinute)
        val quiet = if (start < end) minuteOfDay in start until end else minuteOfDay >= start || minuteOfDay < end
        return !quiet
    }
}

data class WorkflowHistoryRecord(
    val id: String,
    val projectId: ProjectId,
    val workflowId: WorkflowId,
    val event: NormalizedEvent,
    val recordedAtEpochMillis: Long,
) {
    init {
        requireManagementId(id, "History id")
        require(event.streamId == projectId.value) { "History stream must use the project id" }
        require(event.payload[WORKFLOW_ID_FIELD] == workflowId.value) {
            "History event must identify its workflow"
        }
        require(recordedAtEpochMillis >= 0L)
    }
}

data class WorkflowEvidenceRecord(
    val id: EvidenceId,
    val projectId: ProjectId,
    val workflowId: WorkflowId,
    val runId: WorkflowRunId?,
    val kind: String,
    val digest: String,
    val createdAtEpochMillis: Long,
    val retained: Boolean = true,
) {
    init {
        requireManagementText(kind, "Evidence kind", MAX_EVIDENCE_KIND_CHARS)
        requireManagementText(digest, "Evidence digest", MAX_DIGEST_CHARS)
        require(createdAtEpochMillis >= 0L)
    }
}

data class ProjectWorkflowNotification(
    val projectId: ProjectId,
    val workflowId: WorkflowId,
    val eventId: String,
    val kind: NormalizedEventKind,
    val evidenceIds: List<EvidenceId>,
) {
    init {
        requireManagementId(eventId, "Notification event id")
        require(evidenceIds.distinct().size == evidenceIds.size) {
            "Notification evidence must not contain duplicates"
        }
    }
}

data class ProjectWorkflowSnapshot(
    val projects: List<ProjectRecord> = emptyList(),
    val workflows: List<WorkflowTask> = emptyList(),
    val runs: List<WorkflowRun> = emptyList(),
    val history: List<WorkflowHistoryRecord> = emptyList(),
    val evidence: List<WorkflowEvidenceRecord> = emptyList(),
) {
    init {
        val projectIds = projects.mapTo(mutableSetOf(), ProjectRecord::id)
        require(projectIds.size == projects.size) { "Duplicate project identities" }
        val workflowIds = workflows.mapTo(mutableSetOf(), WorkflowTask::id)
        require(workflowIds.size == workflows.size) { "Duplicate workflow identities" }
        val runIds = runs.mapTo(mutableSetOf(), WorkflowRun::id)
        require(runIds.size == runs.size) { "Duplicate run identities" }
        require(workflows.all { workflow -> ProjectId(workflow.projectId) in projectIds }) {
            "Workflow references an unknown project"
        }
        require(runs.all { run -> run.taskId in workflowIds }) { "Run references an unknown workflow" }
        require(history.all { it.projectId in projectIds && it.workflowId in workflowIds }) {
            "History references an unknown project or workflow"
        }
        require(evidence.all { it.projectId in projectIds && it.workflowId in workflowIds }) {
            "Evidence references an unknown project or workflow"
        }
        require(
            history.zipWithNext().all { (first, second) ->
                first.recordedAtEpochMillis <= second.recordedAtEpochMillis
            },
        ) { "History must be ordered by recording time" }
    }

    fun projectHealth(projectId: ProjectId): ProjectWorkflowHealth {
        require(projectId in projects.map(ProjectRecord::id))
        val projectWorkflows = workflows.filter { it.projectId == projectId.value }
        return when {
            projectWorkflows.any { it.state == WorkflowTaskState.RUNNING } -> ProjectWorkflowHealth.RUNNING
            projectWorkflows.any { it.state == WorkflowTaskState.FAILED } -> ProjectWorkflowHealth.FAILED
            projectWorkflows.any { it.state == WorkflowTaskState.APPROVED || it.state == WorkflowTaskState.PAUSED } ->
                ProjectWorkflowHealth.NEEDS_ATTENTION
            projectWorkflows.any { it.state == WorkflowTaskState.COMPLETED } -> ProjectWorkflowHealth.COMPLETED
            else -> ProjectWorkflowHealth.IDLE
        }
    }

    fun historyFor(projectId: ProjectId, workflowId: WorkflowId? = null): List<WorkflowHistoryRecord> =
        history.filter { it.projectId == projectId && (workflowId == null || it.workflowId == workflowId) }

    fun evidenceFor(projectId: ProjectId, workflowId: WorkflowId): List<WorkflowEvidenceRecord> =
        evidence.filter { it.projectId == projectId && it.workflowId == workflowId && it.retained }

    fun notificationsFor(
        projectId: ProjectId,
        minuteOfDay: Int,
    ): List<ProjectWorkflowNotification> {
        val project = projects.first { it.id == projectId }
        return history.asSequence()
            .filter { it.projectId == projectId && project.notificationPolicy.allows(it.event.kind, minuteOfDay) }
            .map { record ->
                ProjectWorkflowNotification(
                    projectId = projectId,
                    workflowId = record.workflowId,
                    eventId = record.event.id,
                    kind = record.event.kind,
                    evidenceIds = evidenceFor(projectId, record.workflowId).map(WorkflowEvidenceRecord::id),
                )
            }
            .distinctBy(ProjectWorkflowNotification::eventId)
            .toList()
    }
}

private fun requireManagementId(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_MANAGEMENT_ID_CHARS) { "$label is invalid" }
}

private fun requireManagementText(value: String, label: String, maximum: Int) {
    require(value.isNotBlank() && value.length <= maximum) { "$label is invalid" }
}

private const val MAX_MANAGEMENT_ID_CHARS = 512
private const val MAX_PROJECT_NAME_CHARS = 256
private const val MAX_PROJECT_ROOT_CHARS = 4_096
private const val MAX_EVIDENCE_KIND_CHARS = 128
private const val MAX_DIGEST_CHARS = 256
private const val MAX_NOTIFICATION_KINDS = 16
private const val WORKFLOW_ID_FIELD = "workflowId"
private val MINUTES_PER_DAY = 0 until 1_440
