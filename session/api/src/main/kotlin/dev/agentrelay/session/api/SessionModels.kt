package dev.agentrelay.session.api

import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentApprovalType
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

enum class SessionActivitySummaryKind {
    NEW_AGENT_OUTPUT,
    TOOL_FAILED,
    NAMED_TOOL_FAILED,
    AGENT_TURN_COMPLETED,
    AGENT_TURN_FAILED,
    AGENT_PROVIDER_FAILED,
    AGENT_QUESTION_REQUIRES_ANSWER,
    AGENT_APPROVAL_REQUIRED,
    CONNECTION_RECONNECTED,
}

sealed interface SessionActivitySummary {
    data class Generated(
        val kind: SessionActivitySummaryKind,
        val argument: String? = null,
    ) : SessionActivitySummary {
        init {
            if (kind.requiresArgument) {
                requireBounded(requireNotNull(argument), "Activity summary argument", MAX_LABEL_CHARS)
            } else {
                require(argument == null) { "Activity summary kind does not accept an argument" }
            }
        }
    }

    data class Verbatim(val text: String) : SessionActivitySummary {
        init {
            requireBounded(text, "Activity summary", MAX_ACTIVITY_CHARS)
        }
    }
}

private val SessionActivitySummaryKind.requiresArgument: Boolean
    get() = this == SessionActivitySummaryKind.NAMED_TOOL_FAILED ||
        this == SessionActivitySummaryKind.CONNECTION_RECONNECTED

data class SessionActivity(
    val id: String,
    val locator: SessionLocator,
    val type: SessionActivityType,
    val summary: SessionActivitySummary,
    val eventAnchorId: String?,
    val actionRequestId: String? = null,
    val occurredAtEpochMillis: Long,
    val isRead: Boolean = false,
    val isResolved: Boolean = false,
) {
    init {
        requireBounded(id, "Activity id", MAX_ID_CHARS)
        eventAnchorId?.let { requireBounded(it, "Event anchor id", MAX_ID_CHARS) }
        actionRequestId?.let { requireBounded(it, "Action request id", MAX_ID_CHARS) }
        require(occurredAtEpochMillis >= 0L)
        require(!isResolved || type == SessionActivityType.APPROVAL_REQUIRED || type == SessionActivityType.QUESTION) {
            "Only actionable activity can be resolved"
        }
    }

    val requiresAction: Boolean
        get() = !isResolved &&
            (type == SessionActivityType.APPROVAL_REQUIRED || type == SessionActivityType.QUESTION)
}

enum class SessionActionState {
    PENDING,
    DELIVERING,
    RESOLVED,
}

enum class SessionActionRisk {
    DESTRUCTIVE_COMMAND,
    BROAD_FILESYSTEM_ACCESS,
    CREDENTIAL_ACCESS,
    NETWORK_EXPANSION,
    EXTERNAL_TOOL,
}

enum class SessionPresentationTextKind {
    ACTION_REVIEW_REQUIRED,
    AGENT_QUESTION,
}

sealed interface SessionPresentationText {
    data class Generated(val kind: SessionPresentationTextKind) : SessionPresentationText

    data class Verbatim(val text: String) : SessionPresentationText {
        init {
            requireBounded(text, "Presentation text", MAX_DESCRIPTION_CHARS)
        }
    }
}

data class SessionQuestionOption(
    val label: String,
    val description: String? = null,
) {
    init {
        requireBounded(label, "Question option", MAX_QUESTION_OPTION_CHARS)
        description?.let { requireBounded(it, "Question option description", MAX_DESCRIPTION_CHARS) }
    }
}

data class SessionQuestion(
    val id: String,
    val providerQuestionId: String,
    val header: String?,
    val prompt: SessionPresentationText,
    val options: List<SessionQuestionOption> = emptyList(),
    val allowsOther: Boolean = true,
    val allowsMultiple: Boolean = false,
) {
    init {
        requireBounded(id, "Question id", MAX_ID_CHARS)
        requireBounded(providerQuestionId, "Provider question id", MAX_PROVIDER_REQUEST_ID_CHARS)
        header?.let { requireBounded(it, "Question header", MAX_LABEL_CHARS) }
        requirePresentationText(
            prompt,
            "Question prompt",
            MAX_DESCRIPTION_CHARS,
            SessionPresentationTextKind.AGENT_QUESTION,
        )
        require(options.size <= MAX_QUESTION_OPTIONS) { "Question has too many options" }
        require(options.distinctBy { it.label }.size == options.size) {
            "Question contains duplicate options"
        }
        require(options.isNotEmpty() || allowsOther) {
            "Question must provide an option or allow a written answer"
        }
    }
}

data class SessionActionRequest(
    val id: String,
    val providerApprovalId: String,
    val locator: SessionLocator,
    val turnId: String?,
    val type: AgentApprovalType,
    val title: SessionPresentationText,
    val description: String?,
    val command: String?,
    val workingDirectory: String?,
    val questions: List<SessionQuestion>,
    val availableDecisions: Set<AgentApprovalDecision>,
    val riskReasons: Set<SessionActionRisk>,
    val receivedAtEpochMillis: Long,
    val state: SessionActionState = SessionActionState.PENDING,
    val decision: AgentApprovalDecision? = null,
    val answeredQuestionIds: Set<String> = emptySet(),
    val additionalConfirmationGiven: Boolean = false,
    val decisionAtEpochMillis: Long? = null,
) {
    init {
        requireBounded(id, "Action request id", MAX_ID_CHARS)
        requireBounded(providerApprovalId, "Provider approval id", MAX_PROVIDER_REQUEST_ID_CHARS)
        turnId?.let { requireBounded(it, "Action turn id", MAX_ID_CHARS) }
        requirePresentationText(
            title,
            "Action title",
            MAX_TITLE_CHARS,
            SessionPresentationTextKind.ACTION_REVIEW_REQUIRED,
        )
        description?.let { requireBounded(it, "Action description", MAX_DESCRIPTION_CHARS) }
        command?.let { requireBounded(it, "Action command", MAX_COMMAND_CHARS) }
        workingDirectory?.let { requireBounded(it, "Action working directory", MAX_PATH_CHARS) }
        require(questions.size <= MAX_QUESTIONS) { "Action request has too many questions" }
        require(questions.distinctBy { it.id }.size == questions.size) {
            "Action request contains duplicate questions"
        }
        require(availableDecisions.isNotEmpty()) { "Action request exposes no decisions" }
        require(receivedAtEpochMillis >= 0L)
        require(answeredQuestionIds.all { answer -> questions.any { it.id == answer } }) {
            "Action decision references an unknown question"
        }
        when (state) {
            SessionActionState.PENDING -> {
                require(
                    decision == null && decisionAtEpochMillis == null &&
                        answeredQuestionIds.isEmpty() && !additionalConfirmationGiven,
                ) {
                    "A pending action cannot contain a decision"
                }
            }
            SessionActionState.DELIVERING,
            SessionActionState.RESOLVED,
            -> {
                require(decision != null && decisionAtEpochMillis != null) {
                    "A delivered action requires decision audit data"
                }
                require(decision in availableDecisions) { "Action decision was not offered by the provider" }
                require(decisionAtEpochMillis >= 0L)
                require(decision == AgentApprovalDecision.SUBMIT || answeredQuestionIds.isEmpty()) {
                    "Only submitted question answers may record answered question ids"
                }
                require(!requiresAdditionalConfirmation(decision) || additionalConfirmationGiven) {
                    "A high-risk or session-wide approval requires additional confirmation"
                }
            }
        }
    }

    val requiresAction: Boolean
        get() = state != SessionActionState.RESOLVED

    fun requiresAdditionalConfirmation(candidate: AgentApprovalDecision): Boolean =
        candidate == AgentApprovalDecision.APPROVE_FOR_SESSION ||
            (candidate in APPROVING_DECISIONS && riskReasons.isNotEmpty())
}

enum class SessionArtifactAvailability {
    DOWNLOADABLE,
    DELETED,
    OUTSIDE_WORKSPACE,
    WORKSPACE_UNKNOWN,
}

data class SessionArtifact(
    val id: String,
    val locator: SessionLocator,
    val providerPath: String,
    val relativePath: String?,
    val oldProviderPath: String?,
    val oldRelativePath: String?,
    val kind: AgentFileChangeKind,
    val turnId: String?,
    val availability: SessionArtifactAvailability,
    val observedAtEpochMillis: Long,
) {
    init {
        requireBounded(id, "Artifact id", MAX_ID_CHARS)
        requireBounded(providerPath, "Artifact provider path", MAX_PATH_CHARS)
        relativePath?.let { requireArtifactRelativePath(it, "Artifact relative path") }
        oldProviderPath?.let { requireBounded(it, "Artifact old provider path", MAX_PATH_CHARS) }
        oldRelativePath?.let { requireArtifactRelativePath(it, "Artifact old relative path") }
        turnId?.let { requireBounded(it, "Artifact turn id", MAX_ID_CHARS) }
        require(observedAtEpochMillis >= 0L)
        when (availability) {
            SessionArtifactAvailability.DOWNLOADABLE -> {
                require(relativePath != null) { "A downloadable artifact requires a safe relative path" }
                require(kind != AgentFileChangeKind.DELETED) {
                    "A deleted artifact cannot be downloadable"
                }
            }
            SessionArtifactAvailability.DELETED -> {
                require(kind == AgentFileChangeKind.DELETED) {
                    "Deleted availability requires a deleted file change"
                }
            }
            SessionArtifactAvailability.OUTSIDE_WORKSPACE,
            SessionArtifactAvailability.WORKSPACE_UNKNOWN,
            -> {
                require(relativePath == null) {
                    "An unavailable artifact cannot expose a transfer path"
                }
            }
        }
    }
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
    val actionRequests: List<SessionActionRequest> = emptyList(),
    val artifacts: List<SessionArtifact> = emptyList(),
) {
    init {
        require(sessions.distinctBy { it.locator }.size == sessions.size) {
            "Session snapshot contains duplicate sessions"
        }
        require(activities.distinctBy { it.locator to it.id }.size == activities.size) {
            "Session snapshot contains duplicate activity identities"
        }
        require(actionRequests.distinctBy { it.locator to it.id }.size == actionRequests.size) {
            "Session snapshot contains duplicate action request identities"
        }
        require(artifacts.distinctBy { it.locator to it.id }.size == artifacts.size) {
            "Session snapshot contains duplicate artifact identities"
        }
        val locators = sessions.mapTo(mutableSetOf()) { it.locator }
        require(drafts.keys.all(locators::contains)) { "A draft references an unknown session" }
        require(activities.all { it.locator in locators }) { "Activity references an unknown session" }
        require(transcripts.keys.all(locators::contains)) { "A transcript references an unknown session" }
        require(actionRequests.all { it.locator in locators }) { "An action request references an unknown session" }
        require(artifacts.all { it.locator in locators }) { "An artifact references an unknown session" }
        val actionKeys = actionRequests.mapTo(mutableSetOf()) { it.locator to it.id }
        require(
            activities.all { activity ->
                activity.actionRequestId == null || (activity.locator to activity.actionRequestId) in actionKeys
            },
        ) { "An activity references an unknown action request" }
        require(
            transcripts.values.all { entries ->
                entries.distinctBy { it.id }.size == entries.size
            },
        ) { "A transcript contains duplicate entry ids" }
    }

    fun session(locator: SessionLocator): SessionRecord? = sessions.firstOrNull { it.locator == locator }

    fun actionRequest(locator: SessionLocator, id: String): SessionActionRequest? =
        actionRequests.firstOrNull { it.locator == locator && it.id == id }

    fun sessionArtifacts(locator: SessionLocator): List<SessionArtifact> =
        artifacts
            .asSequence()
            .filter { it.locator == locator }
            .sortedByDescending(SessionArtifact::observedAtEpochMillis)
            .toList()

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
    val maximumActionRequests: Int = 2_000,
    val maximumArtifacts: Int = 4_000,
) {
    init {
        require(maximumSessions > 0)
        require(maximumActivities > 0)
        require(maximumTranscriptEntriesPerSession > 0)
        require(maximumActionRequests > 0)
        require(maximumArtifacts > 0)
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
    val retainedActionRequests = actionRequests
        .asSequence()
        .filter { it.locator in retainedLocators }
        .sortedWith(
            compareByDescending<SessionActionRequest> { it.requiresAction }
                .thenByDescending { it.receivedAtEpochMillis },
        )
        .take(policy.maximumActionRequests)
        .sortedBy { it.receivedAtEpochMillis }
        .toList()
    val retainedArtifacts = artifacts
        .asSequence()
        .filter { it.locator in retainedLocators }
        .sortedByDescending(SessionArtifact::observedAtEpochMillis)
        .take(policy.maximumArtifacts)
        .sortedBy(SessionArtifact::observedAtEpochMillis)
        .toList()
    val retainedActionKeys = retainedActionRequests.mapTo(mutableSetOf()) { it.locator to it.id }
    val normalizedActivities = retainedActivities.filter { activity ->
        activity.actionRequestId == null || (activity.locator to activity.actionRequestId) in retainedActionKeys
    }
    val unreadBySession = normalizedActivities
        .asSequence()
        .filterNot(SessionActivity::isRead)
        .groupingBy(SessionActivity::locator)
        .eachCount()
    val newestActivityBySession = normalizedActivities
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
        activities = normalizedActivities,
        transcripts = normalizedTranscripts,
        actionRequests = retainedActionRequests,
        artifacts = retainedArtifacts,
    )
}

private fun requireBounded(value: String, label: String, maximum: Int) {
    require(value.isNotBlank()) { "$label must not be blank" }
    require(value.length <= maximum) { "$label is too large" }
}

private fun requirePresentationText(
    value: SessionPresentationText,
    label: String,
    maximum: Int,
    generatedKind: SessionPresentationTextKind,
) {
    when (value) {
        is SessionPresentationText.Verbatim -> requireBounded(value.text, label, maximum)
        is SessionPresentationText.Generated -> require(value.kind == generatedKind) {
            "$label has the wrong generated kind"
        }
    }
}

private fun requireArtifactRelativePath(value: String, label: String) {
    requireBounded(value, label, MAX_PATH_CHARS)
    require(!value.startsWith('/') && !value.startsWith('\\')) {
        "$label must stay relative to the workspace"
    }
    require('\\' !in value) { "$label must use forward slashes" }
    val segments = value.split('/')
    require(segments.all { it.isNotEmpty() && it != "." && it != ".." }) {
        "$label contains an unsafe segment"
    }
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
private const val MAX_DESCRIPTION_CHARS = 16_384
private const val MAX_COMMAND_CHARS = 64 * 1024
private const val MAX_QUESTIONS = 32
private const val MAX_QUESTION_OPTIONS = 64
private const val MAX_QUESTION_OPTION_CHARS = 4_096
private const val MAX_PROVIDER_REQUEST_ID_CHARS = 4_096
private const val MAX_METADATA_ENTRIES = 64
private const val MAX_METADATA_KEY_CHARS = 256
private const val MAX_METADATA_VALUE_CHARS = 4_096

private val APPROVING_DECISIONS = setOf(
    AgentApprovalDecision.APPROVE_ONCE,
    AgentApprovalDecision.APPROVE_FOR_SESSION,
    AgentApprovalDecision.SUBMIT,
)
