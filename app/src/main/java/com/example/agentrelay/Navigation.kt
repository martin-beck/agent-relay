package com.example.agentrelay

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.agentrelay.notifications.SessionNotificationPermissionState
import com.example.agentrelay.ui.main.MainScreen
import com.example.agentrelay.ui.main.MainScreenUiState
import com.example.agentrelay.ui.main.MainScreenViewModel
import com.example.agentrelay.ui.main.SessionDetailRoute
import com.example.agentrelay.ui.main.SpeechInputUiActions
import com.example.agentrelay.ui.main.rememberArtifactSaveRequest
import com.example.agentrelay.ui.main.rememberSpeechStartRequest

@Composable
internal fun MainNavigation(
    notificationNavigationKey: String? = null,
    onNotificationNavigationConsumed: (String) -> Unit = {},
    notificationPermissionState: SessionNotificationPermissionState =
        SessionNotificationPermissionState.HIDDEN,
    onRequestNotificationPermission: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
) {
    val application = LocalContext.current.applicationContext as AgentRelayApplication
    val backgroundTransportState by
        application.backgroundTransport.state.collectAsStateWithLifecycle()
    val mainViewModel = viewModel {
        MainScreenViewModel {
            application.graph.sessionHubRuntime()
        }
    }
    val uiState by mainViewModel.uiState.collectAsStateWithLifecycle()
    val saveArtifact = rememberArtifactSaveRequest(mainViewModel)
    val requestSpeechStart = rememberSpeechStartRequest(
        onPermissionGranted = mainViewModel.speechActions::start,
        selectedSessionKey = (uiState as? MainScreenUiState.Ready)?.hub?.selectedSessionKey,
        onPermissionDenied = mainViewModel.speechActions::permissionDenied,
    )
    val speechActions = remember(mainViewModel, requestSpeechStart) {
        SpeechInputUiActions(
            selectModel = mainViewModel.speechActions::selectModel,
            installModel = mainViewModel.speechActions::installModel,
            cancelModelInstall = mainViewModel.speechActions::cancelModelInstall,
            requestStart = requestSpeechStart,
            stop = mainViewModel.speechActions::stop,
            cancel = mainViewModel.speechActions::cancel,
            useTranscript = mainViewModel.speechActions::useTranscript,
            dismiss = mainViewModel.speechActions::dismiss,
        )
    }
    val backStack = rememberNavBackStack(Main)
    val onBack: () -> Unit = {
        backStack.removeLastOrNull()
        mainViewModel.clearSelection()
    }

    LaunchedEffect(notificationNavigationKey, uiState) {
        val sessionKey = notificationNavigationKey ?: return@LaunchedEffect
        val ready = uiState as? MainScreenUiState.Ready ?: return@LaunchedEffect
        val route = SessionDetails(sessionKey)
        if (ready.hub.sessions.any { session -> session.stableKey == sessionKey }) {
            if (backStack.lastOrNull() != route) {
                if (backStack.lastOrNull() is SessionDetails) {
                    backStack.removeLastOrNull()
                }
                backStack.add(route)
            }
        } else {
            mainViewModel.selectSession(sessionKey)
        }
        onNotificationNavigationConsumed(sessionKey)
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
                    speechActions = speechActions,
                    notificationPermissionState = notificationPermissionState,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onOpenNotificationSettings = onOpenNotificationSettings,
                    backgroundTransportState = backgroundTransportState,
                    onStartBackgroundTransport = application.backgroundTransport::start,
                    onStopBackgroundTransport = application.backgroundTransport::stop,
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
                    speechActions = speechActions,
                    modifier = Modifier.safeDrawingPadding(),
                )
            }
        },
    )
}
