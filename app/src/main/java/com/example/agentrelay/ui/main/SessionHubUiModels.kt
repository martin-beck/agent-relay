package com.example.agentrelay.ui.main

import dev.agentrelay.connection.api.ConnectionCapability
import dev.agentrelay.connection.api.ConnectionDisconnectReason
import dev.agentrelay.connection.api.ConnectionIdentityDisposition
import dev.agentrelay.connection.api.ConnectionProviderDescriptor
import dev.agentrelay.connection.api.ConnectionState
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentApprovalType
import dev.agentrelay.provider.api.AgentCapability
import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.AgentTranscriptRole
import dev.agentrelay.session.api.CachedTranscriptEntry
import dev.agentrelay.session.api.SessionActivity
import dev.agentrelay.session.api.SessionActivityType
import dev.agentrelay.session.api.SessionActionRequest
import dev.agentrelay.session.api.SessionActionRisk
import dev.agentrelay.session.api.SessionActionState
import dev.agentrelay.session.api.SessionDraft
import dev.agentrelay.session.api.SessionHubSnapshot
import dev.agentrelay.session.api.SessionLocator
import dev.agentrelay.session.api.SessionRecord
import dev.agentrelay.session.runtime.AgentEndpointKey
import dev.agentrelay.session.runtime.AgentEndpointPhase
import dev.agentrelay.session.runtime.SessionConnectionKey
import dev.agentrelay.session.runtime.SessionCoordinatorSnapshot
import java.security.MessageDigest

internal const val MAX_SESSION_DRAFT_CHARS = 32_000

internal data class SessionHubUiModel(
    val availableConnectionProviders: List<String>,
    val connections: List<ConnectionUiModel>,
    val sessions: List<SessionUiModel>,
    val issues: List<CoordinatorIssueUiModel>,
    val selectedSession: SessionDetailUiModel?,
    val selectedSessionKey: String?,
    val operationError: String?,
    val isRefreshingProfiles: Boolean,
    val manageableConnectionProviders: List<ConnectionProviderUiModel> = emptyList(),
    val sessionLaunchers: List<SessionLauncherUiModel> = emptyList(),
    val attentionActions: List<SessionActionUiModel> = emptyList(),
)

internal data class ConnectionProviderUiModel(
    val stableKey: String,
    val name: String,
)

internal data class ConnectionUiModel(
    val stableKey: String,
    val providerName: String,
    val label: String,
    val target: String,
    val authenticationLabel: String?,
    val status: ConnectionStatus,
    val statusDetail: String?,
    val connectedAgentCount: Int,
    val agentCount: Int,
    val unavailableAgentCount: Int,
    val canConnect: Boolean,
    val canDisconnect: Boolean,
    val isBusy: Boolean,
    val identityChallenge: IdentityChallengeUiModel?,
    val canEdit: Boolean = false,
)

internal enum class ConnectionStatus {
    OFFLINE,
    CONNECTING,
    ONLINE,
    RECONNECTING,
    IDENTITY_REVIEW,
    FAILED,
}

internal data class IdentityChallengeUiModel(
    val endpoint: String,
    val algorithm: String,
    val fingerprint: String,
    val isChangedIdentity: Boolean,
    val previousFingerprints: List<String>,
)

internal data class SessionUiModel(
    val stableKey: String,
    val title: String,
    val preview: String,
    val connectionLabel: String,
    val connectionProviderName: String,
    val agentProviderLabel: String,
    val projectPath: String?,
    val agentState: AgentSessionState,
    val unreadCount: Int,
    val requiresActionCount: Int,
    val lastActivityAtEpochMillis: Long?,
    val isPinned: Boolean,
)

internal data class SessionLauncherUiModel(
    val stableKey: String,
    val connectionLabel: String,
    val connectionProviderName: String,
    val agentProviderLabel: String,
    val suggestedWorkingDirectory: String?,
)

internal data class SessionDetailUiModel(
    val session: SessionUiModel,
    val activities: List<SessionActivityUiModel>,
    val transcript: List<TranscriptEntryUiModel>,
    val composer: SessionComposerUiModel = SessionComposerUiModel(),
    val actions: List<SessionActionUiModel> = emptyList(),
)

internal data class SessionActionUiModel(
    val stableKey: String,
    val sessionKey: String,
    val title: String,
    val typeLabel: String,
    val description: String?,
    val command: String?,
    val scope: String?,
    val connectionLabel: String,
    val connectionProviderName: String,
    val connectionTarget: String,
    val agentProviderLabel: String,
    val sessionTitle: String,
    val questions: List<SessionQuestionUiModel>,
    val decisions: List<SessionDecisionUiModel>,
    val riskLabels: List<String>,
    val state: SessionActionState,
    val completedDecisionLabel: String?,
    val additionalConfirmationGiven: Boolean,
    val isBusy: Boolean,
)

internal data class SessionQuestionUiModel(
    val stableKey: String,
    val header: String?,
    val prompt: String,
    val options: List<SessionQuestionOptionUiModel>,
    val allowsOther: Boolean,
    val allowsMultiple: Boolean,
)

internal data class SessionQuestionOptionUiModel(
    val label: String,
    val description: String?,
)

internal data class SessionDecisionUiModel(
    val decision: AgentApprovalDecision,
    val label: String,
    val requiresConfirmation: Boolean,
    val isPositive: Boolean,
)

internal enum class SessionSubmitMode {
    SEND,
    STEER,
}

internal data class SessionComposerUiModel(
    val draftText: String = "",
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val submitMode: SessionSubmitMode = SessionSubmitMode.SEND,
    val canSubmit: Boolean = false,
    val canResume: Boolean = false,
    val canInterrupt: Boolean = false,
    val isBusy: Boolean = false,
    val statusMessage: String? = "Connect this session to send input.",
)

internal data class SessionActivityUiModel(
    val id: String,
    val type: SessionActivityType,
    val summary: String,
    val occurredAtEpochMillis: Long,
    val requiresAction: Boolean,
    val isRead: Boolean,
)

internal data class TranscriptEntryUiModel(
    val id: String,
    val roleLabel: String,
    val kind: TimelineEntryKind,
    val text: String,
    val wasTruncated: Boolean,
    val createdAtEpochMillis: Long?,
)

internal enum class TimelineEntryKind {
    USER_MESSAGE,
    AGENT_COMMENTARY,
    AGENT_FINAL,
    PLAN,
    REASONING_SUMMARY,
    TOOL,
    SYSTEM,
}

internal data class CoordinatorIssueUiModel(
    val id: String,
    val message: String,
    val recoverable: Boolean,
)

internal val SessionConnectionKey.stableUiKey: String
    get() = stableHash(providerId.value, profileId.value)

internal val AgentEndpointKey.stableUiKey: String
    get() = stableHash(
        connection.providerId.value,
        connection.profileId.value,
        agentProviderId.value,
    )

internal val SessionLocator.stableUiKey: String
    get() = stableHash(stableKey)

private fun stableHash(vararg values: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString(separator = "") { "${it.length}:$it" }.encodeToByteArray())
        .joinToString(separator = "") { "%02x".format(it) }

internal object SessionHubUiMapper {
    fun map(
        coordinator: SessionCoordinatorSnapshot,
        sessions: SessionHubSnapshot,
        connectionProviders: List<ConnectionProviderDescriptor>,
        selectedSessionKey: String?,
        operationError: String?,
        busyConnectionKeys: Set<String>,
        busySessionKeys: Set<String> = emptySet(),
        busyActionKeys: Set<String> = emptySet(),
        draftOverrides: Map<String, SessionDraft> = emptyMap(),
    ): SessionHubUiModel {
        val providerNames = connectionProviders.associate { it.id to it.displayName }
        val manageableProviders = connectionProviders
            .filter { ConnectionCapability.PROFILE_MANAGEMENT in it.capabilities }
            .associateBy(ConnectionProviderDescriptor::id)
        val actions = actionModels(sessions, providerNames, busyActionKeys)
        return SessionHubUiModel(
            availableConnectionProviders = connectionProviders.map { it.displayName },
            connections = connectionModels(
                coordinator,
                providerNames,
                manageableProviders.keys,
                busyConnectionKeys,
            ),
            sessions = sessionModels(sessions, providerNames),
            issues = coordinator.issues.values
                .sortedByDescending { it.occurredAtEpochMillis }
                .map { CoordinatorIssueUiModel(it.id, it.actionableMessage, it.recoverable) },
            selectedSession = selectedDetail(
                selectedSessionKey,
                coordinator,
                sessions,
                providerNames,
                actions,
                busySessionKeys,
                draftOverrides,
            ),
            selectedSessionKey = selectedSessionKey,
            operationError = operationError,
            isRefreshingProfiles = coordinator.isRefreshingProfiles,
            manageableConnectionProviders = manageableProviders.values
                .map { ConnectionProviderUiModel(it.id.value, it.displayName) }
                .sortedBy(ConnectionProviderUiModel::name),
            sessionLaunchers = sessionLaunchers(coordinator, sessions, providerNames),
            attentionActions = actions.filter { it.state != SessionActionState.RESOLVED },
        )
    }

    private fun connectionModels(
        coordinator: SessionCoordinatorSnapshot,
        providerNames: Map<dev.agentrelay.connection.api.ConnectionProviderId, String>,
        manageableProviderIds: Set<dev.agentrelay.connection.api.ConnectionProviderId>,
        busyConnectionKeys: Set<String>,
    ): List<ConnectionUiModel> = coordinator.profiles.map { profile ->
        val key = SessionConnectionKey(profile.providerId, profile.id)
        val state = coordinator.connectionStates[key]
        val endpoints = coordinator.agentEndpoints.values.filter { it.key.connection == key }
        ConnectionUiModel(
            stableKey = key.stableUiKey,
            providerName = providerNames[profile.providerId] ?: profile.providerId.value,
            label = profile.label,
            target = profile.target,
            authenticationLabel = profile.authenticationLabel,
            status = state.toUiStatus(),
            statusDetail = state.toStatusDetail(),
            connectedAgentCount = endpoints.count { it.phase == AgentEndpointPhase.READY },
            agentCount = endpoints.size,
            unavailableAgentCount = endpoints.count {
                it.phase == AgentEndpointPhase.UNAVAILABLE || it.phase == AgentEndpointPhase.FAILED
            },
            canConnect = state == null ||
                state is ConnectionState.Disconnected ||
                state is ConnectionState.Failed,
            canDisconnect = state is ConnectionState.Connected ||
                state is ConnectionState.Connecting ||
                state is ConnectionState.Reconnecting ||
                state is ConnectionState.AwaitingIdentityTrust,
            isBusy = key.stableUiKey in busyConnectionKeys,
            identityChallenge = (state as? ConnectionState.AwaitingIdentityTrust)
                ?.challenge
                ?.let { challenge ->
                    IdentityChallengeUiModel(
                        endpoint = challenge.endpoint,
                        algorithm = challenge.algorithm,
                        fingerprint = challenge.sha256Fingerprint,
                        isChangedIdentity =
                        challenge.disposition == ConnectionIdentityDisposition.CHANGED,
                        previousFingerprints = challenge.previouslyTrustedFingerprints,
                    )
                },
            canEdit = profile.providerId in manageableProviderIds,
        )
    }

    private fun sessionModels(
        sessions: SessionHubSnapshot,
        providerNames: Map<dev.agentrelay.connection.api.ConnectionProviderId, String>,
    ): List<SessionUiModel> = sessions.recentSessions().map { record ->
        record.toUiModel(
            connectionProviderName = providerNames[record.locator.connectionProviderId]
                ?: record.locator.connectionProviderId.value,
            activities = sessions.activities.filter { it.locator == record.locator },
        )
    }

    private fun sessionLaunchers(
        coordinator: SessionCoordinatorSnapshot,
        sessions: SessionHubSnapshot,
        providerNames: Map<dev.agentrelay.connection.api.ConnectionProviderId, String>,
    ): List<SessionLauncherUiModel> = coordinator.agentEndpoints.values
        .asSequence()
        .filter { endpoint ->
            endpoint.phase == AgentEndpointPhase.READY &&
                AgentCapability.SESSION_START in endpoint.descriptor.capabilities
        }
        .mapNotNull { endpoint ->
            val profile = coordinator.profile(endpoint.key.connection) ?: return@mapNotNull null
            val suggestedWorkingDirectory = sessions.recentSessions()
                .firstOrNull { record ->
                    record.locator.connectionProviderId == endpoint.key.connection.providerId &&
                        record.locator.connectionProfileId == endpoint.key.connection.profileId &&
                        record.locator.agentProviderId == endpoint.key.agentProviderId
                }
                ?.observation
                ?.projectPath
            SessionLauncherUiModel(
                stableKey = endpoint.key.stableUiKey,
                connectionLabel = profile.label,
                connectionProviderName = providerNames[profile.providerId]
                    ?: profile.providerId.value,
                agentProviderLabel = endpoint.descriptor.displayName,
                suggestedWorkingDirectory = suggestedWorkingDirectory,
            )
        }
        .sortedWith(
            compareBy(SessionLauncherUiModel::connectionLabel)
                .thenBy(SessionLauncherUiModel::agentProviderLabel),
        )
        .toList()

    private fun actionModels(
        sessions: SessionHubSnapshot,
        providerNames: Map<dev.agentrelay.connection.api.ConnectionProviderId, String>,
        busyActionKeys: Set<String>,
    ): List<SessionActionUiModel> = sessions.actionRequests
        .sortedByDescending(SessionActionRequest::receivedAtEpochMillis)
        .mapNotNull { request ->
            val record = sessions.session(request.locator) ?: return@mapNotNull null
            request.toUiModel(
                record = record,
                connectionProviderName = providerNames[record.locator.connectionProviderId]
                    ?: record.locator.connectionProviderId.value,
                isBusy = request.id in busyActionKeys,
            )
        }

    private fun selectedDetail(
        selectedSessionKey: String?,
        coordinator: SessionCoordinatorSnapshot,
        sessions: SessionHubSnapshot,
        providerNames: Map<dev.agentrelay.connection.api.ConnectionProviderId, String>,
        actions: List<SessionActionUiModel>,
        busySessionKeys: Set<String>,
        draftOverrides: Map<String, SessionDraft>,
    ): SessionDetailUiModel? {
        val record = sessions.sessions.firstOrNull {
            it.locator.stableUiKey == selectedSessionKey
        } ?: return null
        val activities = sessions.activities.filter { it.locator == record.locator }
        return SessionDetailUiModel(
            session = record.toUiModel(
                connectionProviderName = providerNames[record.locator.connectionProviderId]
                    ?: record.locator.connectionProviderId.value,
                activities = activities,
            ),
            activities = activities
                .sortedByDescending(SessionActivity::occurredAtEpochMillis)
                .map { it.toUiModel() },
            transcript = sessions.transcripts[record.locator]
                .orEmpty()
                .map { it.toUiModel() },
            composer = record.toComposerUiModel(
                coordinator = coordinator,
                persistedDraft = sessions.drafts[record.locator],
                overrideDraft = draftOverrides[record.locator.stableUiKey],
                isBusy = record.locator.stableUiKey in busySessionKeys,
            ),
            actions = actions.filter { it.sessionKey == record.locator.stableUiKey },
        )
    }

    private fun SessionRecord.toUiModel(
        connectionProviderName: String,
        activities: List<SessionActivity>,
    ) = SessionUiModel(
        stableKey = locator.stableUiKey,
        title = observation.title?.takeIf(String::isNotBlank)
            ?: observation.agentProviderLabel + " session",
        preview = observation.preview,
        connectionLabel = observation.connectionLabel,
        connectionProviderName = connectionProviderName,
        agentProviderLabel = observation.agentProviderLabel,
        projectPath = observation.projectPath,
        agentState = observation.agentState,
        unreadCount = unreadCount,
        requiresActionCount = activities.count(SessionActivity::requiresAction),
        lastActivityAtEpochMillis = lastActivityAtEpochMillis,
        isPinned = preferences.pinned,
    )

    private fun SessionActionRequest.toUiModel(
        record: SessionRecord,
        connectionProviderName: String,
        isBusy: Boolean,
    ) = SessionActionUiModel(
        stableKey = id,
        sessionKey = locator.stableUiKey,
        title = title,
        typeLabel = type.uiLabel,
        description = description,
        command = command,
        scope = workingDirectory ?: record.observation.projectPath,
        connectionLabel = record.observation.connectionLabel,
        connectionProviderName = connectionProviderName,
        connectionTarget = record.observation.connectionTarget,
        agentProviderLabel = record.observation.agentProviderLabel,
        sessionTitle = record.observation.title?.takeIf(String::isNotBlank)
            ?: record.observation.agentProviderLabel + " session",
        questions = questions.map { question ->
            SessionQuestionUiModel(
                stableKey = question.id,
                header = question.header,
                prompt = question.prompt,
                options = question.options.map { option ->
                    SessionQuestionOptionUiModel(option.label, option.description)
                },
                allowsOther = question.allowsOther,
                allowsMultiple = question.allowsMultiple,
            )
        },
        decisions = availableDecisions
            .sortedBy(AgentApprovalDecision::ordinal)
            .map { candidate ->
                SessionDecisionUiModel(
                    decision = candidate,
                    label = candidate.uiLabel,
                    requiresConfirmation = requiresAdditionalConfirmation(candidate),
                    isPositive = candidate in POSITIVE_DECISIONS,
                )
            },
        riskLabels = riskReasons
            .sortedBy(SessionActionRisk::ordinal)
            .map { it.uiLabel },
        state = state,
        completedDecisionLabel = decision?.uiLabel,
        additionalConfirmationGiven = additionalConfirmationGiven,
        isBusy = isBusy || state == SessionActionState.DELIVERING,
    )

    private val AgentApprovalType.uiLabel: String
        get() = when (this) {
            AgentApprovalType.COMMAND -> "Command approval"
            AgentApprovalType.FILE_CHANGE -> "File change approval"
            AgentApprovalType.USER_INPUT -> "Question"
            AgentApprovalType.PERMISSION -> "Permission request"
            AgentApprovalType.EXTERNAL_TOOL -> "External tool approval"
        }

    private val AgentApprovalDecision.uiLabel: String
        get() = when (this) {
            AgentApprovalDecision.APPROVE_ONCE -> "Approve once"
            AgentApprovalDecision.APPROVE_FOR_SESSION -> "Approve for session"
            AgentApprovalDecision.SUBMIT -> "Submit answers"
            AgentApprovalDecision.DECLINE -> "Decline"
            AgentApprovalDecision.CANCEL -> "Cancel"
        }

    private val SessionActionRisk.uiLabel: String
        get() = when (this) {
            SessionActionRisk.DESTRUCTIVE_COMMAND -> "Destructive command"
            SessionActionRisk.BROAD_FILESYSTEM_ACCESS -> "Broad filesystem access"
            SessionActionRisk.CREDENTIAL_ACCESS -> "Credential or secret access"
            SessionActionRisk.NETWORK_EXPANSION -> "Network access expansion"
            SessionActionRisk.EXTERNAL_TOOL -> "External tool execution"
        }

    private fun SessionActivity.toUiModel() = SessionActivityUiModel(
        id = id,
        type = type,
        summary = summary,
        occurredAtEpochMillis = occurredAtEpochMillis,
        requiresAction = requiresAction,
        isRead = isRead,
    )

    private fun SessionRecord.toComposerUiModel(
        coordinator: SessionCoordinatorSnapshot,
        persistedDraft: SessionDraft?,
        overrideDraft: SessionDraft?,
        isBusy: Boolean,
    ): SessionComposerUiModel {
        val draft = overrideDraft ?: persistedDraft
        val connectionKey = SessionConnectionKey(
            providerId = locator.connectionProviderId,
            profileId = locator.connectionProfileId,
        )
        val endpoint = coordinator.agentEndpoints[
            AgentEndpointKey(connectionKey, locator.agentProviderId),
        ]
        val endpointReady = endpoint?.phase == AgentEndpointPhase.READY
        val capabilities = endpoint?.descriptor?.capabilities.orEmpty()
        val canAcceptInput = observation.metadata["can_accept_input"] == "true"
        val supportsSubmit = supportsComposerSubmit(
            state = observation.agentState,
            endpointReady = endpointReady,
            canAcceptInput = canAcceptInput,
            capabilities = capabilities,
        )
        val canResume = endpointReady &&
            AgentCapability.SESSION_RESUME in capabilities &&
            observation.agentState in RESUMABLE_SESSION_STATES &&
            !isBusy
        val canInterrupt = endpointReady &&
            AgentCapability.TURN_INTERRUPT in capabilities &&
            observation.agentState in INTERRUPTIBLE_SESSION_STATES &&
            !isBusy
        val draftText = draft?.text.orEmpty()
        return SessionComposerUiModel(
            draftText = draftText,
            selectionStart = draft?.selectionStart?.coerceIn(0, draftText.length) ?: draftText.length,
            selectionEnd = draft?.selectionEnd?.coerceIn(0, draftText.length) ?: draftText.length,
            submitMode = if (observation.agentState == AgentSessionState.RUNNING) {
                SessionSubmitMode.STEER
            } else {
                SessionSubmitMode.SEND
            },
            canSubmit = supportsSubmit && draftText.isNotBlank() && !isBusy,
            canResume = canResume,
            canInterrupt = canInterrupt,
            isBusy = isBusy,
            statusMessage = composerStatusMessage(
                endpointReady = endpointReady,
                canAcceptInput = canAcceptInput,
                capabilities = capabilities,
                canResume = canResume,
                isBusy = isBusy,
            ),
        )
    }

    private fun supportsComposerSubmit(
        state: AgentSessionState,
        endpointReady: Boolean,
        canAcceptInput: Boolean,
        capabilities: Set<AgentCapability>,
    ): Boolean {
        if (!endpointReady || !canAcceptInput) {
            return false
        }
        return when (state) {
            AgentSessionState.IDLE -> true
            AgentSessionState.RUNNING -> AgentCapability.ACTIVE_TURN_STEERING in capabilities
            AgentSessionState.NOT_LOADED,
            AgentSessionState.WAITING_FOR_APPROVAL,
            AgentSessionState.FAILED,
            AgentSessionState.UNKNOWN,
            -> false
        }
    }

    private fun SessionRecord.composerStatusMessage(
        endpointReady: Boolean,
        canAcceptInput: Boolean,
        capabilities: Set<AgentCapability>,
        canResume: Boolean,
        isBusy: Boolean,
    ): String? = when {
        isBusy -> "Applying session action..."
        !endpointReady -> "Connect ${observation.connectionLabel} to send this saved draft."
        observation.agentState == AgentSessionState.WAITING_FOR_APPROVAL ->
            "Resolve the pending approval or question before sending more input."
        observation.agentState in RESUMABLE_SESSION_STATES ->
            if (canResume) {
                "Resume this saved session before sending input."
            } else {
                "This provider cannot safely resume the saved session."
            }
        observation.agentState == AgentSessionState.RUNNING &&
            AgentCapability.ACTIVE_TURN_STEERING !in capabilities ->
            "This provider cannot steer an active turn. Wait for it to finish or interrupt it."
        !canAcceptInput -> "The provider exposed this session as read-only."
        else -> null
    }

    private fun CachedTranscriptEntry.toUiModel() = TranscriptEntryUiModel(
        id = id,
        roleLabel = timelineKind.label,
        kind = timelineKind,
        text = text.take(MAX_RENDERED_TRANSCRIPT_CHARS),
        wasTruncated = text.length > MAX_RENDERED_TRANSCRIPT_CHARS,
        createdAtEpochMillis = createdAtEpochMillis,
    )

    private val CachedTranscriptEntry.timelineKind: TimelineEntryKind
        get() = when (role) {
            AgentTranscriptRole.USER -> TimelineEntryKind.USER_MESSAGE
            AgentTranscriptRole.TOOL -> TimelineEntryKind.TOOL
            AgentTranscriptRole.SYSTEM -> TimelineEntryKind.SYSTEM
            AgentTranscriptRole.AGENT -> when (channel) {
                AgentMessageChannel.FINAL -> TimelineEntryKind.AGENT_FINAL
                AgentMessageChannel.PLAN -> TimelineEntryKind.PLAN
                AgentMessageChannel.REASONING_SUMMARY -> TimelineEntryKind.REASONING_SUMMARY
                AgentMessageChannel.SYSTEM -> TimelineEntryKind.SYSTEM
                AgentMessageChannel.COMMENTARY,
                null,
                -> TimelineEntryKind.AGENT_COMMENTARY
            }
        }

    private val TimelineEntryKind.label: String
        get() = when (this) {
            TimelineEntryKind.USER_MESSAGE -> "You"
            TimelineEntryKind.AGENT_COMMENTARY -> "Agent commentary"
            TimelineEntryKind.AGENT_FINAL -> "Final answer"
            TimelineEntryKind.PLAN -> "Plan"
            TimelineEntryKind.REASONING_SUMMARY -> "Reasoning summary"
            TimelineEntryKind.TOOL -> "Tool"
            TimelineEntryKind.SYSTEM -> "System"
        }

    private fun ConnectionState?.toUiStatus(): ConnectionStatus = when (this) {
        null,
        is ConnectionState.Disconnected,
        -> ConnectionStatus.OFFLINE
        is ConnectionState.Connecting -> ConnectionStatus.CONNECTING
        is ConnectionState.Connected -> ConnectionStatus.ONLINE
        is ConnectionState.Reconnecting -> ConnectionStatus.RECONNECTING
        is ConnectionState.AwaitingIdentityTrust -> ConnectionStatus.IDENTITY_REVIEW
        is ConnectionState.Failed -> ConnectionStatus.FAILED
    }

    private fun ConnectionState?.toStatusDetail(): String? = when (this) {
        null -> null
        is ConnectionState.Disconnected -> when (reason) {
            ConnectionDisconnectReason.NOT_CONNECTED -> null
            ConnectionDisconnectReason.USER_REQUESTED -> "Disconnected by you"
            ConnectionDisconnectReason.AUTHENTICATION_FAILED -> "Authentication failed"
            ConnectionDisconnectReason.NETWORK_LOST -> "Network connection lost"
            ConnectionDisconnectReason.SERVER_IDENTITY_REJECTED -> "Server identity rejected"
            ConnectionDisconnectReason.CREDENTIAL_UNAVAILABLE -> "Credential unavailable"
            ConnectionDisconnectReason.BACKGROUND_SUSPENDED -> "Paused in the background"
            ConnectionDisconnectReason.RETRY_LIMIT_REACHED -> "Automatic retry limit reached"
            ConnectionDisconnectReason.PROVIDER_STOPPED -> "Connection provider stopped"
        }
        is ConnectionState.Connecting ->
            phase.name
                .lowercase()
                .replace('_', ' ')
                .replaceFirstChar { it.titlecase() }
        is ConnectionState.Connected -> null
        is ConnectionState.Reconnecting -> lastFailure.actionableMessage
        is ConnectionState.AwaitingIdentityTrust -> challenge.endpoint
        is ConnectionState.Failed -> failure.actionableMessage
    }

    private val RESUMABLE_SESSION_STATES = setOf(
        AgentSessionState.NOT_LOADED,
        AgentSessionState.FAILED,
        AgentSessionState.UNKNOWN,
    )
    private val INTERRUPTIBLE_SESSION_STATES = setOf(
        AgentSessionState.RUNNING,
        AgentSessionState.WAITING_FOR_APPROVAL,
    )

    private val POSITIVE_DECISIONS = setOf(
        AgentApprovalDecision.APPROVE_ONCE,
        AgentApprovalDecision.APPROVE_FOR_SESSION,
        AgentApprovalDecision.SUBMIT,
    )

    private const val MAX_RENDERED_TRANSCRIPT_CHARS = 32_000
}
