package dev.agentrelay.session.api

import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.AgentTranscriptRole

data class SessionLocator(
    val connectionProviderId: ConnectionProviderId,
    val connectionProfileId: ConnectionProfileId,
    val agentProviderId: AgentProviderId,
    val agentSessionId: AgentSessionId,
) {
    val stableKey: String
        get() = listOf(
            connectionProviderId.value,
            connectionProfileId.value,
            agentProviderId.value,
            agentSessionId.value,
        ).joinToString(separator = "") { value -> "${value.length}:$value" }
}

data class SessionObservation(
    val locator: SessionLocator,
    val connectionLabel: String,
    val connectionTarget: String,
    val projectPath: String?,
    val agentProviderLabel: String,
    val title: String?,
    val preview: String,
    val agentState: AgentSessionState,
    val createdAtEpochMillis: Long?,
    val updatedAtEpochMillis: Long?,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        requireBounded(connectionLabel, "Connection label", MAX_LABEL_CHARS)
        requireBounded(connectionTarget, "Connection target", MAX_PATH_CHARS)
        projectPath?.let { requireBounded(it, "Project path", MAX_PATH_CHARS) }
        requireBounded(agentProviderLabel, "Agent provider label", MAX_LABEL_CHARS)
        title?.let { requireBounded(it, "Session title", MAX_TITLE_CHARS) }
        require(preview.length <= MAX_PREVIEW_CHARS) { "Session preview is too large" }
        require(createdAtEpochMillis == null || createdAtEpochMillis >= 0L)
        require(updatedAtEpochMillis == null || updatedAtEpochMillis >= 0L)
        validateMetadata(metadata)
    }
}

enum class SessionNotificationPriority {
    ALL_ACTIVITY,
    IMPORTANT_ONLY,
    FINAL_OUTPUT_ONLY,
    MUTED,
}

data class SessionPreferences(
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val notificationPriority: SessionNotificationPriority = SessionNotificationPriority.IMPORTANT_ONLY,
)

data class SessionRecord(
    val observation: SessionObservation,
    val preferences: SessionPreferences = SessionPreferences(),
    val unreadCount: Int = 0,
    val lastActivityAtEpochMillis: Long? = observation.updatedAtEpochMillis,
) {
    init {
        require(unreadCount >= 0) { "Unread count must not be negative" }
        require(lastActivityAtEpochMillis == null || lastActivityAtEpochMillis >= 0L)
    }

    val locator: SessionLocator
        get() = observation.locator
}

data class SessionDraft(
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(text.length <= MAX_DRAFT_CHARS) { "Session draft is too large" }
        require(selectionStart in 0..text.length) { "Draft selection start is invalid" }
        require(selectionEnd in selectionStart..text.length) { "Draft selection end is invalid" }
        require(updatedAtEpochMillis >= 0L)
    }
}

enum class SessionActivityType {
    NEW_OUTPUT,
    APPROVAL_REQUIRED,
    QUESTION,
    FAILURE,
    RECONNECTED,
    TURN_COMPLETED,
}

data class SessionActivity(
    val id: String,
    val locator: SessionLocator,
    val type: SessionActivityType,
    val summary: String,
    val eventAnchorId: String?,
    val occurredAtEpochMillis: Long,
    val isRead: Boolean = false,
    val isResolved: Boolean = false,
) {
    init {
        requireBounded(id, "Activity id", MAX_ID_CHARS)
        requireBounded(summary, "Activity summary", MAX_ACTIVITY_CHARS)
        eventAnchorId?.let { requireBounded(it, "Event anchor id", MAX_ID_CHARS) }
        require(occurredAtEpochMillis >= 0L)
        require(!isResolved || type == SessionActivityType.APPROVAL_REQUIRED || type == SessionActivityType.QUESTION) {
            "Only actionable activity can be resolved"
        }
    }

    val requiresAction: Boolean
        get() = !isResolved &&
            (type == SessionActivityType.APPROVAL_REQUIRED || type == SessionActivityType.QUESTION)
}

data class CachedTranscriptEntry(
    val id: String,
    val turnId: String?,
    val role: AgentTranscriptRole,
    val channel: AgentMessageChannel?,
    val text: String,
    val createdAtEpochMillis: Long?,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        requireBounded(id, "Transcript entry id", MAX_ID_CHARS)
        turnId?.let { requireBounded(it, "Turn id", MAX_ID_CHARS) }
        require(text.length <= MAX_TRANSCRIPT_ENTRY_CHARS) { "Transcript entry is too large" }
        require(createdAtEpochMillis == null || createdAtEpochMillis >= 0L)
        validateMetadata(metadata)
    }
}

data class SessionHubSnapshot(
    val sessions: List<SessionRecord> = emptyList(),
    val drafts: Map<SessionLocator, SessionDraft> = emptyMap(),
    val activities: List<SessionActivity> = emptyList(),
    val transcripts: Map<SessionLocator, List<CachedTranscriptEntry>> = emptyMap(),
) {
    init {
        require(sessions.distinctBy { it.locator }.size == sessions.size) {
            "Session snapshot contains duplicate sessions"
        }
        require(activities.distinctBy { it.locator to it.id }.size == activities.size) {
            "Session snapshot contains duplicate activity identities"
        }
        val locators = sessions.mapTo(mutableSetOf()) { it.locator }
        require(drafts.keys.all(locators::contains)) { "A draft references an unknown session" }
        require(activities.all { it.locator in locators }) { "Activity references an unknown session" }
        require(transcripts.keys.all(locators::contains)) { "A transcript references an unknown session" }
        require(
            transcripts.values.all { entries ->
                entries.distinctBy { it.id }.size == entries.size
            },
        ) { "A transcript contains duplicate entry ids" }
    }

    fun session(locator: SessionLocator): SessionRecord? = sessions.firstOrNull { it.locator == locator }

    fun recentSessions(includeArchived: Boolean = false): List<SessionRecord> =
        sessions
            .asSequence()
            .filter { includeArchived || !it.preferences.archived }
            .sortedWith(
                compareByDescending<SessionRecord> { it.preferences.pinned }
                    .thenByDescending { it.lastActivityAtEpochMillis ?: Long.MIN_VALUE }
                    .thenBy { it.observation.title ?: it.observation.agentProviderLabel },
            )
            .toList()

    fun inbox(includeRead: Boolean = false): List<SessionActivity> =
        activities
            .asSequence()
            .filter { includeRead || !it.isRead || it.requiresAction }
            .sortedWith(
                compareByDescending<SessionActivity> { it.requiresAction }
                    .thenByDescending { it.occurredAtEpochMillis },
            )
            .toList()

    val totalUnread: Int
        get() = sessions.sumOf(SessionRecord::unreadCount)
}

data class SessionRetentionPolicy(
    val maximumSessions: Int = 200,
    val maximumActivities: Int = 2_000,
    val maximumTranscriptEntriesPerSession: Int = 500,
) {
    init {
        require(maximumSessions > 0)
        require(maximumActivities > 0)
        require(maximumTranscriptEntriesPerSession > 0)
    }
}

internal fun SessionHubSnapshot.normalized(policy: SessionRetentionPolicy): SessionHubSnapshot {
    val retainedSessions = sessions
        .sortedWith(
            compareByDescending<SessionRecord> { it.preferences.pinned }
                .thenByDescending { !it.preferences.archived }
                .thenByDescending { it.lastActivityAtEpochMillis ?: Long.MIN_VALUE },
        )
        .take(policy.maximumSessions)
    val retainedLocators = retainedSessions.mapTo(mutableSetOf()) { it.locator }
    val retainedActivities = activities
        .asSequence()
        .filter { it.locator in retainedLocators }
        .sortedWith(
            compareByDescending<SessionActivity> { it.requiresAction }
                .thenByDescending { it.occurredAtEpochMillis },
        )
        .take(policy.maximumActivities)
        .sortedBy { it.occurredAtEpochMillis }
        .toList()
    val unreadBySession = retainedActivities
        .asSequence()
        .filterNot(SessionActivity::isRead)
        .groupingBy(SessionActivity::locator)
        .eachCount()
    val newestActivityBySession = retainedActivities
        .groupBy(SessionActivity::locator)
        .mapValues { (_, values) -> values.maxOf(SessionActivity::occurredAtEpochMillis) }
    val normalizedSessions = retainedSessions.map { session ->
        session.copy(
            unreadCount = unreadBySession[session.locator] ?: 0,
            lastActivityAtEpochMillis = maxOfNullable(
                session.observation.updatedAtEpochMillis,
                newestActivityBySession[session.locator],
            ),
        )
    }
    val normalizedTranscripts = transcripts
        .filterKeys(retainedLocators::contains)
        .mapValues { (_, entries) ->
            entries
                .distinctBy(CachedTranscriptEntry::id)
                .takeLast(policy.maximumTranscriptEntriesPerSession)
        }
    return SessionHubSnapshot(
        sessions = normalizedSessions,
        drafts = drafts.filterKeys(retainedLocators::contains),
        activities = retainedActivities,
        transcripts = normalizedTranscripts,
    )
}

private fun requireBounded(value: String, label: String, maximum: Int) {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value.length <= maximum) { "$label is too large" }
}

private fun validateMetadata(metadata: Map<String, String>) {
    require(metadata.size <= MAX_METADATA_ENTRIES) { "Session metadata has too many entries" }
    require(
        metadata.all { (key, value) ->
            key.isNotBlank() &&
                key.length <= MAX_METADATA_KEY_CHARS &&
                value.length <= MAX_METADATA_VALUE_CHARS
        },
    ) { "Session metadata is invalid or too large" }
}

private fun maxOfNullable(first: Long?, second: Long?): Long? = when {
    first == null -> second
    second == null -> first
    else -> maxOf(first, second)
}

private const val MAX_ID_CHARS = 512
private const val MAX_LABEL_CHARS = 256
private const val MAX_TITLE_CHARS = 1_024
private const val MAX_PATH_CHARS = 4_096
private const val MAX_PREVIEW_CHARS = 16_384
private const val MAX_DRAFT_CHARS = 256 * 1024
private const val MAX_ACTIVITY_CHARS = 16_384
private const val MAX_TRANSCRIPT_ENTRY_CHARS = 1024 * 1024
private const val MAX_METADATA_ENTRIES = 64
private const val MAX_METADATA_KEY_CHARS = 256
private const val MAX_METADATA_VALUE_CHARS = 4_096
