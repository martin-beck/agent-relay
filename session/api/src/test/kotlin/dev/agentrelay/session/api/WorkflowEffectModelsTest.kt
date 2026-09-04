package dev.agentrelay.session.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkflowEffectModelsTest {
    private val intent = WorkflowEffectIntent(
        id = WorkflowEffectId("effect-1"),
        workflowId = WorkflowId("workflow-1"),
        runId = WorkflowRunId("run-1"),
        stepId = WorkflowStepId("step-1"),
        operation = "send-notification",
        idempotencyKey = "effect-key-1",
        requiredCapability = "notification.v1",
        payloadDigest = "sha256:payload",
    )

    @Test
    fun intentIsDurableBeforeDispatchAndDuplicateDeliveryIsIdempotent() {
        val journal = InMemoryWorkflowEffectJournal()
        assertEquals(WorkflowEffectLifecycle.INTENDED, (journal.intend(intent) as WorkflowEffectAppendResult.Appended).record.state)
        val duplicate = journal.intend(intent.copy(id = WorkflowEffectId("different-id")))

        assertEquals(WorkflowEffectLifecycle.INTENDED, (duplicate as WorkflowEffectAppendResult.Duplicate).record.state)
        assertEquals(0, duplicate.record.attempt)
    }

    @Test
    fun lostRequestAndLostResponseBecomeUncertainAndCannotBeDispatchedAgain() {
        val journal = InMemoryWorkflowEffectJournal()
        journal.intend(intent)
        journal.dispatch(intent.id)
        val uncertain = journal.markUncertain(intent.id, WorkflowEffectFailure.LOST_REQUEST)

        assertEquals(WorkflowEffectLifecycle.UNCERTAIN, uncertain.state)
        assertFailsWith<IllegalArgumentException> { journal.dispatch(intent.id) }
        assertFailsWith<IllegalArgumentException> {
            journal.resolve(intent.id, WorkflowEffectResolution.RETRY_AFTER_RECONCILIATION)
        }
    }

    @Test
    fun acknowledgementDoesNotPretendCompletionAndDelayedUnknownResultStaysUncertain() {
        val journal = InMemoryWorkflowEffectJournal()
        journal.intend(intent)
        journal.dispatch(intent.id)
        val acknowledged = journal.acknowledge(
            WorkflowEffectAcknowledgement(intent.id, intent.idempotencyKey, accepted = true),
        )
        assertEquals(WorkflowEffectLifecycle.ACKNOWLEDGED, acknowledged.state)
        val uncertain = journal.observeCompletion(
            WorkflowEffectCompletionObservation(intent.id, WorkflowEffectCompletion.UNKNOWN),
        )

        assertEquals(WorkflowEffectLifecycle.UNCERTAIN, uncertain.state)
        assertEquals(WorkflowEffectFailure.LOST_RESPONSE, uncertain.failure)
    }

    @Test
    fun explicitReconciliationCanAcceptCompletionOrRejectedOutcome() {
        val journal = InMemoryWorkflowEffectJournal()
        journal.intend(intent)
        journal.dispatch(intent.id)
        journal.markUncertain(intent.id, WorkflowEffectFailure.DISCONNECTED)
        val resolved = journal.resolve(intent.id, WorkflowEffectResolution.ACCEPT_AS_COMPLETED)

        assertEquals(WorkflowEffectLifecycle.RESOLVED, resolved.state)
        assertEquals(WorkflowEffectResolution.ACCEPT_AS_COMPLETED, resolved.resolution)
        assertFailsWith<IllegalArgumentException> {
            journal.observeCompletion(
                WorkflowEffectCompletionObservation(intent.id, WorkflowEffectCompletion.COMPLETED),
            )
        }
    }

    @Test
    fun safeCompensationIsRequiredBeforeExplicitRetryResolution() {
        val journal = InMemoryWorkflowEffectJournal()
        journal.intend(intent.copy(compensation = WorkflowEffectCompensationPolicy.Safe))
        journal.dispatch(intent.id)
        journal.markUncertain(intent.id, WorkflowEffectFailure.PROVIDER_RESTARTED)

        assertEquals(
            WorkflowEffectResolution.RETRY_AFTER_RECONCILIATION,
            journal.resolve(intent.id, WorkflowEffectResolution.RETRY_AFTER_RECONCILIATION).resolution,
        )
    }

    @Test
    fun rejectedAcknowledgementCannotBePromotedToCompletion() {
        val journal = InMemoryWorkflowEffectJournal()
        journal.intend(intent)
        journal.dispatch(intent.id)
        assertEquals(
            WorkflowEffectLifecycle.REJECTED,
            journal.acknowledge(
                WorkflowEffectAcknowledgement(intent.id, intent.idempotencyKey, accepted = false),
            ).state,
        )
        assertFailsWith<IllegalArgumentException> {
            journal.observeCompletion(
                WorkflowEffectCompletionObservation(intent.id, WorkflowEffectCompletion.COMPLETED),
            )
        }
    }
}
