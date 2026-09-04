package dev.agentrelay.session.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkflowExecutionAttemptModelsTest {
    private val step = WorkflowStepId("step-1")
    private val attempt = WorkflowExecutionAttempt(
        id = WorkflowAttemptId("attempt-1"),
        workflowId = WorkflowId("workflow-1"),
        runId = WorkflowRunId("run-1"),
        stepId = step,
        workflowState = WorkflowRunState.RUNNING,
        stepState = WorkflowStepState.PENDING,
        attemptNumber = 1,
        agentInstanceId = AgentInstanceId("agent-1"),
        terminalSessionId = TerminalSessionId("terminal-1"),
        metadata = AttemptRuntimeMetadata("codex", "pty", mapOf("mode" to "interactive")),
        admittedAtMillis = 100,
        leaseExpiresAtMillis = 200,
        lastHeartbeatAtMillis = 100,
    )

    @Test
    fun admissionHeartbeatAndDuplicateCompletionAreIdempotent() {
        val journal = InMemoryWorkflowExecutionAttemptJournal()
        assertEquals(attempt, journal.admit(attempt))
        val running = journal.heartbeat(attempt.id, 150, 250)
        assertEquals(WorkflowAttemptState.RUNNING, running.state)
        val completed = journal.complete(attempt.id, AttemptCompletion.SUCCEEDED)
        assertEquals(completed, journal.complete(attempt.id, AttemptCompletion.SUCCEEDED))
        assertFailsWith<IllegalArgumentException> { journal.complete(attempt.id, AttemptCompletion.FAILED) }
        assertFailsWith<IllegalArgumentException> { journal.heartbeat(attempt.id, 160, 260) }
    }

    @Test
    fun oneActiveAttemptPerStepAndWorkflowSnapshotIsRequired() {
        val journal = InMemoryWorkflowExecutionAttemptJournal()
        journal.admit(attempt)
        assertFailsWith<IllegalArgumentException> { journal.admit(attempt.copy(id = WorkflowAttemptId("attempt-2"))) }
        assertFailsWith<IllegalArgumentException> {
            journal.admit(attempt.copy(id = WorkflowAttemptId("attempt-3"), workflowState = WorkflowRunState.PAUSED))
        }
        assertFailsWith<IllegalArgumentException> {
            journal.admit(attempt.copy(id = WorkflowAttemptId("attempt-4"), stepState = WorkflowStepState.RUNNING))
        }
    }

    @Test
    fun processLossAndTerminalDisconnectCanReconnectWithBoundedEvidence() {
        val journal = InMemoryWorkflowExecutionAttemptJournal()
        journal.admit(attempt)
        val disconnected = journal.disconnect(attempt.id, AttemptRecoveryCause.PROCESS_LOSS, 180, 9)
        assertEquals(WorkflowAttemptState.DISCONNECTED, disconnected.state)
        val recovered = journal.reconnect(
            attempt.id,
            AgentInstanceId("agent-2"),
            TerminalSessionId("terminal-2"),
            nowMillis = 220,
            leaseExpiresAtMillis = 300,
            journalSequence = 10,
            evidenceDigest = "sha256:recovery",
        )
        assertEquals(WorkflowAttemptState.RUNNING, recovered.state)
        assertEquals(1, recovered.recoveryCount)
        assertEquals("sha256:recovery", recovered.recoveryEvidence.single().evidenceDigest)
        assertEquals(SessionBoundary.RECONNECTED, recovered.sessionBoundaries.last().boundary)
    }

    @Test
    fun staleLeaseCanBeTakenOverButUncertainEffectCannot() {
        val journal = InMemoryWorkflowExecutionAttemptJournal()
        journal.admit(attempt)
        journal.heartbeat(attempt.id, 150, 180)
        val recovered = journal.reconnect(
            attempt.id,
            AgentInstanceId("agent-2"),
            TerminalSessionId("terminal-2"),
            nowMillis = 180,
            leaseExpiresAtMillis = 300,
            journalSequence = 10,
            evidenceDigest = "sha256:stale",
        )
        assertEquals(WorkflowAttemptState.RUNNING, recovered.state)
        journal.complete(attempt.id, AttemptCompletion.UNKNOWN)
        assertFailsWith<IllegalArgumentException> {
            journal.reconnect(
                attempt.id,
                AgentInstanceId("agent-3"),
                TerminalSessionId("terminal-3"),
                400,
                500,
                11,
                "sha256:late",
            )
        }
    }

    @Test
    fun redactionAndRecoveryBoundsRejectPrivateOrUnboundedData() {
        assertEquals(setOf("mode"), attempt.metadata.redacted(setOf("mode")).attributes.keys)
        assertFailsWith<IllegalArgumentException> {
            AttemptRuntimeMetadata("codex", "pty", mapOf("host" to "private.example"))
        }
        assertFailsWith<IllegalArgumentException> {
            attempt.copy(lastHeartbeatAtMillis = 201)
        }
        assertFailsWith<IllegalArgumentException> {
            attempt.copy(effect = WorkflowEffectState.UNCERTAIN)
        }
    }

    @Test
    fun uncertainCompletionIsTerminalAndAbandonmentIsExplicit() {
        val journal = InMemoryWorkflowExecutionAttemptJournal()
        journal.admit(attempt)
        val uncertain = journal.complete(attempt.id, AttemptCompletion.UNKNOWN)
        assertEquals(WorkflowAttemptState.UNCERTAIN, uncertain.state)
        assertEquals(WorkflowEffectState.UNCERTAIN, uncertain.effect)

        val other = InMemoryWorkflowExecutionAttemptJournal()
        other.admit(attempt)
        assertEquals(WorkflowAttemptState.ABANDONED, other.abandon(attempt.id, 250).state)
    }
}
