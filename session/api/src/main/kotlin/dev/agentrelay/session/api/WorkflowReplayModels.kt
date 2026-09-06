package dev.agentrelay.session.api

@JvmInline
value class ReplayHistoryId(val value: String) {
    init {
        requireReplayValue(value, "Replay history id")
    }
}

@JvmInline
value class ReplayForkId(val value: String) {
    init {
        requireReplayValue(value, "Replay fork id")
    }
}

enum class ReplayBoundary { WORKFLOW, STEP, ATTEMPT, COMMAND, APPROVAL, EFFECT, OUTCOME }

data class ReplayEvent(
    val sequence: Long,
    val eventId: String,
    val boundary: ReplayBoundary,
    val payloadDigest: String,
    val externalEffect: Boolean = false,
) {
    init {
        require(sequence >= 0) { "Replay sequence must not be negative" }
        requireReplayValue(eventId, "Replay event id")
        requireReplayValue(payloadDigest, "Replay payload digest")
    }
}

data class ReplayHistory(
    val id: ReplayHistoryId,
    val schemaVersion: Int,
    val workflowId: WorkflowId,
    val events: List<ReplayEvent>,
) {
    init {
        require(schemaVersion > 0) { "Replay schema version must be positive" }
        require(events.isNotEmpty()) { "Replay history must not be empty" }
        require(events.map { it.eventId }.toSet().size == events.size) { "Replay event ids must be unique" }
        require(events.map { it.sequence } == events.indices.map(Int::toLong)) {
            "Replay history is truncated or out of sequence"
        }
    }
}

data class ReplayCompatibility(
    val compatible: Boolean,
    val boundary: ReplayBoundary?,
    val reason: String,
)

data class ReplayFork(
    val id: ReplayForkId,
    val source: ReplayHistoryId,
    val checkpointSequence: Long,
    val seed: Long,
    val syntheticObservations: Map<String, String> = emptyMap(),
) {
    init {
        require(checkpointSequence >= 0) { "Fork checkpoint must not be negative" }
        require(syntheticObservations.keys.all { it.isNotBlank() }) { "Fork observation keys are invalid" }
        require(syntheticObservations.values.all { it.isNotBlank() }) { "Fork observation values are invalid" }
    }
}

class WorkflowReplay private constructor(private val history: ReplayHistory) {
    fun compatibility(currentSchema: Int, supportedBoundaries: Set<ReplayBoundary>): ReplayCompatibility {
        if (currentSchema < history.schemaVersion) {
            return ReplayCompatibility(false, null, "history-schema-newer-than-runtime")
        }
        val unsupported = history.events.firstOrNull { it.boundary !in supportedBoundaries }
        return if (unsupported == null) {
            ReplayCompatibility(true, null, "compatible")
        } else {
            ReplayCompatibility(false, unsupported.boundary, "unsupported-boundary:${unsupported.eventId}")
        }
    }

    fun fork(sequence: Long, id: ReplayForkId, seed: Long, observations: Map<String, String> = emptyMap()): ReplayFork {
        require(sequence in history.events.indices.map(Int::toLong)) { "Fork checkpoint is not in history" }
        return ReplayFork(id, history.id, sequence, seed, observations.toSortedMap())
    }

    fun replayedEvents(until: Long? = null): List<ReplayEvent> = history.events.takeWhile { until == null || it.sequence <= until }

    companion object {
        fun from(history: ReplayHistory): WorkflowReplay = WorkflowReplay(history)
    }
}

private fun requireReplayValue(value: String, label: String) {
    require(value.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "$label is invalid" }
}
