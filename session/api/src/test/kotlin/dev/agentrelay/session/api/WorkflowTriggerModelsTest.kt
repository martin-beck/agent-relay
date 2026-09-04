package dev.agentrelay.session.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorkflowTriggerModelsTest {
    private val request = WorkflowTriggerRequest(
        id = "trigger-1",
        workflowId = WorkflowId("workflow-1"),
        source = WorkflowTriggerSource.EVENT,
        sourceKey = "event-42",
        requestedAtEpochMillis = 10,
        correlationId = "correlation-1",
    )

    @Test
    fun admissionIsAtomicAndDuplicateReplayHasNoSecondPendingRequest() {
        val store = InMemoryWorkflowTriggerStore()
        val policy = TriggerAdmissionPolicy(deduplicationWindowMillis = 100)
        assertEquals(TriggerAdmission.ADMITTED, store.admit(request, 10, policy).admission)
        assertEquals(TriggerAdmission.DUPLICATE, store.admit(request.copy(id = "retry"), 20, policy).admission)
        assertEquals(1, store.pendingCount())
        assertEquals(1, store.receipts().size)
    }

    @Test
    fun policyRejectsDisabledSourcesAndBoundsPendingAdmission() {
        val disabled = TriggerAdmissionPolicy(enabledSources = setOf(WorkflowTriggerSource.MANUAL))
        val store = InMemoryWorkflowTriggerStore()
        assertEquals(TriggerAdmission.REJECTED, store.admit(request, 10, disabled).admission)
        val limited = TriggerAdmissionPolicy(maxPending = 1)
        assertEquals(TriggerAdmission.ADMITTED, store.admit(request, 10, limited).admission)
        assertEquals(
            TriggerAdmission.DELAYED,
            store.admit(request.copy(sourceKey = "event-43"), 11, limited).admission,
        )
    }

    @Test
    fun scheduleBoundsCatchUpAndAdvancesDurablyAcrossClockJumps() {
        val cursor = ScheduleCursor("schedule-1", nextDueEpochMillis = 100, intervalMillis = 10)
        assertTrue(cursor.dueOccurrences(95, 8).isEmpty())
        assertEquals(listOf(100L, 110L, 120L), cursor.dueOccurrences(125, 8))
        val advanced = cursor.advancedThrough(120)
        assertEquals(130, advanced.nextDueEpochMillis)
        assertEquals(120, advanced.lastEmittedEpochMillis)
        assertEquals(listOf(130L, 140L), advanced.dueOccurrences(150, 2))
    }

    @Test
    fun invalidAndSecretlyOversizedRequestsFailClosed() {
        assertFailsWith<IllegalArgumentException> { request.copy(requestedAtEpochMillis = -1) }
        assertFailsWith<IllegalArgumentException> {
            request.copy(payload = (0..64).associate { "key-$it" to "value" })
        }
        assertFailsWith<IllegalArgumentException> { ScheduleCursor("schedule", 0, 0) }
    }
}
