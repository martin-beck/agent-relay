package dev.agentrelay.session.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkflowTaskCreationModelsTest {
    private val request = WorkflowTaskCreationRequest(
        desiredOutcome = "Synchronize the approved project",
        triggers = setOf("user-approved"),
        constraints = setOf("offline-safe"),
        prohibitedEffects = setOf("send-private-data"),
        completion = WorkflowCompletionContract(setOf("sync-confirmed"), emptySet(), false),
        budget = WorkflowBudget(1_000, 1_024, 10_000, 4),
        risk = WorkflowRisk.R1,
    )

    @Test
    fun materializationPreservesOutcomeAndStartsProposed() {
        val proposal = request.materialize(
            WorkflowId("task"),
            WorkflowId("workflow"),
            "project",
            "revision",
        )

        assertEquals("Synchronize the approved project", proposal.desiredOutcome)
        assertEquals(setOf("user-approved"), proposal.triggers)
        assertEquals(WorkflowTaskState.PROPOSED, proposal.task.state)
    }

    @Test
    fun constraintsAndProhibitedEffectsCannotOverlap() {
        assertFailsWith<IllegalArgumentException> {
            request.copy(constraints = setOf("offline-safe"), prohibitedEffects = setOf("offline-safe"))
        }
    }

    @Test
    fun emptyOrOversizedCreationFieldsAreRejected() {
        assertFailsWith<IllegalArgumentException> { request.copy(desiredOutcome = " ") }
        assertFailsWith<IllegalArgumentException> {
            request.copy(triggers = (0..128).map { "trigger-$it" }.toSet())
        }
        assertFailsWith<IllegalArgumentException> {
            request.materialize(WorkflowId("task"), WorkflowId("workflow"), "project", "revision")
                .copy(task = request.materialize(WorkflowId("task"), WorkflowId("workflow"), "project", "revision").task.copy(state = WorkflowTaskState.APPROVED))
        }
    }
}
