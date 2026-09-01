package com.example.agentrelay

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.agentrelay.ui.main.MainScreen
import com.example.agentrelay.ui.main.MainScreenViewModel
import com.example.agentrelay.ui.main.SessionDetailRoute
import com.example.agentrelay.ui.main.rememberArtifactSaveRequest

@Composable
fun MainNavigation() {
    val application = LocalContext.current.applicationContext as AgentRelayApplication
    val mainViewModel = viewModel {
        MainScreenViewModel {
            application.graph.sessionHubRuntime()
        }
    }
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val saveArtifact = rememberArtifactSaveRequest(mainViewModel)
    val backStack = rememberNavBackStack(Main)
    val onBack: () -> Unit = {
        backStack.removeLastOrNull()
        mainViewModel.clearSelection()
    }

    NavDisplay(
        backStack = backStack,
        onBack = onBack,
        entryProvider =
        entryProvider {
            entry<Main> {
                MainScreen(
                    viewModel = mainViewModel,
                    onOpenSession = { key ->
                        backStack.add(SessionDetails(key))
                    },
                    onSaveArtifact = saveArtifact,
                    modifier = Modifier.safeDrawingPadding().padding(16.dp),
                )
            }
            entry<SessionDetails> { route ->
                LaunchedEffect(route.sessionKey) {
                    mainViewModel.selectSession(route.sessionKey)
                }
                SessionDetailRoute(
                    state = uiState,
                    onBack = onBack,
                    onDraftChanged = mainViewModel::updateSessionDraft,
                    onSubmitDraft = mainViewModel::submitSessionDraft,
                    onResumeSession = mainViewModel::resumeSession,
                    onInterruptSession = mainViewModel::interruptSession,
                    onRespondToAction = mainViewModel::respondToAction,
                    onRefreshArtifacts = mainViewModel.artifactInteractions::refreshArtifacts,
                    onSaveArtifact = saveArtifact,
                    onCancelArtifact = mainViewModel.artifactInteractions::cancelArtifactExport,
                    modifier = Modifier.safeDrawingPadding(),
                )
            }
        },
    )
}
