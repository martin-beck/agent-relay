package dev.agentrelay.workflow.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class WorkflowAuthoringModelsTest {
    @Test
    fun parserRequiresChoiceForAmbiguousGoal() {
        val result = WorkflowAuthoringParser.parse("Handle this task")
        assertEquals(WorkflowAuthoringStatus.AMBIGUOUS, result.status)
        assertNotNull(result.questions.single())
    }

    @Test
    fun parserRejectsMalformedGoal() {
        assertEquals(WorkflowAuthoringStatus.REJECTED, WorkflowAuthoringParser.parse(" ").status)
        assertEquals(WorkflowAuthoringStatus.REJECTED, WorkflowAuthoringParser.parse("x".repeat(501)).status)
    }

    @Test
    fun parserProducesUnapprovedReviewableDraft() {
        val result = WorkflowAuthoringParser.parse("Notify me when the session completes")
        val draft = assertNotNull(result.draft)
        assertEquals(WorkflowAuthoringStatus.READY, result.status)
        assertFailsWith<IllegalArgumentException> { draft.executionPlan() }
        assertEquals("step.completed", draft.approve().executionPlan().completion.requiredSignals.single())
    }

    @Test
    fun rejectsCyclesAndUnknownDependencies() {
        val a = AuthoredWorkflowStep("a", WorkflowAction.NOTIFY, permission = WorkflowPermission.NOTIFY_USER)
        val b = AuthoredWorkflowStep("b", WorkflowAction.NOTIFY, setOf("a"), WorkflowPermission.NOTIFY_USER)
        val base = WorkflowDefinition(
            WorkflowId("wf_v1_graph"),
            "Graph",
            WorkflowTrigger.MANUAL,
            setOf("user.goal"),
            WorkflowBudget(1, 10, 100),
            listOf(WorkflowStep("a", WorkflowAction.NOTIFY, WorkflowRisk.SAFE)),
        )
        WorkflowAuthoringDraft(
            base,
            listOf(a, b),
            setOf(WorkflowPermission.NOTIFY_USER),
            WorkflowCompletionContract(setOf("done")),
            "Preview",
        )
        assertFailsWith<IllegalArgumentException> {
            WorkflowAuthoringDraft(
                base,
                listOf(a.copy(dependsOn = setOf("missing"))),
                setOf(WorkflowPermission.NOTIFY_USER),
                WorkflowCompletionContract(setOf("done")),
                "Preview",
            )
        }
    }
}
