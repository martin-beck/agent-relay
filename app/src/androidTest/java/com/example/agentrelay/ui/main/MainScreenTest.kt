package com.example.agentrelay.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agentrelay.theme.AgentRelayTheme
import dev.agentrelay.connection.api.ConnectionProfileFieldType
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.session.api.SessionActionState
import dev.agentrelay.session.api.SessionActivityType
import org.junit.Rule
import org.junit.Test

class MainScreenTest {

    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun loadingStateExplainsThatInitializationIsInProgress() {
        val recorder = ActionRecorder()
        setContent(MainScreenUiState.Loading, recorder)

        composeTestRule.onNodeWithTag(MAIN_LOADING_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Loading Agent Relay...").assertIsDisplayed()
    }

    @Test
    fun fatalError_explainsRecoveryAndRetries() {
        val recorder = ActionRecorder()
        setContent(MainScreenUiState.FatalError("Session storage is unavailable."), recorder)

        composeTestRule.onNodeWithTag(MAIN_FATAL_ERROR_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Session storage is unavailable.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").performClick()

        check(recorder.retryCount == 1)
    }

    @Test
    fun adaptiveBreakpointUsesCompactLayoutImmediatelyBelow840Dp() {
        val recorder = ActionRecorder()
        setSizedContent(
            state = MainScreenUiState.Ready(testHub()),
            recorder = recorder,
            widthDp = 839,
        )

        composeTestRule.onNodeWithTag(SESSION_HUB_LIST_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(SESSION_DETAIL_PANE_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun adaptiveBreakpointUsesExpandedLayoutAt840Dp() {
        val recorder = ActionRecorder()
        setSizedContent(
            state = MainScreenUiState.Ready(testHub()),
            recorder = recorder,
            widthDp = 840,
        )

        composeTestRule.onNodeWithTag(SESSION_HUB_LIST_TEST_TAG).assertExists()
        composeTestRule.onNodeWithTag(SESSION_DETAIL_PANE_TEST_TAG).assertExists()
    }

    @Test
    fun compactHub_exposesConnectionsIdentityReviewAndSessionActions() {
        val recorder = ActionRecorder()
        setContent(MainScreenUiState.Ready(testHub()), recorder)

        composeTestRule.onNodeWithText("Connections").assertExists()
        val hubList = composeTestRule.onNode(hasScrollAction())
        hubList.performScrollToNode(hasText("Connect"))
        composeTestRule.onNodeWithText("Connect").performClick()
        hubList.performScrollToNode(hasText("Replace identity"))
        composeTestRule.onNodeWithText("Replace identity").performClick()
        hubList.performScrollToNode(hasText("Investigate flaky build"))
        composeTestRule.onNodeWithText("Investigate flaky build").performClick()

        check(recorder.connectedKey == "local-key")
        check(recorder.trustedKey == "ssh-key")
        check(recorder.replaceIdentity)
        check(recorder.selectedKey == "session-key")
        check(recorder.openedKey == "session-key")
    }

    @Test
    fun profileManagementIsDiscoverableFromProviderAndExistingConnection() {
        val recorder = ActionRecorder()
        setContent(MainScreenUiState.Ready(testHub()), recorder)

        composeTestRule
            .onNodeWithText("Add Secure Shell profile")
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithText("Edit profile").performScrollTo().performClick()

        check(recorder.addedProvider == "ssh.secure-shell")
        check(recorder.editedConnection == "ssh-key")
    }

    @Test
    fun profileEditorExposesProviderFieldsErrorsAndDestructiveConfirmation() {
        val recorder = ActionRecorder()
        setContent(MainScreenUiState.Ready(testHub(), testEditor()), recorder)

        composeTestRule.onNodeWithText("Edit Secure Shell profile").assertIsDisplayed()
        composeTestRule.onNodeWithText("Correct the highlighted profile fields.").assertIsDisplayed()
        composeTestRule.onNodeWithText("A secret is stored. Leave this blank to keep it.").assertExists()
        composeTestRule.onNodeWithText("Imported private key").performClick()
        composeTestRule.onNodeWithText("Delete").performClick()

        check(recorder.updatedField == "authentication" to "imported-key")
        check(recorder.deleteRequested)
    }

    @Test
    fun profileDeletionRequiresExplicitConfirmation() {
        val recorder = ActionRecorder()
        setContent(
            MainScreenUiState.Ready(testHub(), testEditor().copy(confirmDelete = true)),
            recorder,
        )

        composeTestRule.onNodeWithText("Delete connection profile?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cancel").performClick()

        check(recorder.deleteCancelled)
    }

    @Test
    fun profileEditorExposesJumpHostAndKeyOperations() {
        val recorder = ActionRecorder()
        setContent(MainScreenUiState.Ready(testHub(), testEditor()), recorder)

        composeTestRule.onNodeWithText("Staging bastion").performScrollTo().performClick()
        check(recorder.updatedField == "jump-host" to "jump-profile")

        composeTestRule.onNodeWithText("Install public key").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Test key-only login").performScrollTo().performClick()

        check(recorder.requestedOperations == listOf("install-public-key", "verify-key-login"))
        composeTestRule
            .onNodeWithText("ssh-ed25519 AAAATESTKEY")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun publicKeyInstallationConfirmationExplainsRemoteMutation() {
        val recorder = ActionRecorder()
        setContent(
            MainScreenUiState.Ready(
                testHub(),
                testEditor().copy(confirmOperationId = "install-public-key"),
            ),
            recorder,
        )

        composeTestRule
            .onNodeWithText("Install public key on this remote account?")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                "Agent Relay will add only this app-managed public key to the remote account.",
            )
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Install public key").performClick()

        check(recorder.operationConfirmed)
    }

    @Test
    fun unsavedProfileChangesDisableKeyOperations() {
        val recorder = ActionRecorder()
        setContent(
            MainScreenUiState.Ready(
                testHub(),
                testEditor().copy(hasUnsavedChanges = true),
            ),
            recorder,
        )

        composeTestRule
            .onNodeWithText("Install public key")
            .performScrollTo()
            .assertIsNotEnabled()
        composeTestRule
            .onNodeWithText("Test key-only login")
            .performScrollTo()
            .assertIsNotEnabled()
        check(recorder.requestedOperations.isEmpty())
    }

    @Test
    fun expandedHub_keepsSelectedSessionDetailVisible() {
        val recorder = ActionRecorder()
        composeTestRule.setContent {
            AgentRelayTheme {
                MainScreenContent(
                    state = MainScreenUiState.Ready(testHub()),
                    actions = recorder.actions(),
                    modifier = Modifier.requiredSize(width = 1_000.dp, height = 720.dp),
                )
            }
        }

        composeTestRule.onNodeWithText("Connections").assertExists()
        composeTestRule.onNodeWithText("Timeline").assertExists()
        composeTestRule.onNodeWithText("Cached agent output").assertExists()
        composeTestRule.onNodeWithText("Changed files").assertExists()
        composeTestRule.onNodeWithText("reports/result.txt").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Refresh changed files").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Save copy").performScrollTo().performClick()

        check(recorder.refreshedArtifactsFor == "session-key")
        check(recorder.savedArtifact == Triple("session-key", "artifact-key", "result.txt"))
    }

    @Test
    fun expandedComposerEditsSteersAndInterruptsSelectedSession() {
        val recorder = ActionRecorder()
        composeTestRule.setContent {
            AgentRelayTheme {
                MainScreenContent(
                    state = MainScreenUiState.Ready(interactiveHub()),
                    actions = recorder.actions(),
                    modifier = Modifier.requiredSize(width = 1_000.dp, height = 820.dp),
                )
            }
        }

        composeTestRule
            .onNodeWithTag("session-composer-input")
            .performTextReplacement("Keep the provider-neutral scope")
        composeTestRule.onNodeWithText("Steer active turn").performClick()
        composeTestRule.onNodeWithText("Interrupt turn").performClick()

        check(recorder.updatedDraft?.first == "session-key")
        check(recorder.updatedDraft?.second == "Keep the provider-neutral scope")
        check(recorder.submittedSession == "session-key")
        check(recorder.interruptedSession == "session-key")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun expandedComposerSupportsExternalKeyboardFocusAndActivation() {
        withKeyboardFocusMode {
            val recorder = ActionRecorder()
            composeTestRule.setContent {
                AgentRelayTheme {
                    MainScreenContent(
                        state = MainScreenUiState.Ready(interactiveHub()),
                        actions = recorder.actions(),
                        modifier = Modifier.requiredSize(width = 1_000.dp, height = 820.dp),
                    )
                }
            }

            check(recorder.updatedDraft == null)
            val composer = composeTestRule.onNodeWithTag("session-composer-input")
            composer.requestFocus().assertIsFocused()
            composer.performKeyInput { pressKey(Key.Tab) }

            val interrupt = composeTestRule.onNodeWithText("Interrupt turn")
            interrupt.assertIsFocused()
            interrupt.performKeyInput { pressKey(Key.Enter) }

            check(recorder.interruptedSession == "session-key")

            interrupt.performKeyInput { pressKey(Key.Tab) }
            val submit = composeTestRule.onNodeWithText("Steer active turn")
            submit.assertIsFocused()
            submit.performKeyInput { pressKey(Key.Enter) }

            check(recorder.submittedSession == "session-key")

            submit.performKeyInput {
                keyDown(Key.ShiftLeft)
                pressKey(Key.Tab)
                keyUp(Key.ShiftLeft)
            }
            interrupt.assertIsFocused()
        }
    }

    @Test
    fun readyAgentEndpointExposesSessionLauncher() {
        val recorder = ActionRecorder()
        setContent(MainScreenUiState.Ready(actionHub()), recorder)

        val hubList = composeTestRule.onNode(hasScrollAction())
        hubList.performScrollToNode(hasText("Start Codex on Trusted server"))
        composeTestRule.onNodeWithText("Start Codex on Trusted server").performClick()

        check(recorder.openedSessionCreator == "launcher-key")
    }

    @Test
    fun sessionCreatorExposesProviderNeutralSettings() {
        val recorder = ActionRecorder()
        composeTestRule.setContent {
            AgentRelayTheme {
                var creatorState by remember { mutableStateOf(testSessionCreator()) }
                val actions = recorder.actions().copy(
                    updateSessionCreatorWorkingDirectory = { value ->
                        recorder.sessionWorkingDirectory = value
                        creatorState = creatorState.copy(workingDirectory = value)
                    },
                    updateSessionCreatorModel = { value ->
                        recorder.sessionModel = value
                        creatorState = creatorState.copy(model = value)
                    },
                )
                MainScreenContent(
                    state = MainScreenUiState.Ready(
                        hub = testHub(),
                        sessionCreator = creatorState,
                    ),
                    actions = actions,
                )
            }
        }

        composeTestRule.onNodeWithText("Start a new session").assertIsDisplayed()
        composeTestRule
            .onNodeWithTag("session-working-directory")
            .performTextReplacement("/workspace/new")
        composeTestRule
            .onNodeWithTag("session-model")
            .performTextReplacement("test-model")
        composeTestRule.onNodeWithTag("start-session").performClick()

        check(recorder.sessionWorkingDirectory == "/workspace/new")
        check(recorder.sessionModel == "test-model")
        check(recorder.startSessionCount == 1)
    }

    @Test
    fun sensitiveQuestionRequiresAnswerAndSecondConfirmation() {
        val recorder = ActionRecorder()
        composeTestRule.setContent {
            AgentRelayTheme {
                MainScreenContent(
                    state = MainScreenUiState.Ready(actionHub()),
                    actions = recorder.actions(),
                    modifier = Modifier.requiredSize(width = 1_000.dp, height = 900.dp),
                )
            }
        }

        composeTestRule.onNodeWithText("Approvals and questions").assertExists()
        composeTestRule.onNodeWithText("Approve once").assertDoesNotExist()
        composeTestRule
            .onNodeWithText("Submit answers")
            .performScrollTo()
            .assertIsNotEnabled()
        composeTestRule
            .onNodeWithText("Focused tests")
            .performScrollTo()
            .performClick()
        composeTestRule.onNodeWithText("Submit answers").performClick()

        check(recorder.actionResponse == null)
        composeTestRule.onNodeWithTag("action-confirmation-dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("Confirm submit answers").performClick()

        check(
            recorder.actionResponse == RecordedActionResponse(
                sessionKey = "session-key",
                actionKey = "action-key",
                decision = AgentApprovalDecision.SUBMIT,
                answers = mapOf("question-key" to listOf("Focused tests")),
                additionalConfirmationGiven = true,
            ),
        )
    }

    @Test
    fun uncertainActionDeliveryCannotBeSubmittedAgain() {
        val recorder = ActionRecorder()
        val base = actionHub()
        val delivering = base.attentionActions.single().copy(
            state = SessionActionState.DELIVERING,
            completedDecisionLabel = "Submit answers",
            additionalConfirmationGiven = true,
            isBusy = true,
        )
        val hub = base.copy(
            attentionActions = listOf(delivering),
            selectedSession = checkNotNull(base.selectedSession).copy(
                actions = listOf(delivering),
            ),
        )
        composeTestRule.setContent {
            AgentRelayTheme {
                MainScreenContent(
                    state = MainScreenUiState.Ready(hub),
                    actions = recorder.actions(),
                    modifier = Modifier.requiredSize(width = 1_000.dp, height = 900.dp),
                )
            }
        }

        composeTestRule
            .onNodeWithText(
                "Response delivery is awaiting provider confirmation. Do not retry this request; " +
                    "wait for a newly identified provider request or verify its state independently.",
            )
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Submit answers").assertDoesNotExist()
        composeTestRule.onNodeWithText("Cancel").assertDoesNotExist()
        check(recorder.actionResponse == null)
    }

    @Test
    fun emptyHub_explainsHowToProceed() {
        val recorder = ActionRecorder()
        val emptyHub = testHub().copy(
            connections = emptyList(),
            sessions = emptyList(),
            selectedSession = null,
            selectedSessionKey = null,
        )
        setContent(MainScreenUiState.Ready(emptyHub), recorder)

        composeTestRule
            .onNodeWithText("No connection profiles are available. Refresh to try again.")
            .assertExists()
        composeTestRule
            .onNodeWithText(
                "No sessions have been discovered yet. Connect a profile to check its agent providers.",
            )
            .performScrollTo()
            .assertIsDisplayed()
    }

    @SdkSuppress(minSdkVersion = 34)
    @Test
    fun readyHub_passesAutomatedAccessibilityChecks() {
        val recorder = ActionRecorder()
        setContent(MainScreenUiState.Ready(testHub()), recorder)

        composeTestRule.enableAccessibilityChecks()
        composeTestRule.onRoot().tryPerformAccessibilityChecks()
    }

    @SdkSuppress(minSdkVersion = 34)
    @Test
    fun profileEditorPassesAutomatedAccessibilityChecks() {
        val recorder = ActionRecorder()
        setContent(MainScreenUiState.Ready(testHub(), testEditor()), recorder)

        composeTestRule.enableAccessibilityChecks()
        composeTestRule.onRoot().tryPerformAccessibilityChecks()
    }

    @SdkSuppress(minSdkVersion = 34)
    @Test
    fun sensitiveActionPassesAutomatedAccessibilityChecks() {
        val recorder = ActionRecorder()
        composeTestRule.setContent {
            AgentRelayTheme {
                MainScreenContent(
                    state = MainScreenUiState.Ready(actionHub()),
                    actions = recorder.actions(),
                    modifier = Modifier.requiredSize(width = 1_000.dp, height = 900.dp),
                )
            }
        }

        composeTestRule.enableAccessibilityChecks()
        composeTestRule.onRoot().tryPerformAccessibilityChecks()
    }

    private inline fun withKeyboardFocusMode(block: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val wasInTouchMode = composeTestRule.activity.window.decorView.isInTouchMode
        instrumentation.setInTouchMode(false)
        try {
            block()
        } finally {
            instrumentation.setInTouchMode(wasInTouchMode)
        }
    }

    private fun setContent(
        state: MainScreenUiState,
        recorder: ActionRecorder,
    ) {
        composeTestRule.setContent {
            AgentRelayTheme {
                MainScreenContent(
                    state = state,
                    actions = recorder.actions(),
                )
            }
        }
    }

    private fun setSizedContent(
        state: MainScreenUiState,
        recorder: ActionRecorder,
        widthDp: Int,
    ) {
        composeTestRule.setContent {
            AgentRelayTheme {
                MainScreenContent(
                    state = state,
                    actions = recorder.actions(),
                    modifier = Modifier.requiredSize(width = widthDp.dp, height = 720.dp),
                )
            }
        }
    }
}

private class ActionRecorder {
    var retryCount = 0
    var connectedKey: String? = null
    var trustedKey: String? = null
    var replaceIdentity = false
    var selectedKey: String? = null
    var openedKey: String? = null
    var addedProvider: String? = null
    var editedConnection: String? = null
    var updatedField: Pair<String, String>? = null
    var deleteRequested = false
    var deleteCancelled = false
    var updatedDraft: Pair<String, String>? = null
    var submittedSession: String? = null
    var resumedSession: String? = null
    var interruptedSession: String? = null
    var openedSessionCreator: String? = null
    var sessionWorkingDirectory: String? = null
    var sessionModel: String? = null
    var startSessionCount = 0
    var actionResponse: RecordedActionResponse? = null
    var refreshedArtifactsFor: String? = null
    var savedArtifact: Triple<String, String, String>? = null
    val requestedOperations = mutableListOf<String>()
    var operationConfirmed = false

    fun actions() = SessionHubActions(
        retry = { retryCount++ },
        refresh = {},
        connect = { connectedKey = it },
        disconnect = {},
        trustIdentity = { key, replace ->
            trustedKey = key
            replaceIdentity = replace
        },
        rejectIdentity = {},
        selectSession = { selectedKey = it },
        openSession = { openedKey = it },
        dismissError = {},
        addProfile = { addedProvider = it },
        editProfile = { editedConnection = it },
        updateProfileField = { id, value -> updatedField = id to value },
        requestProfileOperation = requestedOperations::add,
        confirmProfileOperation = { operationConfirmed = true },
        requestProfileDeletion = { deleteRequested = true },
        cancelProfileDeletion = { deleteCancelled = true },
        updateSessionDraft = { key, text, _, _ -> updatedDraft = key to text },
        submitSessionDraft = { submittedSession = it },
        resumeSession = { resumedSession = it },
        interruptSession = { interruptedSession = it },
        openSessionCreator = { openedSessionCreator = it },
        updateSessionCreatorWorkingDirectory = { sessionWorkingDirectory = it },
        updateSessionCreatorModel = { sessionModel = it },
        startSession = { startSessionCount++ },
        respondToAction = { sessionKey, actionKey, decision, answers, confirmed ->
            actionResponse = RecordedActionResponse(
                sessionKey,
                actionKey,
                decision,
                answers,
                confirmed,
            )
        },
        refreshArtifacts = { refreshedArtifactsFor = it },
        saveArtifact = { sessionKey, artifactKey, fileName ->
            savedArtifact = Triple(sessionKey, artifactKey, fileName)
        },
    )
}

private data class RecordedActionResponse(
    val sessionKey: String,
    val actionKey: String,
    val decision: AgentApprovalDecision,
    val answers: Map<String, List<String>>,
    val additionalConfirmationGiven: Boolean,
)

private fun testHub(): SessionHubUiModel {
    val session = SessionUiModel(
        stableKey = "session-key",
        title = "Investigate flaky build",
        preview = "The test fixture is ready for review.",
        connectionLabel = "This device",
        connectionProviderName = "Local",
        agentProviderLabel = "Codex",
        projectPath = "/test/workspace",
        agentState = AgentSessionState.WAITING_FOR_APPROVAL,
        unreadCount = 2,
        requiresActionCount = 1,
        lastActivityAtEpochMillis = 1_788_200_000_000,
        isPinned = true,
    )
    return SessionHubUiModel(
        availableConnectionProviders = listOf("Local", "Secure Shell"),
        connections = listOf(
            ConnectionUiModel(
                stableKey = "local-key",
                providerName = "Local",
                label = "This device",
                target = "App-private workspace",
                authenticationLabel = null,
                status = ConnectionStatus.OFFLINE,
                statusDetail = null,
                connectedAgentCount = 0,
                agentCount = 0,
                unavailableAgentCount = 0,
                canConnect = true,
                canDisconnect = false,
                isBusy = false,
                identityChallenge = null,
                canEdit = false,
            ),
            ConnectionUiModel(
                stableKey = "ssh-key",
                providerName = "Secure Shell",
                label = "Test fixture",
                target = "Test endpoint",
                authenticationLabel = "Test key",
                status = ConnectionStatus.IDENTITY_REVIEW,
                statusDetail = "Test endpoint",
                connectedAgentCount = 0,
                agentCount = 0,
                unavailableAgentCount = 0,
                canConnect = false,
                canDisconnect = true,
                isBusy = false,
                identityChallenge = IdentityChallengeUiModel(
                    endpoint = "Test endpoint",
                    algorithm = "Ed25519",
                    fingerprint = "SHA256:new-test-fingerprint",
                    isChangedIdentity = true,
                    previousFingerprints = listOf("SHA256:old-test-fingerprint"),
                ),
                canEdit = true,
            ),
        ),
        sessions = listOf(session),
        issues = emptyList(),
        selectedSession = SessionDetailUiModel(
            session = session,
            activities = listOf(
                SessionActivityUiModel(
                    id = "activity",
                    type = SessionActivityType.APPROVAL_REQUIRED,
                    summary = "A safe test action needs review.",
                    occurredAtEpochMillis = 1_788_200_000_000,
                    requiresAction = true,
                    isRead = false,
                ),
            ),
            transcript = listOf(
                TranscriptEntryUiModel(
                    id = "transcript",
                    roleLabel = "Agent",
                    kind = TimelineEntryKind.AGENT_COMMENTARY,
                    text = "Cached agent output",
                    wasTruncated = false,
                    createdAtEpochMillis = 1_788_200_000_000,
                ),
            ),
            composer = SessionComposerUiModel(
                draftText = "Review the pending decision",
                selectionStart = 27,
                selectionEnd = 27,
                submitMode = SessionSubmitMode.SEND,
                canSubmit = false,
                canInterrupt = true,
                statusMessage = "Resolve the pending approval or question before sending more input.",
            ),
            artifacts = listOf(
                SessionArtifactUiModel(
                    stableKey = "artifact-key",
                    sessionKey = "session-key",
                    displayPath = "reports/result.txt",
                    changeLabel = "Modified",
                    availabilityMessage = "Ready to save a checked copy.",
                    suggestedFileName = "result.txt",
                    isDownloadable = true,
                    canSave = true,
                    bytesWritten = 0,
                    totalBytes = null,
                    isExporting = false,
                    isExportComplete = false,
                ),
            ),
            canRefreshArtifacts = true,
        ),
        selectedSessionKey = "session-key",
        operationError = null,
        isRefreshingProfiles = false,
        manageableConnectionProviders = listOf(
            ConnectionProviderUiModel(
                stableKey = "ssh.secure-shell",
                name = "Secure Shell",
            ),
        ),
    )
}

private fun actionHub(): SessionHubUiModel {
    val hub = testHub()
    val action = SessionActionUiModel(
        stableKey = "action-key",
        sessionKey = "session-key",
        title = "Choose validation scope",
        typeLabel = "Command approval",
        description = "The provider needs a scope before continuing.",
        command = "remove generated output",
        scope = "/workspace/project",
        connectionLabel = "Trusted server",
        connectionProviderName = "Secure Shell",
        connectionTarget = "Test endpoint",
        agentProviderLabel = "Codex",
        sessionTitle = "Investigate flaky build",
        questions = listOf(
            SessionQuestionUiModel(
                stableKey = "question-key",
                header = "Scope",
                prompt = "Which tests should run?",
                options = listOf(
                    SessionQuestionOptionUiModel(
                        label = "Focused tests",
                        description = "Run the focused validation suite.",
                    ),
                ),
                allowsOther = true,
                allowsMultiple = false,
            ),
        ),
        decisions = listOf(
            SessionDecisionUiModel(
                decision = AgentApprovalDecision.SUBMIT,
                label = "Submit answers",
                requiresConfirmation = true,
                isPositive = true,
            ),
            SessionDecisionUiModel(
                decision = AgentApprovalDecision.CANCEL,
                label = "Cancel",
                requiresConfirmation = false,
                isPositive = false,
            ),
        ),
        riskLabels = listOf("Credential or secret access"),
        state = SessionActionState.PENDING,
        completedDecisionLabel = null,
        additionalConfirmationGiven = false,
        isBusy = false,
    )
    return hub.copy(
        sessionLaunchers = listOf(
            SessionLauncherUiModel(
                stableKey = "launcher-key",
                connectionLabel = "Trusted server",
                connectionProviderName = "Secure Shell",
                agentProviderLabel = "Codex",
                suggestedWorkingDirectory = "/workspace/project",
            ),
        ),
        attentionActions = listOf(action),
        selectedSession = checkNotNull(hub.selectedSession).copy(actions = listOf(action)),
    )
}

private fun testSessionCreator() = SessionCreatorUiState(
    launcherKey = "launcher-key",
    connectionLabel = "Trusted server",
    connectionProviderName = "Secure Shell",
    agentProviderLabel = "Codex",
    workingDirectory = "/workspace/project",
)

private fun interactiveHub(): SessionHubUiModel {
    val hub = testHub()
    val detail = checkNotNull(hub.selectedSession)
    val runningSession = detail.session.copy(
        agentState = AgentSessionState.RUNNING,
        requiresActionCount = 0,
    )
    return hub.copy(
        sessions = hub.sessions.map { session ->
            if (session.stableKey == runningSession.stableKey) runningSession else session
        },
        selectedSession = detail.copy(
            session = runningSession,
            activities = emptyList(),
            composer = SessionComposerUiModel(
                draftText = "Keep the current scope",
                selectionStart = 22,
                selectionEnd = 22,
                submitMode = SessionSubmitMode.STEER,
                canSubmit = true,
                canInterrupt = true,
                statusMessage = null,
            ),
        ),
    )
}
private fun testEditor() = ConnectionProfileEditorUiState.Editing(
    providerId = "ssh.secure-shell",
    profileId = "ssh-profile",
    title = "Edit Secure Shell profile",
    fields = listOf(
        ConnectionProfileFieldUiModel(
            id = "profile-label",
            label = "Profile name",
            type = ConnectionProfileFieldType.TEXT,
            value = "Test fixture",
            supportingText = null,
            required = true,
            maxLength = 128,
            options = emptyList(),
            visibleWhen = emptyList(),
            hasStoredSecret = false,
        ),
        ConnectionProfileFieldUiModel(
            id = "jump-host",
            label = "Jump host",
            type = ConnectionProfileFieldType.SINGLE_CHOICE,
            value = "direct",
            supportingText = "Route through another configured SSH profile.",
            required = true,
            maxLength = 256,
            options = listOf(
                ConnectionProfileFieldOptionUiModel("direct", "Direct connection", null),
                ConnectionProfileFieldOptionUiModel(
                    "jump-profile",
                    "Staging bastion",
                    "Connect through this SSH profile.",
                ),
            ),
            visibleWhen = emptyList(),
            hasStoredSecret = false,
        ),
        ConnectionProfileFieldUiModel(
            id = "authentication",
            label = "Authentication",
            type = ConnectionProfileFieldType.SINGLE_CHOICE,
            value = "password",
            supportingText = null,
            required = true,
            maxLength = 256,
            options = listOf(
                ConnectionProfileFieldOptionUiModel("password", "Password", null),
                ConnectionProfileFieldOptionUiModel(
                    "imported-key",
                    "Imported private key",
                    "Paste an OpenSSH or PEM private key.",
                ),
            ),
            visibleWhen = emptyList(),
            hasStoredSecret = false,
        ),
        ConnectionProfileFieldUiModel(
            id = "password",
            label = "Password",
            type = ConnectionProfileFieldType.PASSWORD,
            value = "",
            supportingText = "Stored securely.",
            required = false,
            maxLength = 16_384,
            options = emptyList(),
            visibleWhen = listOf(
                ConnectionProfileFieldConditionUiModel("authentication", "password"),
            ),
            hasStoredSecret = true,
        ),
        ConnectionProfileFieldUiModel(
            id = "public-key",
            label = "App-managed public key",
            type = ConnectionProfileFieldType.READ_ONLY,
            value = "ssh-ed25519 AAAATESTKEY",
            supportingText = "The private key stays in Android Keystore.",
            required = false,
            maxLength = 8_192,
            options = emptyList(),
            visibleWhen = emptyList(),
            hasStoredSecret = false,
        ),
    ),
    operations = listOf(
        ConnectionProfileOperationUiModel(
            id = "install-public-key",
            label = "Install public key",
            supportingText = "Install the app-managed public key.",
            confirmationTitle = "Install public key on this remote account?",
            confirmationMessage =
            "Agent Relay will add only this app-managed public key to the remote account.",
        ),
        ConnectionProfileOperationUiModel(
            id = "verify-key-login",
            label = "Test key-only login",
            supportingText = "Test passwordless app-managed key login.",
            confirmationTitle = null,
            confirmationMessage = null,
        ),
    ),
    canDelete = true,
    fieldErrors = mapOf("profile-label" to "Enter a profile name."),
    error = "Correct the highlighted profile fields.",
)
