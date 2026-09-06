package dev.agentrelay.session.api

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Disposable local production-graph assurance: admission, durable effect,
 * checkpointed restart, and terminal result remain bounded and replay-safe.
 */
class ProductionGraphJourneyAssuranceTest {
    @Test
    fun localGraphSurvivesRestartAtDurableCheckpoint() {
        val request = WorkflowTaskCreationRequest(
            desiredOutcome = "Record a bounded local result",
            triggers = setOf("assurance-fixture"),
            constraints = setOf("local-only"),
            prohibitedEffects = setOf("network-write"),
            completion = WorkflowCompletionContract(setOf("result-recorded"), emptySet(), false),
            budget = WorkflowBudget(1_000, 1_024, 10_000, 2),
            risk = WorkflowRisk.R1,
        )
        val task = request.materialize(WorkflowId("task"), WorkflowId("workflow"), "project", "base")
        val run = WorkflowRun(WorkflowRunId("run"), task.task.id, WorkflowRunState.RUNNING)
        val step = WorkflowStep(WorkflowStepId("step"), run.id, ordinal = 0, state = WorkflowStepState.RUNNING)
        val journal = InMemoryWorkflowEffectJournal()
        val intent = WorkflowEffectIntent(
            WorkflowEffectId("effect"),
            task.task.id,
            run.id,
            step.id,
            operation = "record-local-result",
            idempotencyKey = "fixture-effect",
            requiredCapability = "local-storage",
            payloadDigest = "digest",
        )
        journal.intend(intent)
        journal.dispatch(intent.id)
        journal.acknowledge(WorkflowEffectAcknowledgement(intent.id, "fixture-effect", accepted = true))
        journal.observeCompletion(
            WorkflowEffectCompletionObservation(intent.id, WorkflowEffectCompletion.COMPLETED),
        )
        val store = InMemoryWorkflowCheckpointStore()
        store.capture(
            WorkflowCheckpoint(
                "checkpoint", 1, task.task.id, run.id, step.id,
                WorkflowRunState.RUNNING, WorkflowStepState.RUNNING, 1, 100, 200,
                cancellationRequested = false, WorkflowEffectState.IDEMPOTENT, 4, 1,
            ),
        )

        val recovery = WorkflowRecoveryCoordinator(store).recover(nowMillis = 300, maxItems = 1)

        assertEquals(WorkflowTaskState.PROPOSED, task.task.state)
        assertEquals(WorkflowEffectLifecycle.COMPLETED, journal.record(intent.id)?.state)
        assertEquals(WorkflowRecoveryAction.RESUME, recovery.decisions.single().action)
        assertEquals(4, recovery.decisions.single().checkpoint.journalSequence)
    }
}
