package com.example.agentrelay.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import com.example.agentrelay.theme.AgentRelayTheme
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.session.api.SessionActivityType
import org.junit.Rule
import org.junit.Test

class MainScreenTest {

    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun fatalError_explainsRecoveryAndRetries() {
        val recorder = ActionRecorder()
        setContent(MainScreenUiState.FatalError("Session storage is unavailable."), recorder)

        composeTestRule.onNodeWithText("Session storage is unavailable.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Retry").performClick()

        check(recorder.retryCount == 1)
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
        composeTestRule.onNodeWithText("Transcript").assertExists()
        composeTestRule.onNodeWithText("Cached agent output").assertExists()
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
}

private class ActionRecorder {
    var retryCount = 0
    var connectedKey: String? = null
    var trustedKey: String? = null
    var replaceIdentity = false
    var selectedKey: String? = null
    var openedKey: String? = null

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
    )
}

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
                    text = "Cached agent output",
                    wasTruncated = false,
                    createdAtEpochMillis = 1_788_200_000_000,
                ),
            ),
        ),
        selectedSessionKey = "session-key",
        operationError = null,
        isRefreshingProfiles = false,
    )
}
