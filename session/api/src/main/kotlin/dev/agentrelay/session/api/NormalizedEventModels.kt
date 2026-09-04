package dev.agentrelay.session.api

enum class NormalizedEventKind {
    WORKFLOW,
    COMMAND,
    CHECK,
    ARTIFACT,
    PERMISSION,
    CONNECTION,
    CAPABILITY,
    BUDGET,
    ATTENTION,
}

enum class EventSensitivity { PUBLIC, INTERNAL, SENSITIVE, SECRET }

data class EventCausation(
    val correlationId: String,
    val causationId: String? = null,
    val actor: String,
) {
    init {
        requireEventId(correlationId, "Correlation id")
        causationId?.let { requireEventId(it, "Causation id") }
        requireEventId(actor, "Event actor")
    }
}

data class NormalizedEvent(
    val id: String,
    val schemaVersion: Int,
    val streamId: String,
    val sequence: Long,
    val kind: NormalizedEventKind,
    val sensitivity: EventSensitivity,
    val causation: EventCausation,
    val payload: Map<String, String>,
) {
    init {
        requireEventId(id, "Event id")
        require(schemaVersion in 1..MAX_EVENT_SCHEMA_VERSION) { "Event schema version is invalid" }
        requireEventId(streamId, "Event stream id")
        require(sequence > 0) { "Event sequence must be positive" }
        require(payload.size <= MAX_EVENT_FIELDS) { "Event payload is too large" }
        payload.forEach { (key, value) ->
            requireEventId(key, "Event payload key")
            require(value.length <= MAX_EVENT_VALUE_CHARS) { "Event payload value is too large" }
        }
        if (sensitivity == EventSensitivity.SECRET) {
            error("Secret event payloads must not enter the normalized event contract")
        }
    }
}

data class EventCursor(val streamId: String, val sequence: Long, val eventId: String) {
    init {
        requireEventId(streamId, "Cursor stream id")
        require(sequence >= 0) { "Cursor sequence must not be negative" }
        requireEventId(eventId, "Cursor event id")
    }
}

enum class ReplayDecision { APPLY, DUPLICATE, GAP }

fun NormalizedEvent.replayDecision(cursor: EventCursor?): ReplayDecision {
    if (cursor == null) return ReplayDecision.APPLY
    require(cursor.streamId == streamId) { "Event stream does not match cursor" }
    return when {
        sequence == cursor.sequence + 1 -> ReplayDecision.APPLY
        sequence <= cursor.sequence -> ReplayDecision.DUPLICATE
        else -> ReplayDecision.GAP
    }
}

data class ProjectionCompatibility(
    val eventKind: NormalizedEventKind,
    val minimumSchemaVersion: Int,
    val maximumSchemaVersion: Int,
    val projectionVersion: Int,
) {
    init {
        require(minimumSchemaVersion in 1..maximumSchemaVersion)
        require(maximumSchemaVersion <= MAX_EVENT_SCHEMA_VERSION)
        require(projectionVersion > 0)
    }

    fun accepts(event: NormalizedEvent): Boolean =
        event.kind == eventKind && event.schemaVersion in minimumSchemaVersion..maximumSchemaVersion
}

private fun requireEventId(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_EVENT_ID_CHARS) { "$label is invalid" }
}

private const val MAX_EVENT_ID_CHARS = 512
private const val MAX_EVENT_FIELDS = 64
private const val MAX_EVENT_VALUE_CHARS = 4_096
private const val MAX_EVENT_SCHEMA_VERSION = 16
