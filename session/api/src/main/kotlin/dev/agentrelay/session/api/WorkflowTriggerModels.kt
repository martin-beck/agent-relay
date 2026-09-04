package dev.agentrelay.session.api

/** The bounded sources from which a workflow may be requested. */
enum class WorkflowTriggerSource { MANUAL, SCHEDULED, EVENT }

enum class TriggerAdmission { ADMITTED, DUPLICATE, DELAYED, REJECTED }

data class WorkflowTriggerRequest(
    val id: String,
    val workflowId: WorkflowId,
    val source: WorkflowTriggerSource,
    val sourceKey: String,
    val requestedAtEpochMillis: Long,
    val correlationId: String,
    val causationId: String? = null,
    val payload: Map<String, String> = emptyMap(),
) {
    val deduplicationKey: String = "$workflowId|$source|$sourceKey"

    init {
        requireTriggerText(id, "Trigger id")
        requireTriggerText(sourceKey, "Trigger source key")
        requireTriggerText(correlationId, "Trigger correlation id")
        causationId?.let { requireTriggerText(it, "Trigger causation id") }
        require(requestedAtEpochMillis >= 0) { "Trigger timestamp must not be negative" }
        require(payload.size <= MAX_TRIGGER_FIELDS) { "Trigger payload is too large" }
        payload.forEach { (key, value) ->
            requireTriggerText(key, "Trigger payload key")
            require(value.length <= MAX_TRIGGER_VALUE_CHARS) { "Trigger payload value is too large" }
        }
    }
}

data class TriggerAdmissionPolicy(
    val enabledSources: Set<WorkflowTriggerSource> = WorkflowTriggerSource.entries.toSet(),
    val deduplicationWindowMillis: Long = 24 * 60 * 60 * 1000,
    val maxPayloadFields: Int = MAX_TRIGGER_FIELDS,
    val maxPending: Int = 128,
    val maxCatchUp: Int = 8,
) {
    init {
        require(deduplicationWindowMillis > 0)
        require(maxPayloadFields in 0..MAX_TRIGGER_FIELDS)
        require(maxPending > 0)
        require(maxCatchUp in 1..MAX_CATCH_UP)
    }
}

data class TriggerReceipt(
    val request: WorkflowTriggerRequest,
    val admission: TriggerAdmission,
    val observedAtEpochMillis: Long,
    val explanation: String,
) {
    init {
        require(observedAtEpochMillis >= request.requestedAtEpochMillis)
        requireTriggerText(explanation, "Trigger explanation")
    }
}

/** A durable admission boundary. Implementations must atomically deduplicate and record receipts. */
interface WorkflowTriggerStore {
    fun admit(request: WorkflowTriggerRequest, nowEpochMillis: Long, policy: TriggerAdmissionPolicy): TriggerReceipt
    fun receipts(): List<TriggerReceipt>
    fun pendingCount(): Int
}

class InMemoryWorkflowTriggerStore : WorkflowTriggerStore {
    private val records = linkedMapOf<String, TriggerReceipt>()

    @Synchronized
    override fun admit(
        request: WorkflowTriggerRequest,
        nowEpochMillis: Long,
        policy: TriggerAdmissionPolicy,
    ): TriggerReceipt {
        require(nowEpochMillis >= 0) { "Observation timestamp must not be negative" }
        require(request.payload.size <= policy.maxPayloadFields) { "Trigger payload exceeds policy" }
        records[request.deduplicationKey]?.let { prior ->
            if (nowEpochMillis - prior.observedAtEpochMillis <= policy.deduplicationWindowMillis) {
                return receipt(request, TriggerAdmission.DUPLICATE, nowEpochMillis, "duplicate trigger suppressed")
            }
        }
        if (request.source !in policy.enabledSources) {
            return receipt(request, TriggerAdmission.REJECTED, nowEpochMillis, "trigger source is disabled")
        }
        if (pendingCount() >= policy.maxPending) {
            return receipt(request, TriggerAdmission.DELAYED, nowEpochMillis, "pending trigger limit reached")
        }
        val accepted = receipt(request, TriggerAdmission.ADMITTED, nowEpochMillis, "trigger admitted")
        records[request.deduplicationKey] = accepted
        return accepted
    }

    @Synchronized override fun receipts(): List<TriggerReceipt> = records.values.toList()

    @Synchronized override fun pendingCount(): Int = records.values.count { it.admission == TriggerAdmission.ADMITTED }

    private fun receipt(
        request: WorkflowTriggerRequest,
        admission: TriggerAdmission,
        nowEpochMillis: Long,
        explanation: String,
    ) = TriggerReceipt(request, admission, nowEpochMillis, explanation)
}

data class ScheduleCursor(
    val scheduleId: String,
    val nextDueEpochMillis: Long,
    val intervalMillis: Long,
    val lastEmittedEpochMillis: Long? = null,
) {
    init {
        requireTriggerText(scheduleId, "Schedule id")
        require(nextDueEpochMillis >= 0 && intervalMillis > 0)
        require(lastEmittedEpochMillis == null || lastEmittedEpochMillis < nextDueEpochMillis)
    }

    /** Advances from a due instant, bounding missed occurrences after clock jumps or downtime. */
    fun dueOccurrences(nowEpochMillis: Long, maxCatchUp: Int): List<Long> {
        require(nowEpochMillis >= 0)
        require(maxCatchUp in 1..MAX_CATCH_UP)
        if (nowEpochMillis < nextDueEpochMillis) return emptyList()
        return generateSequence(nextDueEpochMillis) { due -> due + intervalMillis }
            .takeWhile { it <= nowEpochMillis }
            .take(maxCatchUp)
            .toList()
    }

    fun advancedThrough(occurrenceEpochMillis: Long): ScheduleCursor {
        require(occurrenceEpochMillis >= nextDueEpochMillis)
        return copy(
            nextDueEpochMillis = occurrenceEpochMillis + intervalMillis,
            lastEmittedEpochMillis = occurrenceEpochMillis,
        )
    }
}

private fun requireTriggerText(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_TRIGGER_TEXT_CHARS) { "$label is invalid" }
}

private const val MAX_TRIGGER_TEXT_CHARS = 512
private const val MAX_TRIGGER_FIELDS = 64
private const val MAX_TRIGGER_VALUE_CHARS = 4_096
private const val MAX_CATCH_UP = 128
