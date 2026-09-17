/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import com.example.agentrelay.R
import dev.agentrelay.connection.api.ConnectionCapability
import dev.agentrelay.connection.api.ConnectionDisconnectReason
import dev.agentrelay.connection.api.ConnectionFailureMessage
import dev.agentrelay.connection.api.ConnectionFailureMessageKind
import dev.agentrelay.connection.api.ConnectionIdentityDisposition
import dev.agentrelay.connection.api.ConnectionProviderDescriptor
import dev.agentrelay.connection.api.ConnectionState
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentApprovalType
import dev.agentrelay.provider.api.AgentCapability
import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.AgentTranscriptRole
import dev.agentrelay.session.api.CachedTranscriptEntry
import dev.agentrelay.session.api.SessionActivity
import dev.agentrelay.session.api.SessionActivitySummary
import dev.agentrelay.session.api.SessionActivitySummaryKind
import dev.agentrelay.session.api.SessionActivityType
import dev.agentrelay.session.api.SessionActionRequest
import dev.agentrelay.session.api.SessionActionRisk
import dev.agentrelay.session.api.SessionActionState
import dev.agentrelay.session.api.SessionArtifact
import dev.agentrelay.session.api.SessionDraft
import dev.agentrelay.session.api.SessionHubSnapshot
import dev.agentrelay.session.api.SessionLocator
import dev.agentrelay.session.api.SessionPresentationText
import dev.agentrelay.session.api.SessionPresentationTextKind
import dev.agentrelay.session.api.SessionRecord
import dev.agentrelay.session.runtime.AgentEndpointKey
import dev.agentrelay.session.runtime.AgentEndpointStatus
import dev.agentrelay.session.runtime.AgentEndpointPhase
import dev.agentrelay.session.runtime.SessionConnectionKey
import dev.agentrelay.session.runtime.SessionCoordinatorIssue
import dev.agentrelay.session.runtime.SessionCoordinatorIssueKind
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
    val operationError: UiMessage?,
    val isRefreshingProfiles: Boolean,
    val manageableConnectionProviders: List<ConnectionProviderUiModel> = emptyList(),
    val sessionLaunchers: List<SessionLauncherUiModel> = emptyList(),
    val attentionActions: List<SessionActionUiModel> = emptyList(),
    val notificationActivities: List<SessionActivity> = emptyList(),
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
    val statusDetail: UiMessage?,
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
    val title: UiMessage,
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
    val lastLlmResponseAtEpochMillis: Long? = null,
)

internal enum class SessionListSortOption {
    LAST_APP_INTERACTION,
    LAST_LLM_RESPONSE,
}

internal fun sortSessionList(
    sessions: List<SessionUiModel>,
    option: SessionListSortOption,
): List<SessionUiModel> = sessions.sortedWith(
    compareByDescending<SessionUiModel> { it.isPinned }
        .thenByDescending {
            when (option) {
                SessionListSortOption.LAST_APP_INTERACTION -> it.lastActivityAtEpochMillis
                SessionListSortOption.LAST_LLM_RESPONSE ->
                    it.lastLlmResponseAtEpochMillis ?: it.lastActivityAtEpochMillis
            } ?: Long.MIN_VALUE
        }
        .thenBy(SessionUiModel::stableKey),
)

internal data class AttentionSurfaceBuckets(
    val needsAttention: List<SessionUiModel>,
    val changed: List<SessionUiModel>,
    val running: List<SessionUiModel>,
    val recentlyCompleted: List<SessionUiModel>,
)

internal fun attentionSurfaceBuckets(sessions: List<SessionUiModel>): AttentionSurfaceBuckets {
    val needsAttention = ArrayList<SessionUiModel>()
    val changed = ArrayList<SessionUiModel>()
    val running = ArrayList<SessionUiModel>()
    val recentlyCompleted = ArrayList<SessionUiModel>()
    sessions.forEach { session ->
        val bucket = when {
            session.requiresActionCount > 0 ||
                session.agentState == AgentSessionState.WAITING_FOR_APPROVAL ->
                needsAttention
            session.agentState == AgentSessionState.RUNNING -> running
            session.unreadCount > 0 -> changed
            else -> recentlyCompleted
        }
        bucket += session
    }
    return AttentionSurfaceBuckets(
        needsAttention = needsAttention,
        changed = changed,
        running = running,
        recentlyCompleted = recentlyCompleted,
    )
}

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
    val artifacts: List<SessionArtifactUiModel> = emptyList(),
    val canRefreshArtifacts: Boolean = false,
    val isRefreshingArtifacts: Boolean = false,
)

internal data class ArtifactTransferUiState(
    val bytesWritten: Long = 0L,
    val totalBytes: Long? = null,
    val isRunning: Boolean = true,
    val isComplete: Boolean = false,
) {
    init {
        require(bytesWritten >= 0L)
        require(totalBytes == null || totalBytes >= 0L)
        require(totalBytes == null || bytesWritten <= totalBytes)
        require(!isComplete || !isRunning)
    }
}

internal enum class SessionArtifactAvailabilityStatus {
    RECONNECT,
    UNSUPPORTED,
    READY,
    DELETED,
    OUTSIDE_WORKSPACE,
    WORKSPACE_UNKNOWN,
}

internal data class SessionArtifactUiModel(
    val stableKey: String,
    val sessionKey: String,
    val displayPath: String?,
    val changeKind: AgentFileChangeKind,
    val availabilityStatus: SessionArtifactAvailabilityStatus,
    val suggestedFileName: String,
    val isDownloadable: Boolean,
    val canSave: Boolean,
    val bytesWritten: Long,
    val totalBytes: Long?,
    val isExporting: Boolean,
    val isExportComplete: Boolean,
)

internal data class SessionActionUiModel(
    val stableKey: String,
    val sessionKey: String,
    val title: UiMessage,
    val type: AgentApprovalType,
    val description: String?,
    val command: String?,
    val scope: String?,
    val connectionLabel: String,
    val connectionProviderName: String,
    val connectionTarget: String,
    val agentProviderLabel: String,
    val sessionTitle: UiMessage,
    val questions: List<SessionQuestionUiModel>,
    val decisions: List<SessionDecisionUiModel>,
    val risks: List<SessionActionRisk>,
    val state: SessionActionState,
    val completedDecision: AgentApprovalDecision?,
    val additionalConfirmationGiven: Boolean,
    val isBusy: Boolean,
)

internal data class SessionQuestionUiModel(
    val stableKey: String,
    val header: String?,
    val prompt: UiMessage,
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
    val statusMessage: UiMessage? =
        UiMessage.Localized(R.string.session_composer_status_connect),
)

internal data class SessionActivityUiModel(
    val id: String,
    val type: SessionActivityType,
    val summary: UiMessage,
    val occurredAtEpochMillis: Long,
    val requiresAction: Boolean,
    val isRead: Boolean,
)

internal enum class SessionActivityTopic {
    TRANSPORT,
    AGENT_FEEDBACK,
    USER_DECISIONS,
    COMPLETION,
    BLOCKED_TASKS,
}

internal enum class SessionActivitySeverity {
    INFO,
    ACTION_REQUIRED,
    WARNING,
    ERROR,
}

internal data class SessionActivitySectionUiModel(
    val topic: SessionActivityTopic,
    val activities: List<SessionActivityUiModel>,
    val isDiagnostic: Boolean,
)

internal val SessionActivityUiModel.topic: SessionActivityTopic
    get() = when (type) {
        SessionActivityType.RECONNECTED -> SessionActivityTopic.TRANSPORT
        SessionActivityType.APPROVAL_REQUIRED,
        SessionActivityType.QUESTION,
        -> SessionActivityTopic.USER_DECISIONS
        SessionActivityType.TURN_COMPLETED -> SessionActivityTopic.COMPLETION
        SessionActivityType.NEW_OUTPUT,
        SessionActivityType.FAILURE,
        -> SessionActivityTopic.AGENT_FEEDBACK
    }

internal val SessionActivityUiModel.severity: SessionActivitySeverity
    get() = when {
        requiresAction -> SessionActivitySeverity.ACTION_REQUIRED
        type == SessionActivityType.FAILURE -> SessionActivitySeverity.ERROR
        else -> SessionActivitySeverity.INFO
    }

internal fun activitySections(
    activities: List<SessionActivityUiModel>,
): List<SessionActivitySectionUiModel> = SessionActivityTopic.entries.map { topic ->
    SessionActivitySectionUiModel(
        topic = topic,
        activities = activities.filter { it.topic == topic },
        isDiagnostic = topic == SessionActivityTopic.TRANSPORT,
    )
}

internal fun highLevelActivities(
    activities: List<SessionActivityUiModel>,
): List<SessionActivityUiModel> = activities.filterNot {
    it.topic == SessionActivityTopic.TRANSPORT
}

internal data class TranscriptEntryUiModel(
    val id: String,
    val roleLabel: UiMessage,
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
    val message: UiMessage,
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

internal val SessionArtifact.stableUiKey: String
    get() = stableHash(locator.stableKey, id)

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
        operationError: UiMessage?,
        busyConnectionKeys: Set<String>,
        busySessionKeys: Set<String> = emptySet(),
        busyActionKeys: Set<String> = emptySet(),
        draftOverrides: Map<String, SessionDraft> = emptyMap(),
        artifactTransferStates: Map<String, ArtifactTransferUiState> = emptyMap(),
        refreshingArtifactSessionKeys: Set<String> = emptySet(),
    ): SessionHubUiModel {
        val providerNames = connectionProviders.associate { it.id to it.displayName }
        val manageableProviders = connectionProviders
            .filter { ConnectionCapability.PROFILE_MANAGEMENT in it.capabilities }
            .associateBy(ConnectionProviderDescriptor::id)
        val recentSessions = sessions.recentSessions()
        val actions = actionModels(sessions, providerNames, busyActionKeys)
        return SessionHubUiModel(
            availableConnectionProviders = connectionProviders.map { it.displayName },
            connections = connectionModels(
                coordinator,
                providerNames,
                manageableProviders.keys,
                busyConnectionKeys,
            ),
            sessions = sessionModels(sessions, recentSessions, providerNames),
            issues = coordinator.issues.values
                .sortedByDescending { it.occurredAtEpochMillis }
                .map { it.toUiModel() },
            selectedSession = selectedDetail(
                selectedSessionKey,
                coordinator,
                sessions,
                providerNames,
                actions,
                busySessionKeys,
                draftOverrides,
                artifactTransferStates,
                refreshingArtifactSessionKeys,
            ),
            selectedSessionKey = selectedSessionKey,
            operationError = operationError,
            isRefreshingProfiles = coordinator.isRefreshingProfiles,
            manageableConnectionProviders = manageableProviders.values
                .map { ConnectionProviderUiModel(it.id.value, it.displayName) }
                .sortedBy(ConnectionProviderUiModel::name),
            sessionLaunchers = sessionLaunchers(coordinator, recentSessions, providerNames),
            attentionActions = actions.filter { it.state != SessionActionState.RESOLVED },
            notificationActivities = sessions.activities,
        )
    }

    private fun SessionCoordinatorIssue.toUiModel() = CoordinatorIssueUiModel(
        id = id,
        message = when (kind) {
            SessionCoordinatorIssueKind.PROFILE_DISCOVERY ->
                UiMessage.Localized(
                    R.string.session_issue_profile_discovery,
                    listOf(requireNotNull(connectionProviderLabel)),
                )
            SessionCoordinatorIssueKind.CONNECTION_SETUP ->
                UiMessage.Localized(
                    R.string.session_issue_connection_setup,
                    listOf(requireNotNull(connectionLabel)),
                )
            SessionCoordinatorIssueKind.PROVIDER_SYNCHRONIZATION ->
                UiMessage.Localized(
                    R.string.session_issue_provider_synchronization,
                    listOf(
                        requireNotNull(agentProviderLabel),
                        requireNotNull(connectionLabel),
                    ),
                )
            SessionCoordinatorIssueKind.SESSION_PERSISTENCE ->
                UiMessage.Localized(
                    R.string.session_issue_session_persistence,
                    listOf(requireNotNull(agentProviderLabel)),
                )
        },
        recoverable = recoverable,
    )

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
        recentSessions: List<SessionRecord>,
        providerNames: Map<dev.agentrelay.connection.api.ConnectionProviderId, String>,
    ): List<SessionUiModel> {
        val activitiesByLocator = sessions.activities.groupBy(SessionActivity::locator)
        return recentSessions.map { record ->
            record.toUiModel(
                connectionProviderName = providerNames[record.locator.connectionProviderId]
                    ?: record.locator.connectionProviderId.value,
                activities = activitiesByLocator[record.locator].orEmpty(),
            )
        }
    }

    private fun sessionLaunchers(
        coordinator: SessionCoordinatorSnapshot,
        recentSessions: List<SessionRecord>,
        providerNames: Map<dev.agentrelay.connection.api.ConnectionProviderId, String>,
    ): List<SessionLauncherUiModel> = coordinator.agentEndpoints.values
        .asSequence()
        .filter { endpoint ->
            endpoint.phase == AgentEndpointPhase.READY &&
                AgentCapability.SESSION_START in endpoint.descriptor.capabilities
        }
        .mapNotNull { endpoint ->
            val profile = coordinator.profile(endpoint.key.connection) ?: return@mapNotNull null
            val suggestedWorkingDirectory = recentSessions
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
        artifactTransferStates: Map<String, ArtifactTransferUiState>,
        refreshingArtifactSessionKeys: Set<String>,
    ): SessionDetailUiModel? {
        val record = sessions.sessions.firstOrNull {
            it.locator.stableUiKey == selectedSessionKey
        } ?: return null
        val activities = sessions.activities.filter { it.locator == record.locator }
        val artifactEndpoint = artifactEndpoint(coordinator, record.locator)
        val providerReady = artifactEndpoint?.phase == AgentEndpointPhase.READY
        val canRefreshArtifacts =
            providerReady &&
                AgentCapability.FILE_CHANGES in artifactEndpoint.descriptor.capabilities
        val fileAccessAvailable = providerReady && artifactEndpoint.fileAccessAvailable
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
            artifacts = sessions.sessionArtifacts(record.locator)
                .sortedByDescending(SessionArtifact::observedAtEpochMillis)
                .map { artifact ->
                    SessionArtifactUiMapper.map(
                        artifact = artifact,
                        transfer = artifactTransferStates[artifact.stableUiKey],
                        providerReady = providerReady,
                        fileAccessAvailable = fileAccessAvailable,
                    )
                },
            canRefreshArtifacts = canRefreshArtifacts,
            isRefreshingArtifacts =
            record.locator.stableUiKey in refreshingArtifactSessionKeys,
        )
    }

    private fun artifactEndpoint(
        coordinator: SessionCoordinatorSnapshot,
        locator: SessionLocator,
    ): AgentEndpointStatus? = coordinator.agentEndpoints.values.firstOrNull { endpoint ->
        endpoint.key.connection.providerId == locator.connectionProviderId &&
            endpoint.key.connection.profileId == locator.connectionProfileId &&
            endpoint.key.agentProviderId == locator.agentProviderId
    }

    private fun SessionRecord.toUiModel(
        connectionProviderName: String,
        activities: List<SessionActivity>,
    ) = SessionUiModel(
        stableKey = locator.stableUiKey,
        title = titleMessage(),
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
        lastLlmResponseAtEpochMillis = activities
            .filter { it.type == SessionActivityType.NEW_OUTPUT }
            .maxOfOrNull(SessionActivity::occurredAtEpochMillis),
    )

    private fun SessionActionRequest.toUiModel(
        record: SessionRecord,
        connectionProviderName: String,
        isBusy: Boolean,
    ) = SessionActionUiModel(
        stableKey = id,
        sessionKey = locator.stableUiKey,
        title = title.toUiMessage(),
        type = type,
        description = description,
        command = command,
        scope = workingDirectory ?: record.observation.projectPath,
        connectionLabel = record.observation.connectionLabel,
        connectionProviderName = connectionProviderName,
        connectionTarget = record.observation.connectionTarget,
        agentProviderLabel = record.observation.agentProviderLabel,
        sessionTitle = record.titleMessage(),
        questions = questions.map { question ->
            SessionQuestionUiModel(
                stableKey = question.id,
                header = question.header,
                prompt = question.prompt.toUiMessage(),
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
                    requiresConfirmation = requiresAdditionalConfirmation(candidate),
                    isPositive = candidate in POSITIVE_DECISIONS,
                )
            },
        risks = riskReasons.sortedBy(SessionActionRisk::ordinal),
        state = state,
        completedDecision = decision,
        additionalConfirmationGiven = additionalConfirmationGiven,
        isBusy = isBusy || state == SessionActionState.DELIVERING,
    )

    private fun SessionActivity.toUiModel() = SessionActivityUiModel(
        id = id,
        type = type,
        summary = summary.toUiMessage(),
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
    ): UiMessage? = when {
        isBusy -> UiMessage.Localized(R.string.session_composer_status_applying)
        !endpointReady ->
            UiMessage.Localized(
                R.string.session_composer_status_connect_draft,
                listOf(observation.connectionLabel),
            )
        observation.agentState == AgentSessionState.WAITING_FOR_APPROVAL ->
            UiMessage.Localized(R.string.session_composer_status_pending_action)
        observation.agentState in RESUMABLE_SESSION_STATES ->
            if (canResume) {
                UiMessage.Localized(R.string.session_composer_status_resume)
            } else {
                UiMessage.Localized(R.string.session_composer_status_resume_unsupported)
            }
        observation.agentState == AgentSessionState.RUNNING &&
            AgentCapability.ACTIVE_TURN_STEERING !in capabilities ->
            UiMessage.Localized(R.string.session_composer_status_steering_unsupported)
        !canAcceptInput -> UiMessage.Localized(R.string.session_composer_status_read_only)
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

    private val TimelineEntryKind.label: UiMessage
        get() = UiMessage.Localized(
            when (this) {
                TimelineEntryKind.USER_MESSAGE -> R.string.session_timeline_role_user
                TimelineEntryKind.AGENT_COMMENTARY ->
                    R.string.session_timeline_role_agent_commentary
                TimelineEntryKind.AGENT_FINAL -> R.string.session_timeline_role_agent_final
                TimelineEntryKind.PLAN -> R.string.session_timeline_role_plan
                TimelineEntryKind.REASONING_SUMMARY ->
                    R.string.session_timeline_role_reasoning_summary
                TimelineEntryKind.TOOL -> R.string.session_timeline_role_tool
                TimelineEntryKind.SYSTEM -> R.string.session_timeline_role_system
            },
        )

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

    private fun ConnectionState?.toStatusDetail(): UiMessage? = when (this) {
        null -> null
        is ConnectionState.Disconnected -> reason.localizedStatusDetail()
        is ConnectionState.Connecting ->
            UiMessage.Localized(
                when (phase) {
                    dev.agentrelay.connection.api.ConnectionPhase.PREPARING ->
                        R.string.connection_phase_preparing
                    dev.agentrelay.connection.api.ConnectionPhase.OPENING_TRANSPORT ->
                        R.string.connection_phase_opening_transport
                    dev.agentrelay.connection.api.ConnectionPhase.VERIFYING_SERVER_IDENTITY ->
                        R.string.connection_phase_verifying_server_identity
                    dev.agentrelay.connection.api.ConnectionPhase.AUTHENTICATING ->
                        R.string.connection_phase_authenticating
                },
            )
        is ConnectionState.Connected -> null
        is ConnectionState.Reconnecting -> lastFailure.message.toUiMessage()
        is ConnectionState.AwaitingIdentityTrust -> UiMessage.Verbatim(challenge.endpoint)
        is ConnectionState.Failed -> failure.message.toUiMessage()
    }

    private fun ConnectionDisconnectReason.localizedStatusDetail(): UiMessage? = when (this) {
        ConnectionDisconnectReason.NOT_CONNECTED -> null
        ConnectionDisconnectReason.USER_REQUESTED ->
            UiMessage.Localized(R.string.connection_disconnect_user_requested)
        ConnectionDisconnectReason.AUTHENTICATION_FAILED ->
            UiMessage.Localized(R.string.connection_disconnect_authentication_failed)
        ConnectionDisconnectReason.NETWORK_LOST ->
            UiMessage.Localized(R.string.connection_disconnect_network_lost)
        ConnectionDisconnectReason.SERVER_IDENTITY_REJECTED ->
            UiMessage.Localized(R.string.connection_disconnect_server_identity_rejected)
        ConnectionDisconnectReason.CREDENTIAL_UNAVAILABLE ->
            UiMessage.Localized(R.string.connection_disconnect_credential_unavailable)
        ConnectionDisconnectReason.BACKGROUND_SUSPENDED ->
            UiMessage.Localized(R.string.connection_disconnect_background_suspended)
        ConnectionDisconnectReason.RETRY_LIMIT_REACHED ->
            UiMessage.Localized(R.string.connection_disconnect_retry_limit_reached)
        ConnectionDisconnectReason.PROVIDER_STOPPED ->
            UiMessage.Localized(R.string.connection_disconnect_provider_stopped)
    }

    private fun SessionRecord.titleMessage(): UiMessage =
        observation.title
            ?.takeIf(String::isNotBlank)
            ?.let(UiMessage::Verbatim)
            ?: UiMessage.Localized(
                R.string.session_title_fallback,
                listOf(observation.agentProviderLabel),
            )

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

private fun ConnectionFailureMessage.toUiMessage(): UiMessage = when (this) {
    is ConnectionFailureMessage.Generated -> UiMessage.Localized(
        when (kind) {
            ConnectionFailureMessageKind.PROFILE_PREPARATION_FAILED ->
                R.string.connection_failure_profile_preparation
        },
    )
    is ConnectionFailureMessage.Verbatim -> UiMessage.Verbatim(text)
}

private fun SessionPresentationText.toUiMessage(): UiMessage = when (this) {
    is SessionPresentationText.Verbatim -> UiMessage.Verbatim(text)
    is SessionPresentationText.Generated -> UiMessage.Localized(
        when (kind) {
            SessionPresentationTextKind.ACTION_REVIEW_REQUIRED ->
                R.string.session_action_title_review_required
            SessionPresentationTextKind.AGENT_QUESTION -> R.string.session_question_prompt_fallback
        },
    )
}

private fun SessionActivitySummary.toUiMessage(): UiMessage = when (this) {
    is SessionActivitySummary.Verbatim -> UiMessage.Verbatim(text)
    is SessionActivitySummary.Generated -> when (kind) {
        SessionActivitySummaryKind.NEW_AGENT_OUTPUT ->
            UiMessage.Localized(R.string.session_activity_summary_new_agent_output)
        SessionActivitySummaryKind.TOOL_FAILED ->
            UiMessage.Localized(R.string.session_activity_summary_tool_failed)
        SessionActivitySummaryKind.NAMED_TOOL_FAILED ->
            UiMessage.Localized(
                R.string.session_activity_summary_named_tool_failed,
                listOf(requireNotNull(argument)),
            )
        SessionActivitySummaryKind.AGENT_TURN_COMPLETED ->
            UiMessage.Localized(R.string.session_activity_summary_agent_turn_completed)
        SessionActivitySummaryKind.AGENT_TURN_FAILED ->
            UiMessage.Localized(R.string.session_activity_summary_agent_turn_failed)
        SessionActivitySummaryKind.AGENT_PROVIDER_FAILED ->
            UiMessage.Localized(R.string.session_activity_summary_agent_provider_failed)
        SessionActivitySummaryKind.AGENT_QUESTION_REQUIRES_ANSWER ->
            UiMessage.Localized(R.string.session_activity_summary_agent_question_requires_answer)
        SessionActivitySummaryKind.AGENT_APPROVAL_REQUIRED ->
            UiMessage.Localized(R.string.session_activity_summary_agent_approval_required)
        SessionActivitySummaryKind.CONNECTION_RECONNECTED ->
            UiMessage.Localized(
                R.string.session_activity_summary_connection_reconnected,
                listOf(requireNotNull(argument)),
            )
    }
}
