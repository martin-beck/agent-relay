package dev.agentrelay.session.api

/** Opaque identity for one externally visible effect intent. */
@JvmInline
value class WorkflowEffectId(val value: String) {
    init {
        requireEffectValue(value, "Effect id")
    }
}

/** An intent is durable before any provider I/O is attempted. */
data class WorkflowEffectIntent(
    val id: WorkflowEffectId,
    val workflowId: WorkflowId,
    val runId: WorkflowRunId,
    val stepId: WorkflowStepId,
    val operation: String,
    val idempotencyKey: String,
    val requiredCapability: String,
    val payloadDigest: String,
    val compensation: WorkflowEffectCompensationPolicy = WorkflowEffectCompensationPolicy.NotSafe,
) {
    init {
        requireEffectValue(operation, "Effect operation")
        requireEffectValue(idempotencyKey, "Effect idempotency key")
        requireEffectValue(requiredCapability, "Effect capability")
        requireEffectValue(payloadDigest, "Effect payload digest")
    }
}

enum class WorkflowEffectCompensationPolicy {
    NotSafe,
    Safe,
}

enum class WorkflowEffectLifecycle {
    INTENDED,
    DISPATCHED,
    ACKNOWLEDGED,
    COMPLETED,
    REJECTED,
    DUPLICATE,
    UNCERTAIN,
    RESOLVED,
}

/** Provider acknowledgement is distinct from completion: acceptance may be followed by a lost result. */
data class WorkflowEffectAcknowledgement(
    val effectId: WorkflowEffectId,
    val idempotencyKey: String,
    val accepted: Boolean,
    val providerEffectId: String? = null,
    val duplicate: Boolean = false,
) {
    init {
        requireEffectValue(idempotencyKey, "Acknowledgement idempotency key")
        providerEffectId?.let { requireEffectValue(it, "Provider effect id") }
        require(!duplicate || accepted) { "A duplicate acknowledgement must be accepted" }
    }
}

enum class WorkflowEffectCompletion {
    COMPLETED,
    FAILED,
    UNKNOWN,
}

/** A provider or reconciler observation of the externally visible result. */
data class WorkflowEffectCompletionObservation(
    val effectId: WorkflowEffectId,
    val completion: WorkflowEffectCompletion,
    val providerEffectId: String? = null,
) {
    init {
        providerEffectId?.let { requireEffectValue(it, "Provider effect id") }
    }
}

enum class WorkflowEffectFailure {
    TIMEOUT,
    DISCONNECTED,
    PROVIDER_RESTARTED,
    LOST_REQUEST,
    LOST_RESPONSE,
}

/** Explicit operator decisions are the only way out of an uncertain state. */
enum class WorkflowEffectResolution {
    ACCEPT_AS_COMPLETED,
    ACCEPT_AS_REJECTED,
    RETRY_AFTER_RECONCILIATION,
}

data class WorkflowEffectRecord(
    val intent: WorkflowEffectIntent,
    val state: WorkflowEffectLifecycle = WorkflowEffectLifecycle.INTENDED,
    val acknowledgement: WorkflowEffectAcknowledgement? = null,
    val completion: WorkflowEffectCompletionObservation? = null,
    val failure: WorkflowEffectFailure? = null,
    val resolution: WorkflowEffectResolution? = null,
    val attempt: Int = 0,
) {
    init {
        require(attempt in 0..MAX_EFFECT_ATTEMPTS) { "Effect attempt is invalid" }
        require(state != WorkflowEffectLifecycle.UNCERTAIN || failure != null) {
            "Uncertain effects require a failure cause"
        }
        require(state != WorkflowEffectLifecycle.RESOLVED || resolution != null) {
            "Resolved effects require an explicit resolution"
        }
        require(state != WorkflowEffectLifecycle.COMPLETED || completion?.completion == WorkflowEffectCompletion.COMPLETED) {
            "Completed effects require completed evidence"
        }
    }
}

sealed interface WorkflowEffectAppendResult {
    val record: WorkflowEffectRecord

    data class Appended(override val record: WorkflowEffectRecord) : WorkflowEffectAppendResult

    data class Duplicate(override val record: WorkflowEffectRecord) : WorkflowEffectAppendResult
}

/**
 * Durable effect boundary used by the host engine. Implementations must persist the intent
 * before dispatch and must never turn a transport failure into a retryable failure implicitly.
 */
interface WorkflowEffectJournal {
    fun intend(intent: WorkflowEffectIntent): WorkflowEffectAppendResult
    fun dispatch(id: WorkflowEffectId): WorkflowEffectRecord
    fun acknowledge(acknowledgement: WorkflowEffectAcknowledgement): WorkflowEffectRecord
    fun observeCompletion(observation: WorkflowEffectCompletionObservation): WorkflowEffectRecord
    fun markUncertain(id: WorkflowEffectId, failure: WorkflowEffectFailure): WorkflowEffectRecord
    fun resolve(id: WorkflowEffectId, resolution: WorkflowEffectResolution): WorkflowEffectRecord
    fun record(id: WorkflowEffectId): WorkflowEffectRecord?
}

/** Deterministic reference adapter for contract and hostile-fault tests. */
class InMemoryWorkflowEffectJournal : WorkflowEffectJournal {
    private val records = linkedMapOf<WorkflowEffectId, WorkflowEffectRecord>()
    private val idempotency = mutableMapOf<String, WorkflowEffectId>()

    @Synchronized
    override fun intend(intent: WorkflowEffectIntent): WorkflowEffectAppendResult {
        val existingId = idempotency[intent.idempotencyKey]
        if (existingId != null) return WorkflowEffectAppendResult.Duplicate(records.getValue(existingId))
        require(records[intent.id] == null) { "Effect id already exists" }
        val record = WorkflowEffectRecord(intent)
        records[intent.id] = record
        idempotency[intent.idempotencyKey] = intent.id
        return WorkflowEffectAppendResult.Appended(record)
    }

    @Synchronized
    override fun dispatch(id: WorkflowEffectId): WorkflowEffectRecord {
        val current = get(id)
        require(current.state == WorkflowEffectLifecycle.INTENDED) { "Only an intended effect may be dispatched" }
        return save(current.copy(state = WorkflowEffectLifecycle.DISPATCHED, attempt = current.attempt + 1))
    }

    @Synchronized
    override fun acknowledge(acknowledgement: WorkflowEffectAcknowledgement): WorkflowEffectRecord {
        val current = get(acknowledgement.effectId)
        require(current.state == WorkflowEffectLifecycle.DISPATCHED) {
            "Only a dispatched effect may be acknowledged"
        }
        require(current.intent.idempotencyKey == acknowledgement.idempotencyKey) {
            "Acknowledgement idempotency key does not match intent"
        }
        val state = when {
            !acknowledgement.accepted -> WorkflowEffectLifecycle.REJECTED
            acknowledgement.duplicate -> WorkflowEffectLifecycle.DUPLICATE
            else -> WorkflowEffectLifecycle.ACKNOWLEDGED
        }
        return save(current.copy(state = state, acknowledgement = acknowledgement))
    }

    @Synchronized
    override fun observeCompletion(observation: WorkflowEffectCompletionObservation): WorkflowEffectRecord {
        val current = get(observation.effectId)
        require(current.state == WorkflowEffectLifecycle.ACKNOWLEDGED || current.state == WorkflowEffectLifecycle.DUPLICATE) {
            "Completion requires acknowledgement"
        }
        val state = when (observation.completion) {
            WorkflowEffectCompletion.COMPLETED -> WorkflowEffectLifecycle.COMPLETED
            WorkflowEffectCompletion.FAILED -> WorkflowEffectLifecycle.REJECTED
            WorkflowEffectCompletion.UNKNOWN -> WorkflowEffectLifecycle.UNCERTAIN
        }
        return save(
            current.copy(
                state = state,
                completion = observation,
                failure = if (state == WorkflowEffectLifecycle.UNCERTAIN) {
                    WorkflowEffectFailure.LOST_RESPONSE
                } else {
                    null
                },
            ),
        )
    }

    @Synchronized
    override fun markUncertain(id: WorkflowEffectId, failure: WorkflowEffectFailure): WorkflowEffectRecord {
        val current = get(id)
        require(current.state == WorkflowEffectLifecycle.DISPATCHED || current.state == WorkflowEffectLifecycle.ACKNOWLEDGED) {
            "Only an in-flight effect may become uncertain"
        }
        return save(current.copy(state = WorkflowEffectLifecycle.UNCERTAIN, failure = failure))
    }

    @Synchronized
    override fun resolve(id: WorkflowEffectId, resolution: WorkflowEffectResolution): WorkflowEffectRecord {
        val current = get(id)
        require(current.state == WorkflowEffectLifecycle.UNCERTAIN) { "Only uncertain effects require resolution" }
        require(
            resolution != WorkflowEffectResolution.RETRY_AFTER_RECONCILIATION ||
                current.intent.compensation == WorkflowEffectCompensationPolicy.Safe,
        ) {
            "Retry requires a compensation-safe intent"
        }
        return save(current.copy(state = WorkflowEffectLifecycle.RESOLVED, resolution = resolution))
    }

    @Synchronized
    override fun record(id: WorkflowEffectId): WorkflowEffectRecord? = records[id]

    private fun get(id: WorkflowEffectId): WorkflowEffectRecord = records[id] ?: error("Unknown effect")

    private fun save(record: WorkflowEffectRecord): WorkflowEffectRecord {
        records[record.intent.id] = record
        return record
    }
}

private fun requireEffectValue(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_EFFECT_VALUE_CHARS) { "$label is invalid" }
}

private const val MAX_EFFECT_VALUE_CHARS = 512
private const val MAX_EFFECT_ATTEMPTS = 32
