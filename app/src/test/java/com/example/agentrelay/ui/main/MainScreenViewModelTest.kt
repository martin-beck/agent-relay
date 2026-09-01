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
import dev.agentrelay.connection.api.ConnectionProfileEditor
import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProfileSaveResult
import dev.agentrelay.connection.api.ConnectionProfileSummary
import dev.agentrelay.connection.api.ConnectionProfileUpdate
import dev.agentrelay.connection.api.ConnectionProviderDescriptor
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.connection.api.ConnectionState
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentApprovalType
import dev.agentrelay.provider.api.AgentCapability
import dev.agentrelay.provider.api.AgentProviderDescriptor
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.StartSessionOptions
import dev.agentrelay.session.api.SessionActionRequest
import dev.agentrelay.session.api.SessionActionRisk
import dev.agentrelay.session.api.SessionDraft
import dev.agentrelay.session.api.SessionHubSnapshot
import dev.agentrelay.session.api.SessionLocator
import dev.agentrelay.session.api.SessionObservation
import dev.agentrelay.session.api.SessionQuestion
import dev.agentrelay.session.api.SessionQuestionOption
import dev.agentrelay.session.api.SessionRecord
import dev.agentrelay.session.runtime.AgentEndpointKey
import dev.agentrelay.session.runtime.AgentEndpointPhase
import dev.agentrelay.session.runtime.AgentEndpointStatus
import dev.agentrelay.session.runtime.SessionConnectionKey
import dev.agentrelay.session.runtime.SessionCoordinatorSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
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
        runtime.failRefresh = true
        viewModel.refreshProfiles()
        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()
        assertEquals(
            "Connection profiles could not be refreshed.",
            (viewModel.uiState.value as MainScreenUiState.Ready).hub.operationError,
        )
        runtime.failRefresh = false
        viewModel.clearOperationError()
        mainDispatcherRule.dispatcher.scheduler.runCurrent()

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

        viewModel.clearOperationError()
        viewModel.submitSessionDraft("missing-session")
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertEquals(
            "That session is no longer available.",
            (viewModel.uiState.value as MainScreenUiState.Ready).hub.operationError,
        )
        viewModel.clearOperationError()
        viewModel.updateSessionDraft("missing-session", "Keep this", 0, 9)
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertEquals(
            "That session is no longer available.",
            (viewModel.uiState.value as MainScreenUiState.Ready).hub.operationError,
        )

        viewModel.clearOperationError()
        viewModel.resumeSession("missing-session")
        mainDispatcherRule.dispatcher.scheduler.runCurrent()
        assertEquals(
            "That session is no longer available.",
            (viewModel.uiState.value as MainScreenUiState.Ready).hub.operationError,
        )

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun draftValidationSaveFailureAndResumeStayProviderScoped() = runTest {
        val providerId = ConnectionProviderId("local.device")
        val key = SessionConnectionKey(providerId, ConnectionProfileId("this-device"))
        val locator = SessionLocator(
            connectionProviderId = providerId,
            connectionProfileId = key.profileId,
            agentProviderId = AgentProviderId("agent.codex"),
            agentSessionId = AgentSessionId("saved-session"),
        )
        val runtime = FakeSessionHubRuntime(
            providers = listOf(descriptor(providerId, "Local")),
            coordinator = SessionCoordinatorSnapshot(profiles = listOf(profile(key, "This device"))),
            sessions = SessionHubSnapshot(
                sessions = listOf(session(locator, AgentSessionState.NOT_LOADED)),
            ),
        ).apply {
            failDraftSave = true
        }
        val viewModel = MainScreenViewModel(clock = { -10L }) { runtime }
        val scheduler = mainDispatcherRule.dispatcher.scheduler
        scheduler.advanceUntilIdle()
        val sessionKey = (viewModel.uiState.value as MainScreenUiState.Ready)
            .hub.sessions.single().stableKey
        viewModel.selectSession(sessionKey)
        scheduler.runCurrent()

        viewModel.submitSessionDraft(sessionKey)
        scheduler.runCurrent()
        assertEquals(
            "Enter a message before sending.",
            (viewModel.uiState.value as MainScreenUiState.Ready).hub.operationError,
        )

        viewModel.clearOperationError()
        viewModel.updateSessionDraft(sessionKey, "x".repeat(32_001), 0, 0)
        scheduler.runCurrent()
        assertEquals(
            "Session drafts are limited to 32000 characters.",
            (viewModel.uiState.value as MainScreenUiState.Ready).hub.operationError,
        )

        viewModel.clearOperationError()
        viewModel.updateSessionDraft(sessionKey, "Keep this saved", -5, 99)
        scheduler.runCurrent()
        val immediate = (viewModel.uiState.value as MainScreenUiState.Ready)
            .hub.selectedSession?.composer
        assertEquals(0, immediate?.selectionStart)
        assertEquals(15, immediate?.selectionEnd)

        scheduler.advanceTimeBy(300L)
        scheduler.runCurrent()
        val failedSave = viewModel.uiState.value as MainScreenUiState.Ready
        assertEquals("The session draft could not be saved securely.", failedSave.hub.operationError)
        assertEquals("Keep this saved", failedSave.hub.selectedSession?.composer?.draftText)
        assertTrue(runtime.savedDrafts.isEmpty())

        runtime.failDraftSave = false
        viewModel.submitSessionDraft(sessionKey)
        scheduler.advanceUntilIdle()
        assertEquals(
            "The session input could not be sent.",
            (viewModel.uiState.value as MainScreenUiState.Ready).hub.operationError,
        )
        assertTrue(runtime.sent.isEmpty())
        assertTrue(runtime.steered.isEmpty())

        viewModel.resumeSession(sessionKey)
        scheduler.advanceUntilIdle()
        assertEquals(listOf(locator), runtime.resumed)

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun sessionDraftIsImmediateDurableAndClearedOnlyAfterSuccessfulSend() = runTest {
        val providerId = ConnectionProviderId("local.device")
        val key = SessionConnectionKey(providerId, ConnectionProfileId("this-device"))
        val locator = SessionLocator(
            connectionProviderId = providerId,
            connectionProfileId = key.profileId,
            agentProviderId = AgentProviderId("agent.codex"),
            agentSessionId = AgentSessionId("session-one"),
        )
        val runtime = FakeSessionHubRuntime(
            providers = listOf(descriptor(providerId, "Local")),
            coordinator = SessionCoordinatorSnapshot(profiles = listOf(profile(key, "This device"))),
            sessions = SessionHubSnapshot(sessions = listOf(session(locator))),
        )
        val viewModel = MainScreenViewModel(clock = { 123L }) { runtime }
        val scheduler = mainDispatcherRule.dispatcher.scheduler
        scheduler.advanceUntilIdle()
        val sessionKey = (viewModel.uiState.value as MainScreenUiState.Ready)
            .hub.sessions.single().stableKey
        viewModel.selectSession(sessionKey)
        viewModel.updateSessionDraft(
            sessionKey = sessionKey,
            text = "Run the focused tests\nand report failures",
            selectionStart = 4,
            selectionEnd = 11,
        )
        scheduler.runCurrent()

        val immediate = (viewModel.uiState.value as MainScreenUiState.Ready)
            .hub.selectedSession?.composer
        assertEquals("Run the focused tests\nand report failures", immediate?.draftText)
        assertEquals(4, immediate?.selectionStart)
        assertEquals(11, immediate?.selectionEnd)
        assertTrue(runtime.savedDrafts.isEmpty())

        scheduler.advanceTimeBy(300L)
        scheduler.runCurrent()

        assertEquals(locator, runtime.savedDrafts.single().first)
        assertEquals(123L, runtime.savedDrafts.single().second.updatedAtEpochMillis)

        viewModel.submitSessionDraft(sessionKey)
        scheduler.advanceUntilIdle()

        assertEquals(listOf(locator to "Run the focused tests\nand report failures"), runtime.sent)
        assertEquals("", runtime.savedDrafts.last().second.text)
        assertEquals(
            "",
            (viewModel.uiState.value as MainScreenUiState.Ready)
                .hub.selectedSession?.composer?.draftText,
        )
        assertTrue(runtime.steered.isEmpty())

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun failedImmediateSendKeepsDraftDurableAndVisible() = runTest {
        val providerId = ConnectionProviderId("local.device")
        val key = SessionConnectionKey(providerId, ConnectionProfileId("this-device"))
        val locator = SessionLocator(
            connectionProviderId = providerId,
            connectionProfileId = key.profileId,
            agentProviderId = AgentProviderId("agent.codex"),
            agentSessionId = AgentSessionId("session-one"),
        )
        val runtime = FakeSessionHubRuntime(
            providers = listOf(descriptor(providerId, "Local")),
            coordinator = SessionCoordinatorSnapshot(profiles = listOf(profile(key, "This device"))),
            sessions = SessionHubSnapshot(sessions = listOf(session(locator))),
        ).apply {
            failSend = true
        }
        val viewModel = MainScreenViewModel { runtime }
        val scheduler = mainDispatcherRule.dispatcher.scheduler
        scheduler.advanceUntilIdle()
        val sessionKey = (viewModel.uiState.value as MainScreenUiState.Ready)
            .hub.sessions.single().stableKey
        viewModel.selectSession(sessionKey)
        scheduler.runCurrent()

        viewModel.updateSessionDraft(sessionKey, "Preserve this input", 19, 19)
        scheduler.runCurrent()
        viewModel.submitSessionDraft(sessionKey)
        scheduler.advanceUntilIdle()

        assertEquals("Preserve this input", runtime.savedDrafts.single().second.text)
        val ready = viewModel.uiState.value as MainScreenUiState.Ready
        assertEquals("Preserve this input", ready.hub.selectedSession?.composer?.draftText)
        assertEquals("The session input could not be sent.", ready.hub.operationError)
        assertTrue(runtime.sent.isEmpty())

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun successfulSendReportsDraftCleanupFailureWithoutInvitingDuplicateDelivery() = runTest {
        val providerId = ConnectionProviderId("local.device")
        val key = SessionConnectionKey(providerId, ConnectionProfileId("this-device"))
        val locator = SessionLocator(
            connectionProviderId = providerId,
            connectionProfileId = key.profileId,
            agentProviderId = AgentProviderId("agent.codex"),
            agentSessionId = AgentSessionId("session-one"),
        )
        val runtime = FakeSessionHubRuntime(
            providers = listOf(descriptor(providerId, "Local")),
            coordinator = SessionCoordinatorSnapshot(profiles = listOf(profile(key, "This device"))),
            sessions = SessionHubSnapshot(sessions = listOf(session(locator))),
        ).apply {
            failDraftClear = true
        }
        val viewModel = MainScreenViewModel { runtime }
        val scheduler = mainDispatcherRule.dispatcher.scheduler
        scheduler.advanceUntilIdle()
        val sessionKey = (viewModel.uiState.value as MainScreenUiState.Ready)
            .hub.sessions.single().stableKey
        viewModel.selectSession(sessionKey)
        scheduler.runCurrent()

        viewModel.updateSessionDraft(sessionKey, "Deliver once", 12, 12)
        scheduler.runCurrent()
        viewModel.submitSessionDraft(sessionKey)
        scheduler.advanceUntilIdle()

        val ready = viewModel.uiState.value as MainScreenUiState.Ready
        assertEquals(listOf(locator to "Deliver once"), runtime.sent)
        assertEquals("Deliver once", runtime.savedDrafts.single().second.text)
        assertEquals("Deliver once", ready.hub.selectedSession?.composer?.draftText)
        assertEquals(
            "The message was delivered, but its saved draft could not be cleared securely.",
            ready.hub.operationError,
        )

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun runningSessionRoutesSteeringAndInterruptThroughFullLocator() = runTest {
        val providerId = ConnectionProviderId("ssh.secure-shell")
        val key = SessionConnectionKey(providerId, ConnectionProfileId("shared-profile"))
        val locator = SessionLocator(
            connectionProviderId = providerId,
            connectionProfileId = key.profileId,
            agentProviderId = AgentProviderId("agent.codex"),
            agentSessionId = AgentSessionId("shared-session"),
        )
        val draft = SessionDraft("Keep the current scope", 22, 22, 10)
        val runtime = FakeSessionHubRuntime(
            providers = listOf(descriptor(providerId, "Secure Shell")),
            coordinator = SessionCoordinatorSnapshot(profiles = listOf(profile(key, "Test server"))),
            sessions = SessionHubSnapshot(
                sessions = listOf(session(locator, AgentSessionState.RUNNING)),
                drafts = mapOf(locator to draft),
            ),
        )
        val viewModel = MainScreenViewModel { runtime }
        val scheduler = mainDispatcherRule.dispatcher.scheduler
        scheduler.advanceUntilIdle()
        val sessionKey = (viewModel.uiState.value as MainScreenUiState.Ready)
            .hub.sessions.single().stableKey

        viewModel.submitSessionDraft(sessionKey)
        scheduler.advanceUntilIdle()
        viewModel.interruptSession(sessionKey)
        scheduler.advanceUntilIdle()

        assertEquals(listOf(locator to draft.text), runtime.steered)
        assertEquals(listOf(locator), runtime.interrupted)
        assertTrue(runtime.sent.isEmpty())

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun sessionCreationAndActionResponsesResolveStableUiKeysToFullProviderIdentity() = runTest {
        val providerId = ConnectionProviderId("ssh.secure-shell")
        val connectionKey = SessionConnectionKey(
            providerId,
            ConnectionProfileId("trusted-profile"),
        )
        val agentProviderId = AgentProviderId("agent.codex")
        val endpointKey = AgentEndpointKey(connectionKey, agentProviderId)
        val existingLocator = SessionLocator(
            connectionProviderId = providerId,
            connectionProfileId = connectionKey.profileId,
            agentProviderId = agentProviderId,
            agentSessionId = AgentSessionId("existing-session"),
        )
        val request = SessionActionRequest(
            id = "stable-action-key",
            providerApprovalId = "provider-private-approval-id",
            locator = existingLocator,
            turnId = "turn-1",
            type = AgentApprovalType.USER_INPUT,
            title = "Choose validation scope",
            description = "The provider needs a scope before continuing.",
            command = null,
            workingDirectory = "/workspace/project",
            questions = listOf(
                SessionQuestion(
                    id = "stable-question-key",
                    providerQuestionId = "provider-private-question-id",
                    header = "Scope",
                    prompt = "Which tests should run?",
                    options = listOf(
                        SessionQuestionOption("Focused tests"),
                    ),
                    allowsOther = true,
                ),
            ),
            availableDecisions = setOf(
                AgentApprovalDecision.SUBMIT,
                AgentApprovalDecision.CANCEL,
            ),
            riskReasons = setOf(SessionActionRisk.CREDENTIAL_ACCESS),
            receivedAtEpochMillis = 30,
        )
        val runtime = FakeSessionHubRuntime(
            providers = listOf(descriptor(providerId, "Secure Shell")),
            coordinator = SessionCoordinatorSnapshot(
                profiles = listOf(profile(connectionKey, "Trusted server")),
                agentEndpoints = mapOf(
                    endpointKey to AgentEndpointStatus(
                        key = endpointKey,
                        descriptor = AgentProviderDescriptor(
                            id = agentProviderId,
                            displayName = "Codex",
                            providerVersion = "1.0",
                            capabilities = setOf(
                                AgentCapability.SESSION_START,
                                AgentCapability.APPROVALS,
                            ),
                        ),
                        phase = AgentEndpointPhase.READY,
                        updatedAtEpochMillis = 40,
                    ),
                ),
            ),
            sessions = SessionHubSnapshot(
                sessions = listOf(session(existingLocator)),
                actionRequests = listOf(request),
            ),
        )
        val viewModel = MainScreenViewModel { runtime }
        val scheduler = mainDispatcherRule.dispatcher.scheduler
        scheduler.advanceUntilIdle()

        val launcher = (viewModel.uiState.value as MainScreenUiState.Ready)
            .hub.sessionLaunchers.single()
        viewModel.openSessionCreator(launcher.stableKey)
        scheduler.runCurrent()
        assertEquals(
            "/workspace/project",
            (viewModel.uiState.value as MainScreenUiState.Ready)
                .sessionCreator?.workingDirectory,
        )

        viewModel.updateSessionCreatorWorkingDirectory(" /workspace/new ")
        viewModel.updateSessionCreatorModel(" test-model ")
        viewModel.startSession()
        scheduler.advanceUntilIdle()

        assertEquals(
            endpointKey to StartSessionOptions(
                workingDirectory = "/workspace/new",
                model = "test-model",
            ),
            runtime.startedSessions.single(),
        )
        val startedLocator = SessionLocator(
            connectionProviderId = providerId,
            connectionProfileId = connectionKey.profileId,
            agentProviderId = agentProviderId,
            agentSessionId = AgentSessionId("started-1"),
        )
        val afterStart = viewModel.uiState.value as MainScreenUiState.Ready
        assertEquals(null, afterStart.sessionCreator)
        assertEquals(startedLocator.stableUiKey, afterStart.hub.selectedSessionKey)

        val answers = mapOf("stable-question-key" to listOf("Focused tests"))
        viewModel.respondToAction(
            sessionKey = existingLocator.stableUiKey,
            actionKey = request.id,
            decision = AgentApprovalDecision.SUBMIT,
            answers = answers,
            additionalConfirmationGiven = true,
        )
        scheduler.advanceUntilIdle()

        assertEquals(
            ActionResponse(
                locator = existingLocator,
                requestId = request.id,
                decision = AgentApprovalDecision.SUBMIT,
                answers = answers,
                additionalConfirmationGiven = true,
            ),
            runtime.actionResponses.single(),
        )

        viewModel.viewModelScope.cancel()
    }

    @Test
    fun interactionEventsBeforeRuntimeInitializationAreSafeNoOps() = runTest {
        val providerId = ConnectionProviderId("local.device")
        val key = SessionConnectionKey(providerId, ConnectionProfileId("this-device"))
        val locator = SessionLocator(
            connectionProviderId = providerId,
            connectionProfileId = key.profileId,
            agentProviderId = AgentProviderId("agent.codex"),
            agentSessionId = AgentSessionId("session-one"),
        )
        val runtime = FakeSessionHubRuntime(
            providers = listOf(descriptor(providerId, "Local")),
            coordinator = SessionCoordinatorSnapshot(profiles = listOf(profile(key, "This device"))),
            sessions = SessionHubSnapshot(sessions = listOf(session(locator))),
        )
        val viewModel = MainScreenViewModel { runtime }

        viewModel.refreshProfiles()
        viewModel.connect(key.stableUiKey)
        viewModel.updateSessionDraft(locator.stableUiKey, "Do not lose this", 0, 16)
        viewModel.submitSessionDraft(locator.stableUiKey)
        viewModel.resumeSession(locator.stableUiKey)
        viewModel.interruptSession(locator.stableUiKey)
        viewModel.addProfile(providerId.value)
        viewModel.editProfile(key.stableUiKey)
        viewModel.updateProfileField("profile-label", "Ignored")
        viewModel.saveProfile()
        viewModel.requestProfileDeletion()
        viewModel.cancelProfileDeletion()
        viewModel.deleteProfile()
        viewModel.dismissProfileEditor()

        assertEquals(MainScreenUiState.Loading, viewModel.uiState.value)
        assertTrue(runtime.connected.isEmpty())
        assertTrue(runtime.savedDrafts.isEmpty())
        assertTrue(runtime.resumed.isEmpty())
        assertTrue(runtime.interrupted.isEmpty())

        mainDispatcherRule.dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, runtime.refreshCount)
        assertTrue(viewModel.uiState.value is MainScreenUiState.Ready)

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
    private val mutableSessionSnapshot = MutableStateFlow(sessions)
    override val sessionSnapshot: StateFlow<SessionHubSnapshot> = mutableSessionSnapshot
    var failRefresh = false
    var failDisconnect = false
    var failMarkRead = false
    var failDraftSave = false
    var failDraftClear = false
    var failSend = false
    var refreshCount = 0
    val connected = mutableListOf<SessionConnectionKey>()
    val markedRead = mutableListOf<SessionLocator>()
    val identityDecisions = mutableListOf<ConnectionIdentityDecision>()
    val savedDrafts = mutableListOf<Pair<SessionLocator, SessionDraft>>()
    val resumed = mutableListOf<SessionLocator>()
    val sent = mutableListOf<Pair<SessionLocator, String>>()
    val steered = mutableListOf<Pair<SessionLocator, String>>()
    val interrupted = mutableListOf<SessionLocator>()
    val startedSessions = mutableListOf<Pair<AgentEndpointKey, StartSessionOptions>>()
    val actionResponses = mutableListOf<ActionResponse>()

    override suspend fun refreshProfiles() {
        refreshCount += 1
        check(!failRefresh)
    }

    override suspend fun profileEditor(
        providerId: ConnectionProviderId,
        profileId: ConnectionProfileId?,
    ): ConnectionProfileEditor = error("Profile management is not configured for this test")

    override suspend fun saveProfile(
        update: ConnectionProfileUpdate,
    ): ConnectionProfileSaveResult = error("Profile management is not configured for this test")

    override suspend fun deleteProfile(
        providerId: ConnectionProviderId,
        profileId: ConnectionProfileId,
    ) {
        error("Profile management is not configured for this test")
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

    override suspend fun updateDraft(locator: SessionLocator, draft: SessionDraft) {
        check(!failDraftSave)
        check(!failDraftClear || draft.text.isNotEmpty())
        savedDrafts += locator to draft
        mutableSessionSnapshot.value = mutableSessionSnapshot.value.copy(
            drafts = mutableSessionSnapshot.value.drafts + (locator to draft),
        )
    }

    override suspend fun resumeSession(locator: SessionLocator) {
        resumed += locator
    }

    override suspend fun sendInput(locator: SessionLocator, text: String) {
        check(!failSend)
        sent += locator to text
    }

    override suspend fun steerActiveTurn(locator: SessionLocator, text: String) {
        steered += locator to text
    }

    override suspend fun interrupt(locator: SessionLocator) {
        interrupted += locator
    }

    override suspend fun startSession(
        endpoint: AgentEndpointKey,
        options: StartSessionOptions,
    ): SessionLocator {
        startedSessions += endpoint to options
        val locator = SessionLocator(
            connectionProviderId = endpoint.connection.providerId,
            connectionProfileId = endpoint.connection.profileId,
            agentProviderId = endpoint.agentProviderId,
            agentSessionId = AgentSessionId("started-" + startedSessions.size),
        )
        mutableSessionSnapshot.value = mutableSessionSnapshot.value.copy(
            sessions = mutableSessionSnapshot.value.sessions + session(locator),
        )
        return locator
    }

    override suspend fun respondToAction(
        locator: SessionLocator,
        requestId: String,
        decision: AgentApprovalDecision,
        answers: Map<String, List<String>>,
        additionalConfirmationGiven: Boolean,
    ) {
        actionResponses += ActionResponse(
            locator,
            requestId,
            decision,
            answers,
            additionalConfirmationGiven,
        )
    }
}

private data class ActionResponse(
    val locator: SessionLocator,
    val requestId: String,
    val decision: AgentApprovalDecision,
    val answers: Map<String, List<String>>,
    val additionalConfirmationGiven: Boolean,
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

private fun session(
    locator: SessionLocator,
    state: AgentSessionState = AgentSessionState.IDLE,
) = SessionRecord(
    observation = SessionObservation(
        locator = locator,
        connectionLabel = "Trusted server",
        connectionTarget = "redacted.example",
        projectPath = "/workspace/project",
        agentProviderLabel = "Codex",
        title = "Session",
        preview = "Ready",
        agentState = state,
        createdAtEpochMillis = 10,
        updatedAtEpochMillis = 20,
        metadata = mapOf("can_accept_input" to "true"),
    ),
)

private fun disconnected() = ConnectionState.Disconnected(
    reason = ConnectionDisconnectReason.NOT_CONNECTED,
    atEpochMillis = 0,
)
