package dev.agentrelay.workflow.api

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkflowModelsTest {
    private val safeStep = WorkflowStep("notify", WorkflowAction.NOTIFY, WorkflowRisk.SAFE)

    @Test
    fun acceptsBoundedDeclaredWorkflow() {
        val workflow = WorkflowDefinition(
            WorkflowId("wf_v1_daily-summary"), "Daily summary", WorkflowTrigger.SCHEDULE,
            setOf("session.summary"), WorkflowBudget(10, 600, 86_400), listOf(safeStep),
        )
        assertTrue(workflow.steps.single().action == WorkflowAction.NOTIFY)
    }

    @Test
    fun rejectsUndeclaredHighRiskAndMalformedInput() {
        assertFailsWith<IllegalArgumentException> { WorkflowId("../../secret") }
        assertFailsWith<IllegalArgumentException> {
            WorkflowDefinition(WorkflowId("wf_v1_bad"), "x", WorkflowTrigger.MANUAL, emptySet(),
                WorkflowBudget(1, 1, 1), listOf(safeStep))
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowStep("run", WorkflowAction.RUN_SAFE_COMMAND, WorkflowRisk.HIGH_RISK)
        }
    }

    @Test
    fun approvalRequirementIsExplicit() {
        assertFailsWith<IllegalArgumentException> {
            WorkflowDefinition(WorkflowId("wf_v1_approval"), "Approval", WorkflowTrigger.MANUAL,
                setOf("session"), WorkflowBudget(1, 10, 100), listOf(safeStep), approvalRequired = true)
        }
    }
}
