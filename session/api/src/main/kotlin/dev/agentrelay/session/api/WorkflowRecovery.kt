package dev.agentrelay.session.api

/** Durable, provider-neutral progress captured at a journal boundary. */
data class WorkflowCheckpoint(
    val checkpointId: String,
    val schemaVersion: Int,
    val workflowId: WorkflowId,
    val runId: WorkflowRunId,
    val stepId: WorkflowStepId,
    val runState: WorkflowRunState,
    val stepState: WorkflowStepState,
    val attempt: Int,
    val capturedAtMillis: Long,
    val leaseExpiresAtMillis: Long?,
    val cancellationRequested: Boolean,
    val effect: WorkflowEffectState,
    val journalSequence: Long,
    val authorityEpoch: Long,
) {
    init {
        requireRecoveryId(checkpointId, "Checkpoint id")
        require(schemaVersion > 0) { "Checkpoint schema version is invalid" }
        require(attempt in 0..MAX_RECOVERY_ATTEMPTS) { "Checkpoint attempt is invalid" }
        require(capturedAtMillis >= 0) { "Checkpoint timestamp is invalid" }
        require(leaseExpiresAtMillis == null || leaseExpiresAtMillis >= capturedAtMillis) {
            "Checkpoint lease is invalid"
        }
        require(journalSequence >= 0) { "Checkpoint journal sequence is invalid" }
        require(authorityEpoch >= 0) { "Checkpoint authority epoch is invalid" }
    }

    fun isCompatible(currentSchema: Int = CURRENT_CHECKPOINT_SCHEMA): Boolean =
        schemaVersion in 1..currentSchema
}

data class WorkflowCompensation(
    val compensationId: String,
    val checkpointId: String,
    val authorityEpoch: Long,
    val recordedAtMillis: Long,
) {
    init {
        requireRecoveryId(compensationId, "Compensation id")
        requireRecoveryId(checkpointId, "Compensation checkpoint id")
        require(authorityEpoch >= 0) { "Compensation authority epoch is invalid" }
        require(recordedAtMillis >= 0) { "Compensation timestamp is invalid" }
    }
}

interface WorkflowCheckpointStore {
    /** The checkpoint and its idempotency key become durable as one commit. */
    fun capture(checkpoint: WorkflowCheckpoint): WorkflowCheckpoint

    fun checkpoints(): List<WorkflowCheckpoint>

    fun quarantine(checkpointId: String, reason: String)

    fun compensation(compensation: WorkflowCompensation): WorkflowCompensation

    fun compensations(): List<WorkflowCompensation>
}

enum class WorkflowRecoveryAction {
    RESUME,
    COMPENSATE,
    COMPENSATED,
    CANCELLED,
    QUARANTINED,
    ATTENTION,
}

/** Deterministic exponential retry budget shared by restart and reconnect recovery. */
data class WorkflowRecoveryDecision(
    val checkpoint: WorkflowCheckpoint,
    val action: WorkflowRecoveryAction,
    val reason: String,
)

data class WorkflowRecoveryReport(
    val decisions: List<WorkflowRecoveryDecision>,
    val remaining: Int,
)

/**
 * Deterministic recovery policy. It only returns work described by a durable checkpoint;
 * callers must execute compensation explicitly and record its outcome separately.
 */
class WorkflowRecoveryCoordinator(
    private val store: WorkflowCheckpointStore,
    private val currentSchema: Int = CURRENT_CHECKPOINT_SCHEMA,
    private val retryPolicy: WorkflowRetryPolicy = WorkflowRetryPolicy(3, 1_000, 60_000),
) {
    init {
        require(currentSchema > 0) { "Recovery schema must be positive" }
    }

    @Synchronized
    fun recover(nowMillis: Long, maxItems: Int): WorkflowRecoveryReport {
        require(nowMillis >= 0) { "Recovery timestamp is invalid" }
        require(maxItems in 1..MAX_RECOVERY_BATCH) { "Recovery batch is invalid" }
        val candidates = store.checkpoints().sortedWith(
            compareBy<WorkflowCheckpoint> { it.capturedAtMillis }
                .thenBy { it.checkpointId },
        )
        val selected = candidates.take(maxItems)
        val decisions = selected.map { checkpoint -> decide(checkpoint, nowMillis) }
        return WorkflowRecoveryReport(decisions, (candidates.size - selected.size).coerceAtLeast(0))
    }

    @Synchronized
    fun recordCompensation(
        checkpoint: WorkflowCheckpoint,
        compensationId: String,
        nowMillis: Long,
    ): WorkflowCompensation {
        require(checkpoint.cancellationRequested) { "Compensation requires cancellation" }
        require(checkpoint.effect == WorkflowEffectState.IDEMPOTENT) {
            "Uncertain effects require operator attention"
        }
        return store.compensation(
            WorkflowCompensation(compensationId, checkpoint.checkpointId, checkpoint.authorityEpoch, nowMillis),
        )
    }

    private fun decide(checkpoint: WorkflowCheckpoint, nowMillis: Long): WorkflowRecoveryDecision {
        if (store.compensations().any { it.checkpointId == checkpoint.checkpointId }) {
            return WorkflowRecoveryDecision(
                checkpoint,
                WorkflowRecoveryAction.COMPENSATED,
                "compensation-already-recorded",
            )
        }
        if (!checkpoint.isCompatible(currentSchema)) {
            store.quarantine(checkpoint.checkpointId, "unsupported-checkpoint-schema")
            return WorkflowRecoveryDecision(checkpoint, WorkflowRecoveryAction.QUARANTINED, "unsupported-checkpoint-schema")
        }
        if (checkpoint.cancellationRequested) {
            return when (checkpoint.effect) {
                WorkflowEffectState.NONE -> WorkflowRecoveryDecision(
                    checkpoint,
                    WorkflowRecoveryAction.CANCELLED,
                    "cancelled-before-effect",
                )
                WorkflowEffectState.IDEMPOTENT -> WorkflowRecoveryDecision(
                    checkpoint,
                    WorkflowRecoveryAction.COMPENSATE,
                    "explicit-compensation-required",
                )
                WorkflowEffectState.UNCERTAIN -> WorkflowRecoveryDecision(
                    checkpoint,
                    WorkflowRecoveryAction.ATTENTION,
                    "effect-outcome-uncertain",
                )
            }
        }
        if (checkpoint.effect == WorkflowEffectState.UNCERTAIN) {
            return WorkflowRecoveryDecision(checkpoint, WorkflowRecoveryAction.ATTENTION, "effect-outcome-uncertain")
        }
        if (!retryPolicy.allows(checkpoint.attempt)) {
            return WorkflowRecoveryDecision(checkpoint, WorkflowRecoveryAction.ATTENTION, "retry-budget-exhausted")
        }
        val stale = checkpoint.leaseExpiresAtMillis?.let { it <= nowMillis } ?: true
        return WorkflowRecoveryDecision(
            checkpoint,
            WorkflowRecoveryAction.RESUME,
            if (stale) "stale-lease-reacquire" else "durable-checkpoint-resume",
        )
    }
}

class InMemoryWorkflowCheckpointStore(
    private val beforeCommit: (() -> Unit)? = null,
    private val afterCommit: (() -> Unit)? = null,
) : WorkflowCheckpointStore {
    private val checkpoints = linkedMapOf<String, WorkflowCheckpoint>()
    private val quarantined = linkedMapOf<String, String>()
    private val recordedCompensations = linkedMapOf<String, WorkflowCompensation>()

    @Synchronized
    override fun capture(checkpoint: WorkflowCheckpoint): WorkflowCheckpoint {
        checkpoints[checkpoint.checkpointId]?.let { return it }
        beforeCommit?.invoke()
        checkpoints[checkpoint.checkpointId] = checkpoint
        afterCommit?.invoke()
        return checkpoint
    }

    @Synchronized
    override fun checkpoints(): List<WorkflowCheckpoint> = checkpoints.values.toList()

    @Synchronized
    override fun quarantine(checkpointId: String, reason: String) {
        requireRecoveryId(checkpointId, "Quarantine checkpoint id")
        requireRecoveryId(reason, "Quarantine reason")
        quarantined[checkpointId] = reason
    }

    @Synchronized
    override fun compensation(compensation: WorkflowCompensation): WorkflowCompensation {
        recordedCompensations[compensation.compensationId]?.let { return it }
        recordedCompensations[compensation.compensationId] = compensation
        return compensation
    }

    @Synchronized
    override fun compensations(): List<WorkflowCompensation> = recordedCompensations.values.toList()

    fun quarantinedReasons(): Map<String, String> = synchronized(this) { quarantined.toMap() }
}

private fun requireRecoveryId(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_RECOVERY_ID_CHARS) { "$label is invalid" }
}

private const val CURRENT_CHECKPOINT_SCHEMA = 1
private const val MAX_RECOVERY_ID_CHARS = 512
private const val MAX_RECOVERY_ATTEMPTS = 32
private const val MAX_RECOVERY_BATCH = 64
