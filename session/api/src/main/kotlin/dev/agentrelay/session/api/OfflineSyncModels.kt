package dev.agentrelay.session.api

/** An opaque, monotonic position in a daemon event stream. */
data class SessionSyncCursor(
    val streamId: String,
    val sequence: Long,
    val schemaVersion: Int = CURRENT_SYNC_SCHEMA,
) {
    init {
        require(streamId.isNotBlank() && '|' !in streamId) { "Sync stream id is invalid" }
        require(sequence >= 0L) { "Sync cursor sequence must not be negative" }
        require(schemaVersion == CURRENT_SYNC_SCHEMA) { "Unsupported sync cursor schema" }
    }

    fun encode(): String = "$schemaVersion|$streamId|$sequence"

    fun next(): SessionSyncCursor = copy(sequence = sequence + 1L)

    companion object {
        fun decode(encoded: String): SessionSyncCursor {
            val parts = encoded.split('|')
            require(parts.size == 3) { "Sync cursor encoding is invalid" }
            return SessionSyncCursor(
                streamId = parts[1],
                sequence = parts[2].toLongOrNull() ?: error("Sync cursor sequence is invalid"),
                schemaVersion = parts[0].toIntOrNull() ?: error("Sync cursor schema is invalid"),
            )
        }

        fun migrateLegacy(streamId: String, legacySequence: Long): SessionSyncCursor =
            SessionSyncCursor(streamId, legacySequence)
    }
}

data class SessionSyncEvent(
    val cursor: SessionSyncCursor,
    val update: SessionEventUpdate,
) {
    init {
        require(cursor.schemaVersion == CURRENT_SYNC_SCHEMA)
    }
}

data class SessionSyncBatch(
    val streamId: String,
    val base: SessionSyncCursor,
    val events: List<SessionSyncEvent>,
) {
    init {
        require(base.streamId == streamId) { "Sync batch base stream does not match" }
        require(events.size <= MAX_SYNC_BATCH) { "Sync batch is too large" }
        require(events.zipWithNext().all { (left, right) -> left.cursor.sequence < right.cursor.sequence }) {
            "Sync batch events must be ordered"
        }
        require(events.all { it.cursor.streamId == streamId }) {
            "Sync batch event stream does not match"
        }
    }
}

data class OfflineSessionProjection(
    val snapshot: SessionHubSnapshot,
    val cursor: SessionSyncCursor,
)

sealed interface SessionSyncResult {
    data class Applied(val projection: OfflineSessionProjection) : SessionSyncResult

    data class Duplicate(val projection: OfflineSessionProjection) : SessionSyncResult

    data class Gap(
        val expected: SessionSyncCursor,
        val received: SessionSyncCursor,
    ) : SessionSyncResult
}

/**
 * Applies ordered host updates through the durable repository. Gaps never advance the
 * cursor, so reconnect recovery can request a range or a verified snapshot without
 * fabricating completion locally.
 */
class OfflineSessionSyncCoordinator(
    private val repository: SessionHubRepository,
    initial: OfflineSessionProjection,
) {
    private var projection = initial

    fun projection(): OfflineSessionProjection = projection.copy(snapshot = repository.snapshot.value)

    suspend fun apply(batch: SessionSyncBatch): SessionSyncResult {
        require(batch.base == projection.cursor) { "Sync batch does not start at the cached cursor" }
        if (batch.events.isEmpty()) return SessionSyncResult.Duplicate(projection())
        var next = projection.cursor
        for (event in batch.events) {
            if (event.cursor.sequence <= next.sequence) continue
            if (event.cursor.sequence != next.sequence + 1L) {
                return SessionSyncResult.Gap(next.next(), event.cursor)
            }
            repository.applyEvent(event.update)
            next = event.cursor
            val prior = repository.snapshot.value.recovery[event.update.locator] ?: SessionRecoveryState()
            repository.updateRecoveryState(
                event.update.locator,
                prior.copy(eventCursor = next.encode()),
            )
        }
        projection = OfflineSessionProjection(repository.snapshot.value, next)
        return SessionSyncResult.Applied(projection)
    }
}

private const val CURRENT_SYNC_SCHEMA = 1
private const val MAX_SYNC_BATCH = 256
