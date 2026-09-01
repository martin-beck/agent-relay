package dev.agentrelay.session.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SessionEventUpdate(
    val locator: SessionLocator,
    val observation: SessionObservation? = null,
    val transcriptEntry: CachedTranscriptEntry? = null,
    val activity: SessionActivity? = null,
) {
    init {
        require(observation == null || observation.locator == locator) {
            "Event observation locator does not match"
        }
        require(activity == null || activity.locator == locator) {
            "Event activity locator does not match"
        }
        require(observation != null || transcriptEntry != null || activity != null) {
            "Session event update must contain a durable change"
        }
    }
}

interface SessionHubStore {
    suspend fun load(): SessionHubSnapshot

    suspend fun save(snapshot: SessionHubSnapshot)
}

interface SessionHubRepository {
    val snapshot: StateFlow<SessionHubSnapshot>

    suspend fun upsertSession(observation: SessionObservation)

    suspend fun setPreferences(
        locator: SessionLocator,
        preferences: SessionPreferences,
    )

    suspend fun applyEvent(update: SessionEventUpdate)

    suspend fun updateDraft(
        locator: SessionLocator,
        draft: SessionDraft,
    )

    suspend fun recordActivity(activity: SessionActivity)

    suspend fun markSessionRead(
        locator: SessionLocator,
        throughEpochMillis: Long = Long.MAX_VALUE,
    )

    suspend fun resolveActivity(
        locator: SessionLocator,
        activityId: String,
    )

    suspend fun cacheTranscript(
        locator: SessionLocator,
        entries: List<CachedTranscriptEntry>,
    )

    suspend fun removeSession(locator: SessionLocator)
}

class PersistentSessionHubRepository private constructor(
    initialSnapshot: SessionHubSnapshot,
    private val store: SessionHubStore,
    private val retentionPolicy: SessionRetentionPolicy,
) : SessionHubRepository {
    private val mutex = Mutex()
    private val mutableSnapshot = MutableStateFlow(initialSnapshot)

    override val snapshot: StateFlow<SessionHubSnapshot> = mutableSnapshot.asStateFlow()

    override suspend fun upsertSession(observation: SessionObservation) {
        mutate { current ->
            val existing = current.session(observation.locator)
            val updated = if (existing == null) {
                SessionRecord(observation)
            } else {
                existing.copy(observation = observation)
            }
            current.copy(
                sessions = current.sessions.filterNot { it.locator == observation.locator } + updated,
            )
        }
    }

    override suspend fun applyEvent(update: SessionEventUpdate) {
        mutate { current ->
            if (update.activity != null && current.activities.any {
                    it.locator == update.activity.locator && it.id == update.activity.id
                }
            ) {
                return@mutate current
            }
            val existing = current.session(update.locator)
            require(existing != null || update.observation != null) {
                "An event for an unknown session requires an observation"
            }
            val updatedRecord = when {
                update.observation != null && existing != null ->
                    existing.copy(observation = update.observation)
                update.observation != null -> SessionRecord(update.observation)
                else -> checkNotNull(existing)
            }
            val updatedSessions = current.sessions
                .filterNot { it.locator == update.locator } + updatedRecord

            val updatedTranscript = update.transcriptEntry?.let { entry ->
                val existingEntries = current.transcripts[update.locator].orEmpty()
                existingEntries.filterNot { it.id == entry.id } + entry
            }
            val updatedTranscripts = if (updatedTranscript == null) {
                current.transcripts
            } else {
                current.transcripts + (update.locator to updatedTranscript)
            }

            val updatedActivities = update.activity?.let { activity ->
                if (current.activities.any {
                        it.locator == activity.locator && it.id == activity.id
                    }
                ) {
                    current.activities
                } else {
                    current.activities + activity
                }
            } ?: current.activities

            val next = current.copy(
                sessions = updatedSessions,
                activities = updatedActivities,
                transcripts = updatedTranscripts,
            )
            if (next == current) current else next
        }
    }

    override suspend fun setPreferences(
        locator: SessionLocator,
        preferences: SessionPreferences,
    ) {
        mutate { current ->
            current.requireSession(locator)
            current.copy(
                sessions = current.sessions.map {
                    if (it.locator == locator) it.copy(preferences = preferences) else it
                },
            )
        }
    }

    override suspend fun updateDraft(
        locator: SessionLocator,
        draft: SessionDraft,
    ) {
        mutate { current ->
            current.requireSession(locator)
            current.copy(drafts = current.drafts + (locator to draft))
        }
    }

    override suspend fun recordActivity(activity: SessionActivity) {
        mutate { current ->
            current.requireSession(activity.locator)
            if (current.activities.any { it.locator == activity.locator && it.id == activity.id }) {
                current
            } else {
                current.copy(activities = current.activities + activity)
            }
        }
    }

    override suspend fun markSessionRead(
        locator: SessionLocator,
        throughEpochMillis: Long,
    ) {
        require(throughEpochMillis >= 0L) { "Read boundary must not be negative" }
        mutate { current ->
            current.requireSession(locator)
            current.copy(
                activities = current.activities.map {
                    if (it.locator == locator && it.occurredAtEpochMillis <= throughEpochMillis) {
                        it.copy(isRead = true)
                    } else {
                        it
                    }
                },
            )
        }
    }

    override suspend fun resolveActivity(
        locator: SessionLocator,
        activityId: String,
    ) {
        mutate { current ->
            val activity = current.activities.firstOrNull { it.locator == locator && it.id == activityId }
                ?: throw NoSuchElementException("No session activity found")
            require(activity.requiresAction) { "Session activity is not awaiting an action" }
            current.copy(
                activities = current.activities.map {
                    if (it.locator == locator && it.id == activityId) it.copy(isResolved = true) else it
                },
            )
        }
    }

    override suspend fun cacheTranscript(
        locator: SessionLocator,
        entries: List<CachedTranscriptEntry>,
    ) {
        require(entries.distinctBy { it.id }.size == entries.size) {
            "Transcript update contains duplicate entry ids"
        }
        mutate { current ->
            current.requireSession(locator)
            current.copy(transcripts = current.transcripts + (locator to entries))
        }
    }

    override suspend fun removeSession(locator: SessionLocator) {
        mutate { current ->
            if (current.session(locator) == null) {
                current
            } else {
                current.copy(
                    sessions = current.sessions.filterNot { it.locator == locator },
                    drafts = current.drafts - locator,
                    activities = current.activities.filterNot { it.locator == locator },
                    transcripts = current.transcripts - locator,
                )
            }
        }
    }

    private suspend fun mutate(transform: (SessionHubSnapshot) -> SessionHubSnapshot) {
        mutex.withLock {
            val previous = mutableSnapshot.value
            val transformed = transform(previous)
            if (transformed === previous) {
                return
            }
            val next = transformed.normalized(retentionPolicy)
            store.save(next)
            mutableSnapshot.value = next
        }
    }

    companion object {
        suspend fun open(
            store: SessionHubStore,
            retentionPolicy: SessionRetentionPolicy = SessionRetentionPolicy(),
        ): PersistentSessionHubRepository {
            val restored = store.load()
            val normalized = restored.normalized(retentionPolicy)
            if (normalized != restored) {
                store.save(normalized)
            }
            return PersistentSessionHubRepository(normalized, store, retentionPolicy)
        }
    }
}

class InMemorySessionHubStore(
    initialSnapshot: SessionHubSnapshot = SessionHubSnapshot(),
) : SessionHubStore {
    private val mutex = Mutex()
    private var persisted = initialSnapshot

    override suspend fun load(): SessionHubSnapshot = mutex.withLock { persisted }

    override suspend fun save(snapshot: SessionHubSnapshot) {
        mutex.withLock {
            persisted = snapshot
        }
    }
}

private fun SessionHubSnapshot.requireSession(locator: SessionLocator): SessionRecord =
    session(locator) ?: throw NoSuchElementException("No session found for the supplied locator")
