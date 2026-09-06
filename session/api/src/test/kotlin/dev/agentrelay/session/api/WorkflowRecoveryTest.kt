package dev.agentrelay.session.api

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.Test

class WorkflowRecoveryTest {
    private val checkpoint = WorkflowCheckpoint(
        checkpointId = "checkpoint-1",
        schemaVersion = 1,
        workflowId = WorkflowId("workflow-1"),
        runId = WorkflowRunId("run-1"),
        stepId = WorkflowStepId("step-1"),
        runState = WorkflowRunState.RUNNING,
        stepState = WorkflowStepState.RUNNING,
        attempt = 1,
        capturedAtMillis = 100,
        leaseExpiresAtMillis = 200,
        cancellationRequested = false,
        effect = WorkflowEffectState.NONE,
        journalSequence = 3,
        authorityEpoch = 7,
    )

    @Test
    fun captureIsAtomicAndIdempotentAfterAmbiguousCommit() {
        var afterCommits = 0
        val store = InMemoryWorkflowCheckpointStore(afterCommit = { afterCommits++ })
        assertEquals(checkpoint, store.capture(checkpoint))
        assertEquals(checkpoint, store.capture(checkpoint))
        assertEquals(1, store.checkpoints().size)
        assertEquals(1, afterCommits)
    }

    @Test
    fun recoveryIsBoundedAndReacquiresExpiredLease() {
        val store = InMemoryWorkflowCheckpointStore()
        store.capture(checkpoint)
        store.capture(checkpoint.copy(checkpointId = "checkpoint-2", capturedAtMillis = 101))
        val report = WorkflowRecoveryCoordinator(store).recover(nowMillis = 300, maxItems = 1)
        assertEquals(1, report.decisions.size)
        assertEquals(1, report.remaining)
        assertEquals(WorkflowRecoveryAction.RESUME, report.decisions.single().action)
        assertEquals("stale-lease-reacquire", report.decisions.single().reason)
    }

    @Test
    fun cancellationRequiresExplicitCompensationAndUncertainEffectsNeedAttention() {
        val store = InMemoryWorkflowCheckpointStore()
        val cancelled = checkpoint.copy(cancellationRequested = true, effect = WorkflowEffectState.IDEMPOTENT)
        val uncertain = checkpoint.copy(checkpointId = "checkpoint-2", effect = WorkflowEffectState.UNCERTAIN)
        store.capture(cancelled)
        store.capture(uncertain)
        val coordinator = WorkflowRecoveryCoordinator(store)
        val decisions = coordinator.recover(300, 64).decisions.associateBy { it.checkpoint.checkpointId }
        assertEquals(WorkflowRecoveryAction.COMPENSATE, decisions["checkpoint-1"]?.action)
        assertEquals(WorkflowRecoveryAction.ATTENTION, decisions["checkpoint-2"]?.action)
        val compensation = coordinator.recordCompensation(cancelled, "compensation-1", 301)
        assertEquals(compensation, coordinator.recordCompensation(cancelled, "compensation-1", 999))
        assertFailsWith<IllegalArgumentException> {
            coordinator.recordCompensation(uncertain.copy(cancellationRequested = true), "compensation-2", 301)
        }
    }

    @Test
    fun restartDoesNotRepeatRecordedCompensationAndExhaustedRetriesNeedAttention() {
        val store = InMemoryWorkflowCheckpointStore()
        val cancelled = checkpoint.copy(cancellationRequested = true, effect = WorkflowEffectState.IDEMPOTENT)
        val exhausted = checkpoint.copy(checkpointId = "checkpoint-2", attempt = 4)
        store.capture(cancelled)
        store.capture(exhausted)
        val first = WorkflowRecoveryCoordinator(store).recordCompensation(cancelled, "compensation-1", 301)

        val restarted = WorkflowRecoveryCoordinator(store)
        val decisions = restarted.recover(300, 64).decisions.associateBy { it.checkpoint.checkpointId }

        assertEquals(first, store.compensations().single())
        assertEquals(WorkflowRecoveryAction.COMPENSATED, decisions["checkpoint-1"]?.action)
        assertEquals("compensation-already-recorded", decisions["checkpoint-1"]?.reason)
        assertEquals(WorkflowRecoveryAction.ATTENTION, decisions["checkpoint-2"]?.action)
        assertEquals("retry-budget-exhausted", decisions["checkpoint-2"]?.reason)
    }

    @Test
    fun retryPolicyIsBoundedAndDeterministic() {
        val policy = WorkflowRetryPolicy(maxAttempts = 4, baseBackoffMillis = 100, maxBackoffMillis = 250)
        assertTrue(policy.allows(4))
        assertTrue(!policy.allows(5))
        assertEquals(100, policy.delayBeforeAttempt(1))
        assertEquals(200, policy.delayBeforeAttempt(2))
        assertEquals(250, policy.delayBeforeAttempt(3))
        assertFailsWith<IllegalArgumentException> { policy.delayBeforeAttempt(0) }
        assertFailsWith<IllegalArgumentException> { policy.delayBeforeAttempt(5) }
    }

    @Test
    fun incompatibleCheckpointIsQuarantinedAndDoesNotResume() {
        val store = InMemoryWorkflowCheckpointStore()
        val incompatible = checkpoint.copy(schemaVersion = 2)
        store.capture(incompatible)
        val decision = WorkflowRecoveryCoordinator(store).recover(300, 64).decisions.single()
        assertEquals(WorkflowRecoveryAction.QUARANTINED, decision.action)
        assertTrue(store.quarantinedReasons().containsKey("checkpoint-1"))
    }

    @Test
    fun invalidBatchAndCheckpointValuesAreRejected() {
        assertFailsWith<IllegalArgumentException> { WorkflowRecoveryCoordinator(InMemoryWorkflowCheckpointStore()).recover(0, 0) }
        assertFailsWith<IllegalArgumentException> {
            checkpoint.copy(attempt = 33)
        }
        assertFailsWith<IllegalArgumentException> {
            checkpoint.copy(leaseExpiresAtMillis = 99)
        }
    }
}
