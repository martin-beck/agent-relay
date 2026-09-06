package dev.agentrelay.session.api

/** Durable cursor for a single workflow authority epoch. */
data class WorkflowReplayCursor(
    val sequence: Long = -1,
    val authorityEpoch: Long,
) {
    init {
        require(sequence >= -1) { "Replay cursor sequence is invalid" }
        require(authorityEpoch >= 0) { "Replay cursor authority epoch is invalid" }
    }
}

enum class WorkflowReplayDisposition { APPLY, DUPLICATE, GAP, AUTHORITY_CHANGED }

data class WorkflowReplayDecision(
    val disposition: WorkflowReplayDisposition,
    val nextCursor: WorkflowReplayCursor,
) {
    init {
        require(nextCursor.sequence >= -1) { "Replay decision cursor is invalid" }
    }
}

/**
 * Reconciles replayed events before they reach workflow effects.
 * Gaps and authority changes remain visible and never advance the cursor.
 */
class WorkflowReplayReconciler(private var cursor: WorkflowReplayCursor) {
    @Synchronized
    fun reconcile(sequence: Long, authorityEpoch: Long): WorkflowReplayDecision {
        require(sequence >= 0) { "Replay sequence is invalid" }
        if (authorityEpoch != cursor.authorityEpoch) {
            return WorkflowReplayDecision(WorkflowReplayDisposition.AUTHORITY_CHANGED, cursor)
        }
        if (sequence <= cursor.sequence) {
            return WorkflowReplayDecision(WorkflowReplayDisposition.DUPLICATE, cursor)
        }
        if (sequence != cursor.sequence + 1) {
            return WorkflowReplayDecision(WorkflowReplayDisposition.GAP, cursor)
        }
        cursor = cursor.copy(sequence = sequence)
        return WorkflowReplayDecision(WorkflowReplayDisposition.APPLY, cursor)
    }

    @Synchronized
    fun cursor(): WorkflowReplayCursor = cursor
}
