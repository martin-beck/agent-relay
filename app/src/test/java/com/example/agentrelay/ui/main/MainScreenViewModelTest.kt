package com.example.agentrelay.ui.main

import androidx.lifecycle.viewModelScope
import com.example.agentrelay.MainDispatcherRule
import com.example.agentrelay.data.SessionHubRuntime
import dev.agentrelay.connection.api.ConnectionCapability
import dev.agentrelay.connection.api.ConnectionChallengeId
import dev.agentrelay.connection.api.ConnectionDisconnectReason
import dev.agentrelay.connection.api.ConnectionIdentityChallenge
import dev.agentrelay.connection.api.ConnectionIdentityDecision
import dev.agentrelay.connection.api.ConnectionIdentityDisposition
import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProfileSummary
import dev.agentrelay.connection.api.ConnectionProviderDescriptor
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.connection.api.ConnectionState
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.session.api.SessionHubSnapshot
import dev.agentrelay.session.api.SessionLocator
import dev.agentrelay.session.api.SessionObservation
import dev.agentrelay.session.api.SessionRecord
import dev.agentrelay.session.runtime.SessionConnectionKey
import dev.agentrelay.session.runtime.SessionCoordinatorSnapshot
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainScreenViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun initializationRoutesProviderScopedActionsAndSelection() = runTest {
        val localId = ConnectionProviderId("local.device")
        val sshId = ConnectionProviderId("ssh.secure-shell")
        val localKey = SessionConnectionKey(localId, ConnectionProfileId("shared"))
        val sshKey = SessionConnectionKey(sshId, ConnectionProfileId("shared"))
        val locator = SessionLocator(
            connectionProviderId = sshId,
            connectionProfileId = sshKey.profileId,
            agentProviderId = AgentProviderId("agent.codex"),
            agentSessionId = AgentSessionId("same-session-id"),
        )
        val runtime = FakeSessionHubRuntime(
            providers = listOf(
                descriptor(localId, "Local"),
                descriptor(sshId, "Secure Shell"),
            ),
            coordinator = SessionCoordinatorSnapshot(
                profiles = listOf(
                    profile(localKey, "This device"),
                    profile(sshKey, "Trusted server"),
                ),
                connectionStates = mapOf(
                    localKey to disconnected(),
                    sshKey to disconnected(),
                ),
            ),
            sessions = SessionHubSnapshot(
                sessions = listOf(session(locator)),
            ),
        )
        val viewModel = MainScreenViewModel { runtime }

        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        val ready = viewModel.uiState.value as MainScreenUiState.Ready
        assertEquals(1, runtime.refreshCount)
        assertEquals(listOf("Local", "Secure Shell"), ready.hub.availableConnectionProviders)
        assertEquals(2, ready.hub.connections.size)
        assertFalse(ready.hub.connections[0].stableKey == ready.hub.connections[1].stableKey)

        val sshConnection = ready.hub.connections.single { it.providerName == "Secure Shell" }
        viewModel.connect(sshConnection.stableKey)
        viewModel.selectSession(ready.hub.sessions.single().stableKey)
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(sshKey), runtime.connected)
        assertEquals(listOf(locator), runtime.markedRead)
        val selected = (viewModel.uiState.value as MainScreenUiState.Ready).hub.selectedSession
        assertEquals("Trusted server", selected?.session?.connectionLabel)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun recoverableFailuresAndIdentityDecisionsRemainActionable() = runTest {
        val providerId = ConnectionProviderId("ssh.secure-shell")
        val key = SessionConnectionKey(providerId, ConnectionProfileId("test-profile"))
        val challenge = ConnectionIdentityChallenge(
            id = ConnectionChallengeId("challenge-1"),
            endpoint = "Test endpoint",
            algorithm = "ssh-ed25519",
            sha256Fingerprint = "SHA256:test-fingerprint",
            disposition = ConnectionIdentityDisposition.UNKNOWN,
            previouslyTrustedFingerprints = emptyList(),
        )
        val locator = SessionLocator(
            connectionProviderId = providerId,
            connectionProfileId = key.profileId,
            agentProviderId = AgentProviderId("agent.codex"),
            agentSessionId = AgentSessionId("test-session"),
        )
        val runtime = FakeSessionHubRuntime(
            providers = listOf(descriptor(providerId, "Secure Shell")),
            coordinator = SessionCoordinatorSnapshot(
                profiles = listOf(profile(key, "Test profile")),
                connectionStates = mapOf(
                    key to ConnectionState.AwaitingIdentityTrust(
                        challenge = challenge,
                        atEpochMillis = 1,
                    ),
                ),
            ),
            sessions = SessionHubSnapshot(sessions = listOf(session(locator))),
        )
        runtime.failRefresh = true
        val viewModel = MainScreenViewModel { runtime }

        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            "Connection profiles could not be refreshed.",
            (viewModel.uiState.value as MainScreenUiState.Ready).hub.operationError,
        )

        runtime.failRefresh = false
        viewModel.retryInitialization()
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, runtime.refreshCount)
        assertEquals(
            null,
            (viewModel.uiState.value as MainScreenUiState.Ready).hub.operationError,
        )

        val stableConnectionKey =
            (viewModel.uiState.value as MainScreenUiState.Ready).hub.connections.single().stableKey
        viewModel.trustIdentity(stableConnectionKey, replaceChangedIdentity = false)
        viewModel.trustIdentity(stableConnectionKey, replaceChangedIdentity = true)
        viewModel.rejectIdentity(stableConnectionKey)
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(
            listOf(
                ConnectionIdentityDecision.TRUST_FIRST_USE,
                ConnectionIdentityDecision.REPLACE_CHANGED,
                ConnectionIdentityDecision.REJECT,
            ),
            runtime.identityDecisions,
        )

        runtime.failDisconnect = true
        viewModel.disconnect(stableConnectionKey)
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            "The connection could not be closed cleanly.",
            (viewModel.uiState.value as MainScreenUiState.Ready).hub.operationError,
        )
        assertFalse(
            (viewModel.uiState.value as MainScreenUiState.Ready)
                .hub.connections.single().isBusy,
        )

        viewModel.connect("missing-connection")
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            "That connection profile is no longer available.",
            (viewModel.uiState.value as MainScreenUiState.Ready).hub.operationError,
        )
        viewModel.clearOperationError()

        runtime.failMarkRead = true
        val stableSessionKey =
            (viewModel.uiState.value as MainScreenUiState.Ready).hub.sessions.single().stableKey
        viewModel.selectSession(stableSessionKey)
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            "The session read state could not be saved.",
            (viewModel.uiState.value as MainScreenUiState.Ready).hub.operationError,
        )
        viewModel.clearSelection()
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            null,
            (viewModel.uiState.value as MainScreenUiState.Ready).hub.selectedSession,
        )

        viewModel.selectSession("missing-session")
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            "That session is no longer available.",
            (viewModel.uiState.value as MainScreenUiState.Ready).hub.operationError,
        )

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun initializationFailureDoesNotExposeThrowableDetails() = runTest {
        val viewModel = MainScreenViewModel {
            error("private-host.example: secret credential failed")
        }

        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        val failed = viewModel.uiState.value as MainScreenUiState.FatalError
        assertTrue(failed.message.contains("Secure session state"))
        assertFalse(failed.message.contains("private-host"))
        assertFalse(failed.message.contains("credential failed"))

        viewModel.viewModelScope.cancel()
    }
}

private class FakeSessionHubRuntime(
    providers: List<ConnectionProviderDescriptor>,
    coordinator: SessionCoordinatorSnapshot,
    sessions: SessionHubSnapshot,
) : SessionHubRuntime {
    override val connectionProviders = providers
    override val coordinatorSnapshot: StateFlow<SessionCoordinatorSnapshot> =
        MutableStateFlow(coordinator)
    override val sessionSnapshot: StateFlow<SessionHubSnapshot> =
        MutableStateFlow(sessions)
    var failRefresh = false
    var failDisconnect = false
    var failMarkRead = false
    var refreshCount = 0
    val connected = mutableListOf<SessionConnectionKey>()
    val markedRead = mutableListOf<SessionLocator>()
    val identityDecisions = mutableListOf<ConnectionIdentityDecision>()

    override suspend fun refreshProfiles() {
        refreshCount += 1
        check(!failRefresh)
    }

    override suspend fun connect(key: SessionConnectionKey) {
        connected += key
    }

    override suspend fun disconnect(key: SessionConnectionKey) {
        check(!failDisconnect)
    }

    override suspend fun resolveIdentityChallenge(
        key: SessionConnectionKey,
        challengeId: ConnectionChallengeId,
        decision: ConnectionIdentityDecision,
    ): Boolean {
        identityDecisions += decision
        return true
    }

    override suspend fun markSessionRead(locator: SessionLocator) {
        check(!failMarkRead)
        markedRead += locator
    }
}

private fun descriptor(
    id: ConnectionProviderId,
    name: String,
) = ConnectionProviderDescriptor(
    id = id,
    displayName = name,
    providerVersion = "1.0",
    capabilities = setOf(ConnectionCapability.MULTIPLEXED_PROCESSES),
)

private fun profile(
    key: SessionConnectionKey,
    label: String,
) = ConnectionProfileSummary(
    id = key.profileId,
    providerId = key.providerId,
    label = label,
    target = label,
    authenticationLabel = null,
)

private fun session(locator: SessionLocator) = SessionRecord(
    observation = SessionObservation(
        locator = locator,
        connectionLabel = "Trusted server",
        connectionTarget = "redacted.example",
        projectPath = "/workspace/project",
        agentProviderLabel = "Codex",
        title = "Session",
        preview = "Ready",
        agentState = AgentSessionState.IDLE,
        createdAtEpochMillis = 10,
        updatedAtEpochMillis = 20,
    ),
)

private fun disconnected() = ConnectionState.Disconnected(
    reason = ConnectionDisconnectReason.NOT_CONNECTED,
    atEpochMillis = 0,
)
