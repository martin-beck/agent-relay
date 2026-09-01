package com.example.agentrelay.ui.main

import dev.agentrelay.connection.api.ConnectionCapability
import dev.agentrelay.connection.api.ConnectionChallengeId
import dev.agentrelay.connection.api.ConnectionDisconnectReason
import dev.agentrelay.connection.api.ConnectionFailure
import dev.agentrelay.connection.api.ConnectionFailureCategory
import dev.agentrelay.connection.api.ConnectionIdentityChallenge
import dev.agentrelay.connection.api.ConnectionIdentityDisposition
import dev.agentrelay.connection.api.ConnectionPhase
import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProfileSummary
import dev.agentrelay.connection.api.ConnectionProviderDescriptor
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.connection.api.ConnectionState
import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.AgentTranscriptRole
import dev.agentrelay.session.api.CachedTranscriptEntry
import dev.agentrelay.session.api.SessionActivity
import dev.agentrelay.session.api.SessionActivityType
import dev.agentrelay.session.api.SessionHubSnapshot
import dev.agentrelay.session.api.SessionLocator
import dev.agentrelay.session.api.SessionObservation
import dev.agentrelay.session.api.SessionRecord
import dev.agentrelay.session.runtime.SessionConnectionKey
import dev.agentrelay.session.runtime.SessionCoordinatorIssue
import dev.agentrelay.session.runtime.SessionCoordinatorIssueKind
import dev.agentrelay.session.runtime.SessionCoordinatorSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class SessionHubUiMapperTest {
    @Test
    fun providerScopedSessionIdentitiesDoNotCollide() {
        val localProvider = ConnectionProviderId("local.device")
        val sshProvider = ConnectionProviderId("ssh.secure-shell")
        val profileId = ConnectionProfileId("same-profile")
        val localLocator = locator(localProvider, profileId)
        val sshLocator = locator(sshProvider, profileId)
        val sessionSnapshot = SessionHubSnapshot(
            sessions = listOf(
                record(localLocator, "Local session", "This device"),
                record(sshLocator, "SSH session", "Trusted server"),
            ),
            activities = listOf(
                SessionActivity(
                    id = "approval-1",
                    locator = sshLocator,
                    type = SessionActivityType.APPROVAL_REQUIRED,
                    summary = "Review the remote command",
                    eventAnchorId = "approval",
                    occurredAtEpochMillis = 100,
                ),
            ),
            transcripts = mapOf(
                sshLocator to listOf(
                    CachedTranscriptEntry(
                        id = "message-1",
                        turnId = null,
                        role = AgentTranscriptRole.AGENT,
                        channel = AgentMessageChannel.FINAL,
                        text = "x".repeat(40_000),
                        createdAtEpochMillis = 90,
                    ),
                ),
            ),
        )
        val coordinator = SessionCoordinatorSnapshot(
            profiles = listOf(
                profile(localProvider, profileId, "This device"),
                profile(sshProvider, profileId, "Trusted server"),
            ),
        )

        val mapped = SessionHubUiMapper.map(
            coordinator = coordinator,
            sessions = sessionSnapshot,
            connectionProviders = listOf(
                descriptor(localProvider, "Local"),
                descriptor(sshProvider, "Secure Shell"),
            ),
            selectedSessionKey = sshLocator.stableUiKey,
            operationError = null,
            busyConnectionKeys = emptySet(),
        )

        assertEquals(2, mapped.sessions.size)
        assertNotEquals(mapped.sessions[0].stableKey, mapped.sessions[1].stableKey)
        assertEquals(64, mapped.sessions[0].stableKey.length)
        assertEquals("SSH session", mapped.selectedSession?.session?.title)
        assertEquals(1, mapped.selectedSession?.session?.requiresActionCount)
        assertEquals(32_000, mapped.selectedSession?.transcript?.single()?.text?.length)
        assertTrue(mapped.selectedSession?.transcript?.single()?.wasTruncated == true)
        assertFalse(mapped.sessions.single { it.title == "Local session" }.stableKey == sshLocator.stableUiKey)
    }

    @Test
    fun changedServerIdentityIsMappedAsAnExplicitReplacementDecision() {
        val providerId = ConnectionProviderId("ssh.secure-shell")
        val profileId = ConnectionProfileId("server")
        val key = SessionConnectionKey(providerId, profileId)
        val challenge = ConnectionIdentityChallenge(
            id = ConnectionChallengeId("challenge-1"),
            endpoint = "redacted.example",
            algorithm = "ssh-ed25519",
            sha256Fingerprint = "SHA256:abcdefghijklmnopqrstuv",
            disposition = ConnectionIdentityDisposition.CHANGED,
            previouslyTrustedFingerprints = listOf("SHA256:zyxwvutsrqponmlkjihgfe"),
        )

        val mapped = SessionHubUiMapper.map(
            coordinator = SessionCoordinatorSnapshot(
                profiles = listOf(profile(providerId, profileId, "Trusted server")),
                connectionStates = mapOf(
                    key to ConnectionState.AwaitingIdentityTrust(
                        challenge = challenge,
                        atEpochMillis = 200,
                    ),
                ),
            ),
            sessions = SessionHubSnapshot(),
            connectionProviders = listOf(descriptor(providerId, "Secure Shell")),
            selectedSessionKey = null,
            operationError = null,
            busyConnectionKeys = setOf(key.stableUiKey),
        )

        val connection = mapped.connections.single()
        assertEquals(ConnectionStatus.IDENTITY_REVIEW, connection.status)
        assertTrue(connection.identityChallenge?.isChangedIdentity == true)
        assertEquals(challenge.previouslyTrustedFingerprints, connection.identityChallenge?.previousFingerprints)
        assertTrue(connection.isBusy)
        assertTrue(connection.canDisconnect)
        assertFalse(connection.canConnect)
    }

    @Test
    fun connectionStatesAndFallbackLabelsRemainActionable() {
        val providerId = ConnectionProviderId("test.provider")
        val failure = ConnectionFailure(
            category = ConnectionFailureCategory.NETWORK,
            code = "TEST_FAILURE",
            actionableMessage = "Try the connection again.",
            recoverable = true,
        )
        val statesByProfileId = linkedMapOf(
            "user" to disconnected(ConnectionDisconnectReason.USER_REQUESTED),
            "authentication" to disconnected(ConnectionDisconnectReason.AUTHENTICATION_FAILED),
            "network" to disconnected(ConnectionDisconnectReason.NETWORK_LOST),
            "identity" to disconnected(ConnectionDisconnectReason.SERVER_IDENTITY_REJECTED),
            "credential" to disconnected(ConnectionDisconnectReason.CREDENTIAL_UNAVAILABLE),
            "background" to disconnected(ConnectionDisconnectReason.BACKGROUND_SUSPENDED),
            "retry" to disconnected(ConnectionDisconnectReason.RETRY_LIMIT_REACHED),
            "stopped" to disconnected(ConnectionDisconnectReason.PROVIDER_STOPPED),
            "connecting" to ConnectionState.Connecting(
                attempt = 1,
                phase = ConnectionPhase.OPENING_TRANSPORT,
                startedAtEpochMillis = 1,
            ),
            "connected" to ConnectionState.Connected(
                connectedAtEpochMillis = 1,
                lastHeartbeatAtEpochMillis = null,
                lastLatency = null,
            ),
            "reconnecting" to ConnectionState.Reconnecting(
                attempt = 2,
                delay = 1.seconds,
                retryAtEpochMillis = 2,
                lastFailure = failure,
            ),
            "failed" to ConnectionState.Failed(
                failure = failure,
                atEpochMillis = 2,
            ),
        )
        val profiles = statesByProfileId.keys.map { id ->
            profile(providerId, ConnectionProfileId(id), id)
        }
        val states = statesByProfileId.mapKeys { (id, _) ->
            SessionConnectionKey(providerId, ConnectionProfileId(id))
        }
        val sessionLocator = locator(providerId, ConnectionProfileId("session"))
        val fallbackSession = record(sessionLocator, null, "Fallback target")
        val mapped = SessionHubUiMapper.map(
            coordinator = SessionCoordinatorSnapshot(
                profiles = profiles,
                connectionStates = states,
                issues = mapOf(
                    "old" to issue("old", "Older issue", 1),
                    "new" to issue("new", "Newer issue", 2),
                ),
                isRefreshingProfiles = true,
            ),
            sessions = SessionHubSnapshot(sessions = listOf(fallbackSession)),
            connectionProviders = emptyList(),
            selectedSessionKey = sessionLocator.stableUiKey,
            operationError = "A safe operation failed.",
            busyConnectionKeys = setOf(
                SessionConnectionKey(
                    providerId,
                    ConnectionProfileId("connecting"),
                ).stableUiKey,
            ),
        )
        val connections = mapped.connections.associateBy(ConnectionUiModel::label)

        assertEquals("Disconnected by you", connections.getValue("user").statusDetail)
        assertEquals("Authentication failed", connections.getValue("authentication").statusDetail)
        assertEquals("Network connection lost", connections.getValue("network").statusDetail)
        assertEquals("Server identity rejected", connections.getValue("identity").statusDetail)
        assertEquals("Credential unavailable", connections.getValue("credential").statusDetail)
        assertEquals("Paused in the background", connections.getValue("background").statusDetail)
        assertEquals("Automatic retry limit reached", connections.getValue("retry").statusDetail)
        assertEquals("Connection provider stopped", connections.getValue("stopped").statusDetail)
        assertEquals(ConnectionStatus.CONNECTING, connections.getValue("connecting").status)
        assertEquals("Opening transport", connections.getValue("connecting").statusDetail)
        assertTrue(connections.getValue("connecting").isBusy)
        assertEquals(ConnectionStatus.ONLINE, connections.getValue("connected").status)
        assertEquals(ConnectionStatus.RECONNECTING, connections.getValue("reconnecting").status)
        assertEquals("Try the connection again.", connections.getValue("reconnecting").statusDetail)
        assertEquals(ConnectionStatus.FAILED, connections.getValue("failed").status)
        assertEquals("Try the connection again.", connections.getValue("failed").statusDetail)
        assertEquals("test.provider", connections.getValue("connected").providerName)
        assertEquals("Codex session", mapped.sessions.single().title)
        assertEquals("test.provider", mapped.selectedSession?.session?.connectionProviderName)
        assertEquals(listOf("new", "old"), mapped.issues.map(CoordinatorIssueUiModel::id))
        assertEquals("A safe operation failed.", mapped.operationError)
        assertTrue(mapped.isRefreshingProfiles)
    }
}

private fun disconnected(reason: ConnectionDisconnectReason) = ConnectionState.Disconnected(
    reason = reason,
    atEpochMillis = 1,
)

private fun issue(
    id: String,
    message: String,
    occurredAtEpochMillis: Long,
) = SessionCoordinatorIssue(
    id = id,
    kind = SessionCoordinatorIssueKind.CONNECTION_SETUP,
    connection = null,
    agentProviderId = null,
    actionableMessage = message,
    recoverable = true,
    occurredAtEpochMillis = occurredAtEpochMillis,
)

private fun locator(
    connectionProviderId: ConnectionProviderId,
    profileId: ConnectionProfileId,
) = SessionLocator(
    connectionProviderId = connectionProviderId,
    connectionProfileId = profileId,
    agentProviderId = AgentProviderId("agent.codex"),
    agentSessionId = AgentSessionId("same-agent-session"),
)

private fun record(
    locator: SessionLocator,
    title: String?,
    connectionLabel: String,
) = SessionRecord(
    observation = SessionObservation(
        locator = locator,
        connectionLabel = connectionLabel,
        connectionTarget = connectionLabel,
        projectPath = "/workspace",
        agentProviderLabel = "Codex",
        title = title,
        preview = "Session preview",
        agentState = AgentSessionState.IDLE,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    ),
)

private fun profile(
    providerId: ConnectionProviderId,
    profileId: ConnectionProfileId,
    label: String,
) = ConnectionProfileSummary(
    id = profileId,
    providerId = providerId,
    label = label,
    target = label,
    authenticationLabel = null,
)

private fun descriptor(
    id: ConnectionProviderId,
    name: String,
) = ConnectionProviderDescriptor(
    id = id,
    displayName = name,
    providerVersion = "1.0",
    capabilities = setOf(ConnectionCapability.MULTIPLEXED_PROCESSES),
)
