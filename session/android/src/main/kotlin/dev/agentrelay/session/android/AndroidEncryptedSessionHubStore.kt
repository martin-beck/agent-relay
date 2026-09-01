package dev.agentrelay.session.android

import android.content.Context
import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.session.api.CachedTranscriptEntry
import dev.agentrelay.session.api.SessionActivity
import dev.agentrelay.session.api.SessionActionRequest
import dev.agentrelay.session.api.SessionDraft
import dev.agentrelay.session.api.SessionHubSnapshot
import dev.agentrelay.session.api.SessionHubStore
import dev.agentrelay.session.api.SessionLocator
import dev.agentrelay.session.api.SessionObservation
import dev.agentrelay.session.api.SessionPreferences
import dev.agentrelay.session.api.SessionRecord
import dev.agentrelay.storage.android.EncryptedFileDocumentStore
import dev.agentrelay.storage.android.SecureDocumentNamespace
import dev.agentrelay.storage.android.SecureDocumentStore
import dev.agentrelay.storage.android.SecureStoreCorruptException
import dev.agentrelay.storage.android.SecureStoreException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Arrays

class AndroidEncryptedSessionHubStore internal constructor(
    private val documents: SecureDocumentStore,
    private val json: Json = sessionStoreJson(),
) : SessionHubStore {
    constructor(context: Context) : this(
        EncryptedFileDocumentStore(
            context = context.applicationContext,
            namespace = SESSION_HUB_NAMESPACE,
        ),
    )

    private val mutex = Mutex()

    override suspend fun load(): SessionHubSnapshot = mutex.withLock {
        val plaintext = documents.read(SESSION_HUB_DOCUMENT) ?: return@withLock SessionHubSnapshot()
        try {
            val document = json.decodeFromString(SessionHubDocument.serializer(), plaintext.decodeToString())
            check(document.formatVersion == SESSION_HUB_FORMAT_VERSION) {
                "Unsupported session hub document version"
            }
            document.toDomain()
        } catch (failure: SecureStoreException) {
            throw failure
        } catch (failure: Throwable) {
            throw SecureStoreCorruptException(failure)
        } finally {
            Arrays.fill(plaintext, 0)
        }
    }

    override suspend fun save(snapshot: SessionHubSnapshot) = mutex.withLock {
        val plaintext = json
            .encodeToString(SessionHubDocument.serializer(), snapshot.toDocument())
            .encodeToByteArray()
        try {
            documents.write(SESSION_HUB_DOCUMENT, plaintext)
        } finally {
            Arrays.fill(plaintext, 0)
        }
    }
}

internal val SESSION_HUB_NAMESPACE = SecureDocumentNamespace(
    directoryName = "session-secure-store",
    associatedDataPrefix = "agent-relay:session-store:v1",
    keyAlias = "agent-relay.session.secure-store.v1",
)

private const val SESSION_HUB_DOCUMENT = "session-hub-v1"
private const val SESSION_HUB_FORMAT_VERSION = 1

private fun sessionStoreJson() = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    explicitNulls = true
}

@Serializable
private data class SessionHubDocument(
    val formatVersion: Int,
    val sessions: List<SessionRecordDocument>,
    val drafts: List<SessionDraftDocument>,
    val activities: List<SessionActivityDocument>,
    val transcripts: List<SessionTranscriptDocument>,
    val actionRequests: List<SessionActionRequestDocument> = emptyList(),
)

@Serializable
internal data class SessionLocatorDocument(
    val connectionProviderId: String,
    val connectionProfileId: String,
    val agentProviderId: String,
    val agentSessionId: String,
)

@Serializable
private data class SessionObservationDocument(
    val locator: SessionLocatorDocument,
    val connectionLabel: String,
    val connectionTarget: String,
    val projectPath: String?,
    val agentProviderLabel: String,
    val title: String?,
    val preview: String,
    val agentState: String,
    val createdAtEpochMillis: Long?,
    val updatedAtEpochMillis: Long?,
    val metadata: Map<String, String>,
)

@Serializable
private data class SessionPreferencesDocument(
    val pinned: Boolean,
    val archived: Boolean,
    val notificationPriority: String,
)

@Serializable
private data class SessionRecordDocument(
    val observation: SessionObservationDocument,
    val preferences: SessionPreferencesDocument,
    val unreadCount: Int,
    val lastActivityAtEpochMillis: Long?,
)

@Serializable
private data class SessionDraftDocument(
    val locator: SessionLocatorDocument,
    val text: String,
    val selectionStart: Int,
    val selectionEnd: Int,
    val updatedAtEpochMillis: Long,
)

@Serializable
private data class SessionActivityDocument(
    val id: String,
    val locator: SessionLocatorDocument,
    val type: String,
    val summary: String,
    val eventAnchorId: String?,
    val occurredAtEpochMillis: Long,
    val isRead: Boolean,
    val isResolved: Boolean,
    val actionRequestId: String? = null,
)

@Serializable
internal data class SessionQuestionOptionDocument(
    val label: String,
    val description: String?,
)

@Serializable
internal data class SessionQuestionDocument(
    val id: String,
    val providerQuestionId: String,
    val header: String?,
    val prompt: String,
    val options: List<SessionQuestionOptionDocument>,
    val allowsOther: Boolean,
    val allowsMultiple: Boolean,
)

@Serializable
internal data class SessionActionRequestDocument(
    val id: String,
    val providerApprovalId: String,
    val locator: SessionLocatorDocument,
    val turnId: String?,
    val type: String,
    val title: String,
    val description: String?,
    val command: String?,
    val workingDirectory: String?,
    val questions: List<SessionQuestionDocument>,
    val availableDecisions: List<String>,
    val riskReasons: List<String>,
    val receivedAtEpochMillis: Long,
    val state: String,
    val decision: String?,
    val answeredQuestionIds: List<String>,
    val additionalConfirmationGiven: Boolean,
    val decisionAtEpochMillis: Long?,
)

@Serializable
internal data class CachedTranscriptEntryDocument(
    val id: String,
    val turnId: String?,
    val role: String,
    val channel: String?,
    val text: String,
    val createdAtEpochMillis: Long?,
    val metadata: Map<String, String>,
)

@Serializable
private data class SessionTranscriptDocument(
    val locator: SessionLocatorDocument,
    val entries: List<CachedTranscriptEntryDocument>,
)

private fun SessionHubSnapshot.toDocument() = SessionHubDocument(
    formatVersion = SESSION_HUB_FORMAT_VERSION,
    sessions = sessions.map(SessionRecord::toDocument),
    drafts = drafts.map { (locator, draft) -> draft.toDocument(locator) },
    activities = activities.map(SessionActivity::toDocument),
    transcripts = transcripts.map { (locator, entries) ->
        SessionTranscriptDocument(
            locator = locator.toDocument(),
            entries = entries.map(CachedTranscriptEntry::toDocument),
        )
    },
    actionRequests = actionRequests.map(SessionActionRequest::toDocument),
)

private fun SessionHubDocument.toDomain(): SessionHubSnapshot {
    val restoredDrafts = drafts
        .requireUniqueBy(SessionDraftDocument::locator, "session draft")
        .associate { it.locator.toDomain() to it.toDomain() }
    val restoredTranscripts = transcripts
        .requireUniqueBy(SessionTranscriptDocument::locator, "session transcript")
        .associate { document ->
            document.locator.toDomain() to document.entries.map(CachedTranscriptEntryDocument::toDomain)
        }
    return SessionHubSnapshot(
        sessions = sessions.map(SessionRecordDocument::toDomain),
        drafts = restoredDrafts,
        activities = activities.map(SessionActivityDocument::toDomain),
        transcripts = restoredTranscripts,
        actionRequests = actionRequests.map(SessionActionRequestDocument::toDomain),
    )
}

internal fun SessionLocator.toDocument() = SessionLocatorDocument(
    connectionProviderId = connectionProviderId.value,
    connectionProfileId = connectionProfileId.value,
    agentProviderId = agentProviderId.value,
    agentSessionId = agentSessionId.value,
)

internal fun SessionLocatorDocument.toDomain() = SessionLocator(
    connectionProviderId = ConnectionProviderId(connectionProviderId),
    connectionProfileId = ConnectionProfileId(connectionProfileId),
    agentProviderId = AgentProviderId(agentProviderId),
    agentSessionId = AgentSessionId(agentSessionId),
)

private fun SessionObservation.toDocument() = SessionObservationDocument(
    locator = locator.toDocument(),
    connectionLabel = connectionLabel,
    connectionTarget = connectionTarget,
    projectPath = projectPath,
    agentProviderLabel = agentProviderLabel,
    title = title,
    preview = preview,
    agentState = agentState.name,
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    metadata = metadata,
)

private fun SessionObservationDocument.toDomain() = SessionObservation(
    locator = locator.toDomain(),
    connectionLabel = connectionLabel,
    connectionTarget = connectionTarget,
    projectPath = projectPath,
    agentProviderLabel = agentProviderLabel,
    title = title,
    preview = preview,
    agentState = enumValue(agentState),
    createdAtEpochMillis = createdAtEpochMillis,
    updatedAtEpochMillis = updatedAtEpochMillis,
    metadata = metadata,
)

private fun SessionPreferences.toDocument() = SessionPreferencesDocument(
    pinned = pinned,
    archived = archived,
    notificationPriority = notificationPriority.name,
)

private fun SessionPreferencesDocument.toDomain() = SessionPreferences(
    pinned = pinned,
    archived = archived,
    notificationPriority = enumValue(notificationPriority),
)

private fun SessionRecord.toDocument() = SessionRecordDocument(
    observation = observation.toDocument(),
    preferences = preferences.toDocument(),
    unreadCount = unreadCount,
    lastActivityAtEpochMillis = lastActivityAtEpochMillis,
)

private fun SessionRecordDocument.toDomain() = SessionRecord(
    observation = observation.toDomain(),
    preferences = preferences.toDomain(),
    unreadCount = unreadCount,
    lastActivityAtEpochMillis = lastActivityAtEpochMillis,
)

private fun SessionDraft.toDocument(locator: SessionLocator) = SessionDraftDocument(
    locator = locator.toDocument(),
    text = text,
    selectionStart = selectionStart,
    selectionEnd = selectionEnd,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun SessionDraftDocument.toDomain() = SessionDraft(
    text = text,
    selectionStart = selectionStart,
    selectionEnd = selectionEnd,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun SessionActivity.toDocument() = SessionActivityDocument(
    id = id,
    locator = locator.toDocument(),
    type = type.name,
    summary = summary,
    eventAnchorId = eventAnchorId,
    occurredAtEpochMillis = occurredAtEpochMillis,
    isRead = isRead,
    isResolved = isResolved,
    actionRequestId = actionRequestId,
)

private fun SessionActivityDocument.toDomain() = SessionActivity(
    id = id,
    locator = locator.toDomain(),
    type = enumValue(type),
    summary = summary,
    eventAnchorId = eventAnchorId,
    occurredAtEpochMillis = occurredAtEpochMillis,
    isRead = isRead,
    isResolved = isResolved,
    actionRequestId = actionRequestId,
)

private fun <T, K> List<T>.requireUniqueBy(
    key: (T) -> K,
    type: String,
): List<T> {
    check(distinctBy(key).size == size) { "Duplicate $type records" }
    return this
}

private inline fun <reified T : Enum<T>> enumValue(name: String): T =
    enumValues<T>().firstOrNull { it.name == name }
        ?: throw IllegalArgumentException("Unknown persisted enum value")
