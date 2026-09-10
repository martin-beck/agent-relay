/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.onboarding

/** Stable stages for the outcome-first first-run journey. */
enum class OutcomeOnboardingStage {
    WELCOME,
    OUTCOME,
    SAFETY_REVIEW,
    WORKFLOW_REVIEW,
    COMPLETE,
    BLOCKED,
}

enum class OutcomeOnboardingBlockReason {
    EMPTY_OUTCOME,
    SENSITIVE_INPUT,
    INTERACTION_LIMIT,
}

data class OutcomeOnboardingState(
    val journeyId: String,
    val revision: Long,
    val stage: OutcomeOnboardingStage,
    val desiredOutcome: String? = null,
    val workflowTitle: String? = null,
    val blockReason: OutcomeOnboardingBlockReason? = null,
    val interactions: Int = 0,
) {
    init {
        require(journeyId.matches(Regex("onboarding_[a-z0-9-]{1,48}")))
        require(revision >= 0)
        require(interactions in 0..MAX_INTERACTIONS)
        require(desiredOutcome == null || desiredOutcome.length <= MAX_TEXT)
        require(workflowTitle == null || workflowTitle.length <= MAX_TEXT)
        require(stage == OutcomeOnboardingStage.BLOCKED == (blockReason != null))
    }

    companion object {
        const val MAX_INTERACTIONS = 12
        const val MAX_TEXT = 240
    }
}

sealed interface OutcomeOnboardingAction {
    data object Begin : OutcomeOnboardingAction
    data class SubmitOutcome(val text: String) : OutcomeOnboardingAction
    data object ConfirmSafety : OutcomeOnboardingAction
    data object ConfirmWorkflow : OutcomeOnboardingAction
    data object Decline : OutcomeOnboardingAction
    data object Resume : OutcomeOnboardingAction
}

/** Pure, replayable state machine for an outcome-first first-run journey. */
object OutcomeOnboardingReducer {
    fun initial(journeyId: String): OutcomeOnboardingState =
        OutcomeOnboardingState(journeyId, 0, OutcomeOnboardingStage.WELCOME)

    fun reduce(state: OutcomeOnboardingState, action: OutcomeOnboardingAction): OutcomeOnboardingState {
        if (action is OutcomeOnboardingAction.Resume) return state
        if (state.stage == OutcomeOnboardingStage.COMPLETE) return state
        if (state.interactions >= OutcomeOnboardingState.MAX_INTERACTIONS) {
            return blocked(state, OutcomeOnboardingBlockReason.INTERACTION_LIMIT)
        }
        return when (action) {
            OutcomeOnboardingAction.Begin -> advance(state, OutcomeOnboardingStage.OUTCOME)
            is OutcomeOnboardingAction.SubmitOutcome -> submitOutcome(state, action.text)
            OutcomeOnboardingAction.ConfirmSafety ->
                if (state.stage == OutcomeOnboardingStage.SAFETY_REVIEW) {
                    advance(state, OutcomeOnboardingStage.WORKFLOW_REVIEW)
                } else {
                    state
                }
            OutcomeOnboardingAction.ConfirmWorkflow ->
                if (state.stage == OutcomeOnboardingStage.WORKFLOW_REVIEW) {
                    advance(state, OutcomeOnboardingStage.COMPLETE)
                } else {
                    state
                }
            OutcomeOnboardingAction.Decline -> blocked(state, OutcomeOnboardingBlockReason.SENSITIVE_INPUT)
            OutcomeOnboardingAction.Resume -> state
        }
    }

    private fun submitOutcome(state: OutcomeOnboardingState, text: String): OutcomeOnboardingState {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        return when {
            normalized.isEmpty() -> blocked(state, OutcomeOnboardingBlockReason.EMPTY_OUTCOME)
            containsSensitiveInput(normalized) -> blocked(state, OutcomeOnboardingBlockReason.SENSITIVE_INPUT)
            state.stage != OutcomeOnboardingStage.OUTCOME -> state
            else -> state.copy(
                revision = state.revision + 1,
                stage = OutcomeOnboardingStage.SAFETY_REVIEW,
                desiredOutcome = normalized,
                workflowTitle = normalized.take(80),
                interactions = state.interactions + 1,
            )
        }
    }

    private fun advance(state: OutcomeOnboardingState, stage: OutcomeOnboardingStage): OutcomeOnboardingState =
        state.copy(revision = state.revision + 1, stage = stage, interactions = state.interactions + 1)

    private fun blocked(
        state: OutcomeOnboardingState,
        reason: OutcomeOnboardingBlockReason,
    ): OutcomeOnboardingState =
        state.copy(
            revision = state.revision + 1,
            stage = OutcomeOnboardingStage.BLOCKED,
            desiredOutcome = null,
            workflowTitle = null,
            blockReason = reason,
            interactions = (state.interactions + 1).coerceAtMost(OutcomeOnboardingState.MAX_INTERACTIONS),
        )

    private fun containsSensitiveInput(text: String): Boolean =
        Regex("(?i)(https?://|ssh://|token\\s*[:=]|password\\s*[:=]|api[_ -]?key\\s*[:=])").containsMatchIn(text)
}
