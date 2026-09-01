package com.example.agentrelay.ui.main

import dev.agentrelay.connection.api.ConnectionDisconnectReason
import dev.agentrelay.connection.api.ConnectionIdentityDisposition
import dev.agentrelay.connection.api.ConnectionProviderDescriptor
import dev.agentrelay.connection.api.ConnectionState
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.session.api.CachedTranscriptEntry
import dev.agentrelay.session.api.SessionActivity
import dev.agentrelay.session.api.SessionActivityType
import dev.agentrelay.session.api.SessionHubSnapshot
import dev.agentrelay.session.api.SessionLocator
import dev.agentrelay.session.api.SessionRecord
import dev.agentrelay.session.runtime.AgentEndpointPhase
import dev.agentrelay.session.runtime.SessionConnectionKey
import dev.agentrelay.session.runtime.SessionCoordinatorSnapshot
import java.security.MessageDigest

internal data class SessionHubUiModel(
    val availableConnectionProviders: List<String>,
    val connections: List<ConnectionUiModel>,
    val sessions: List<SessionUiModel>,
    val issues: List<CoordinatorIssueUiModel>,
    val selectedSession: SessionDetailUiModel?,
    val selectedSessionKey: String?,
    val operationError: String?,
    val isRefreshingProfiles: Boolean,
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

internal data class SessionDetailUiModel(
    val session: SessionUiModel,
    val activities: List<SessionActivityUiModel>,
    val transcript: List<TranscriptEntryUiModel>,
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
    val text: String,
    val wasTruncated: Boolean,
    val createdAtEpochMillis: Long?,
)

internal data class CoordinatorIssueUiModel(
    val id: String,
    val message: String,
    val recoverable: Boolean,
)

internal val SessionConnectionKey.stableUiKey: String
    get() = listOf(providerId.value, profileId.value)
        .joinToString(separator = "") { value -> "${value.length}:$value" }

internal val SessionLocator.stableUiKey: String
    get() = MessageDigest.getInstance("SHA-256")
        .digest(stableKey.encodeToByteArray())
        .joinToString(separator = "") { "%02x".format(it) }

internal object SessionHubUiMapper {
    fun map(
        coordinator: SessionCoordinatorSnapshot,
        sessions: SessionHubSnapshot,
        connectionProviders: List<ConnectionProviderDescriptor>,
        selectedSessionKey: String?,
        operationError: String?,
        busyConnectionKeys: Set<String>,
    ): SessionHubUiModel {
        val connectionProviderNames = connectionProviders.associate {
            it.id to it.displayName
        }
        val connections = coordinator.profiles.map { profile ->
            val key = SessionConnectionKey(profile.providerId, profile.id)
            val state = coordinator.connectionStates[key]
            val endpoints = coordinator.agentEndpoints.values.filter { it.key.connection == key }
            ConnectionUiModel(
                stableKey = key.stableUiKey,
                providerName = connectionProviderNames[profile.providerId] ?: profile.providerId.value,
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
            )
        }
        val sessionModels = sessions.recentSessions().map { record ->
            record.toUiModel(
                connectionProviderName = connectionProviderNames[
                    record.locator.connectionProviderId,
                ] ?: record.locator.connectionProviderId.value,
                activities = sessions.activities.filter { it.locator == record.locator },
            )
        }
        val selected = selectedSessionKey?.let { key ->
            val record = sessions.sessions.firstOrNull { it.locator.stableUiKey == key }
            record?.let { selectedRecord ->
                SessionDetailUiModel(
                    session = selectedRecord.toUiModel(
                        connectionProviderName = connectionProviderNames[
                            selectedRecord.locator.connectionProviderId,
                        ] ?: selectedRecord.locator.connectionProviderId.value,
                        activities = sessions.activities.filter { activity ->
                            activity.locator == selectedRecord.locator
                        },
                    ),
                    activities = sessions.activities
                        .asSequence()
                        .filter { activity -> activity.locator == selectedRecord.locator }
                        .sortedByDescending(SessionActivity::occurredAtEpochMillis)
                        .map { it.toUiModel() }
                        .toList(),
                    transcript = sessions.transcripts[selectedRecord.locator]
                        .orEmpty()
                        .map { it.toUiModel() },
                )
            }
        }
        return SessionHubUiModel(
            availableConnectionProviders = connectionProviders.map { it.displayName },
            connections = connections,
            sessions = sessionModels,
            issues = coordinator.issues.values
                .sortedByDescending { it.occurredAtEpochMillis }
                .map { CoordinatorIssueUiModel(it.id, it.actionableMessage, it.recoverable) },
            selectedSession = selected,
            selectedSessionKey = selectedSessionKey,
            operationError = operationError,
            isRefreshingProfiles = coordinator.isRefreshingProfiles,
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

    private fun SessionActivity.toUiModel() = SessionActivityUiModel(
        id = id,
        type = type,
        summary = summary,
        occurredAtEpochMillis = occurredAtEpochMillis,
        requiresAction = requiresAction,
        isRead = isRead,
    )

    private fun CachedTranscriptEntry.toUiModel() = TranscriptEntryUiModel(
        id = id,
        roleLabel = role.name.lowercase().replaceFirstChar { it.titlecase() },
        text = text.take(MAX_RENDERED_TRANSCRIPT_CHARS),
        wasTruncated = text.length > MAX_RENDERED_TRANSCRIPT_CHARS,
        createdAtEpochMillis = createdAtEpochMillis,
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

    private const val MAX_RENDERED_TRANSCRIPT_CHARS = 32_000
}
