package com.example.agentrelay.ui.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.agentrelay.theme.AgentRelayTheme
import dev.agentrelay.provider.api.AgentSessionState

@Composable
internal fun MainScreen(
    viewModel: MainScreenViewModel,
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onOpenSession) {
        SessionHubActions(
            retry = viewModel::retryInitialization,
            refresh = viewModel::refreshProfiles,
            connect = viewModel::connect,
            disconnect = viewModel::disconnect,
            trustIdentity = viewModel::trustIdentity,
            rejectIdentity = viewModel::rejectIdentity,
            selectSession = viewModel::selectSession,
            openSession = onOpenSession,
            dismissError = viewModel::clearOperationError,
        )
    }
    MainScreenContent(
        state = state,
        actions = actions,
        modifier = modifier,
    )
}

@Composable
internal fun MainScreenContent(
    state: MainScreenUiState,
    actions: SessionHubActions,
    modifier: Modifier = Modifier,
) {
    when (state) {
        MainScreenUiState.Loading -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        is MainScreenUiState.FatalError -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.layout.Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(
                    onClick = actions.retry,
                    modifier = Modifier.padding(top = 16.dp),
                ) {
                    Text("Retry")
                }
            }
        }

        is MainScreenUiState.Ready -> AdaptiveSessionHub(
            hub = state.hub,
            actions = actions,
            modifier = modifier,
        )
    }
}

@Composable
private fun AdaptiveSessionHub(
    hub: SessionHubUiModel,
    actions: SessionHubActions,
    modifier: Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val expanded = maxWidth >= EXPANDED_LAYOUT_MIN_WIDTH
        val selectSession: (String) -> Unit = { key ->
            actions.selectSession(key)
            if (!expanded) {
                actions.openSession(key)
            }
        }
        if (expanded) {
            Row(Modifier.fillMaxSize()) {
                SessionHubList(
                    hub = hub,
                    actions = actions,
                    onSelectSession = selectSession,
                    modifier = Modifier.weight(0.44f),
                )
                VerticalDivider()
                SessionDetailPane(
                    detail = hub.selectedSession,
                    modifier = Modifier.weight(0.56f),
                )
            }
        } else {
            SessionHubList(
                hub = hub,
                actions = actions,
                onSelectSession = selectSession,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Immutable
internal data class SessionHubActions(
    val retry: () -> Unit,
    val refresh: () -> Unit,
    val connect: (String) -> Unit,
    val disconnect: (String) -> Unit,
    val trustIdentity: (String, Boolean) -> Unit,
    val rejectIdentity: (String) -> Unit,
    val selectSession: (String) -> Unit,
    val openSession: (String) -> Unit,
    val dismissError: () -> Unit,
)

@Preview(showBackground = true)
@Composable
private fun MainScreenPreview() {
    AgentRelayTheme {
        MainScreenContent(
            state = MainScreenUiState.Ready(previewSessionHub()),
            actions = previewActions(),
        )
    }
}

@Preview(showBackground = true, widthDp = 1_000, heightDp = 720)
@Composable
private fun MainScreenExpandedPreview() {
    AgentRelayTheme {
        MainScreenContent(
            state = MainScreenUiState.Ready(previewSessionHub()),
            actions = previewActions(),
        )
    }
}

private fun previewActions() = SessionHubActions(
    retry = {},
    refresh = {},
    connect = {},
    disconnect = {},
    trustIdentity = { _, _ -> },
    rejectIdentity = {},
    selectSession = {},
    openSession = {},
    dismissError = {},
)

private fun previewSessionHub(): SessionHubUiModel {
    val session = SessionUiModel(
        stableKey = "preview-session",
        title = "Refine the Android session hub",
        preview = "The provider-neutral runtime is connected and ready for the next instruction.",
        connectionLabel = "This device",
        connectionProviderName = "Local",
        agentProviderLabel = "Codex",
        projectPath = "/workspace/agent-relay",
        agentState = AgentSessionState.IDLE,
        unreadCount = 2,
        requiresActionCount = 0,
        lastActivityAtEpochMillis = 1_788_200_000_000,
        isPinned = true,
    )
    return SessionHubUiModel(
        availableConnectionProviders = listOf("Local", "Secure Shell"),
        connections = listOf(
            ConnectionUiModel(
                stableKey = "preview-connection",
                providerName = "Local",
                label = "This device",
                target = "This device",
                authenticationLabel = null,
                status = ConnectionStatus.ONLINE,
                statusDetail = null,
                connectedAgentCount = 1,
                agentCount = 6,
                unavailableAgentCount = 5,
                canConnect = false,
                canDisconnect = true,
                isBusy = false,
                identityChallenge = null,
            ),
        ),
        sessions = listOf(session),
        issues = emptyList(),
        selectedSession = SessionDetailUiModel(session, emptyList(), emptyList()),
        selectedSessionKey = session.stableKey,
        operationError = null,
        isRefreshingProfiles = false,
    )
}

private val EXPANDED_LAYOUT_MIN_WIDTH = 840.dp
