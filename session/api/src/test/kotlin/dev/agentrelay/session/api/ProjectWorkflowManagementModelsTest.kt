package dev.agentrelay.session.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProjectWorkflowManagementModelsTest {
    @Test
    fun snapshotValidatesReferencesAndComputesProjectHealth() {
        val project = ProjectRecord(ProjectId("project"), "Relay", "/workspace", 1L, 2L)
        val workflow = WorkflowTask(
            id = WorkflowId("workflow"),
            workflowId = WorkflowId("workflow"),
            projectId = project.id.value,
            baseRevision = "revision",
            risk = WorkflowRisk.R1,
            budget = WorkflowBudget(1L, 1L, 1L, 1),
            completion = WorkflowCompletionContract(emptySet(), emptySet(), false),
            state = WorkflowTaskState.RUNNING,
        )
        val snapshot = ProjectWorkflowSnapshot(projects = listOf(project), workflows = listOf(workflow))

        assertEquals(ProjectWorkflowHealth.RUNNING, snapshot.projectHealth(project.id))
        assertFailsWith<IllegalArgumentException> {
            ProjectWorkflowSnapshot(projects = listOf(project), workflows = listOf(workflow.copy(projectId = "other")))
        }
    }

    @Test
    fun notificationPolicyHandlesWrappingQuietHoursAndKinds() {
        val policy = ProjectNotificationPolicy(
            enabledKinds = setOf(NormalizedEventKind.ATTENTION),
            quietHoursStartMinute = 1_320,
            quietHoursEndMinute = 420,
        )

        assertTrue(policy.allows(NormalizedEventKind.ATTENTION, 600))
        assertFalse(policy.allows(NormalizedEventKind.ATTENTION, 1_400))
        assertFalse(policy.allows(NormalizedEventKind.WORKFLOW, 600))
    }

    @Test
    fun historyAndEvidenceAreProjectedToOneNotificationPerEvent() {
        val project = ProjectRecord(ProjectId("project"), "Relay", "/workspace", 1L, 2L)
        val workflow = WorkflowTask(
            WorkflowId("workflow"),
            WorkflowId("workflow"),
            project.id.value,
            "revision",
            WorkflowRisk.R1,
            WorkflowBudget(1L, 1L, 1L, 1),
            WorkflowCompletionContract(emptySet(), emptySet(), false),
        )
        val event = NormalizedEvent(
            id = "event",
            schemaVersion = 1,
            streamId = project.id.value,
            sequence = 1L,
            kind = NormalizedEventKind.ATTENTION,
            sensitivity = EventSensitivity.INTERNAL,
            causation = EventCausation("correlation", actor = "system"),
            payload = mapOf("workflowId" to workflow.id.value),
        )
        val history = WorkflowHistoryRecord("history", project.id, workflow.id, event, 3L)
        val evidence = WorkflowEvidenceRecord(EvidenceId("evidence"), project.id, workflow.id, null, "check", "sha256", 3L)
        val snapshot = ProjectWorkflowSnapshot(listOf(project), listOf(workflow), history = listOf(history), evidence = listOf(evidence))

        assertEquals(listOf(history), snapshot.historyFor(project.id))
        assertEquals(listOf(evidence), snapshot.evidenceFor(project.id, workflow.id))
        assertEquals(listOf(event.id), snapshot.notificationsFor(project.id, 600).map { it.eventId })
    }
}
