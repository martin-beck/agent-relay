package com.example.agentrelay.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class OutcomeOnboardingContractsTest {
    private val start = OutcomeOnboardingReducer.initial("onboarding_demo-1")

    @Test
    fun outcomeJourneyReachesReviewableWorkflowAndIsIdempotentOnResume() {
        val outcome = OutcomeOnboardingReducer.reduce(start, OutcomeOnboardingAction.Begin)
        val safety = OutcomeOnboardingReducer.reduce(
            outcome,
            OutcomeOnboardingAction.SubmitOutcome("  Review my open tasks  "),
        )
        val review = OutcomeOnboardingReducer.reduce(safety, OutcomeOnboardingAction.ConfirmSafety)
        val complete = OutcomeOnboardingReducer.reduce(review, OutcomeOnboardingAction.ConfirmWorkflow)

        assertEquals(OutcomeOnboardingStage.COMPLETE, complete.stage)
        assertEquals("Review my open tasks", safety.desiredOutcome)
        assertEquals(complete, OutcomeOnboardingReducer.reduce(complete, OutcomeOnboardingAction.Resume))
    }

    @Test
    fun sensitiveInputNeverEntersStateAndEmptyOutcomeExplainsBlock() {
        val outcome = OutcomeOnboardingReducer.reduce(start, OutcomeOnboardingAction.Begin)
        val secret = OutcomeOnboardingReducer.reduce(
            outcome,
            OutcomeOnboardingAction.SubmitOutcome("Use token: abc123 at https://example.test"),
        )
        assertEquals(OutcomeOnboardingStage.BLOCKED, secret.stage)
        assertEquals(null, secret.desiredOutcome)
        assertEquals(OutcomeOnboardingBlockReason.SENSITIVE_INPUT, secret.blockReason)

        val empty = OutcomeOnboardingReducer.reduce(outcome, OutcomeOnboardingAction.SubmitOutcome("  "))
        assertEquals(OutcomeOnboardingBlockReason.EMPTY_OUTCOME, empty.blockReason)
    }

    @Test
    fun repeatedActionsCannotExceedBoundedInteractionCost() {
        var state = start
        repeat(OutcomeOnboardingState.MAX_INTERACTIONS + 3) {
            state = OutcomeOnboardingReducer.reduce(state, OutcomeOnboardingAction.Begin)
        }
        assertEquals(OutcomeOnboardingState.MAX_INTERACTIONS, state.interactions)
        assertEquals(OutcomeOnboardingBlockReason.INTERACTION_LIMIT, state.blockReason)
    }
}
