/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.agentrelay.notifications.SessionNotificationPermissionState
import com.example.agentrelay.ui.main.MainScreen
import com.example.agentrelay.ui.main.MainScreenUiState
import com.example.agentrelay.ui.main.MainScreenViewModel
import com.example.agentrelay.ui.main.QuickNavigationDestinationId
import com.example.agentrelay.ui.main.SessionDetailRoute
import com.example.agentrelay.ui.main.SpeechInputUiActions
import com.example.agentrelay.ui.main.rememberArtifactSaveRequest
import com.example.agentrelay.ui.main.rememberSpeechStartRequest
import com.example.agentrelay.settings.AndroidSettingsStore
import com.example.agentrelay.settings.SettingsScreen

@Composable
@Suppress("LongMethod")
internal fun MainNavigation(
    notificationNavigationKey: String? = null,
    onNotificationNavigationConsumed: (String) -> Unit = {},
    notificationPermissionState: SessionNotificationPermissionState =
        SessionNotificationPermissionState.HIDDEN,
    onRequestNotificationPermission: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    pairingHandoffState: kotlinx.coroutines.flow.StateFlow<PairingHandoffUiState?>? = null,
    onPairingApproved: (VerifiedPairingAppLink) -> Unit = {},
    onPairingDismissed: () -> Unit = {},
) {
    val application = LocalContext.current.applicationContext as AgentRelayApplication
    val backgroundTransportState by
        application.backgroundTransport.state.collectAsStateWithLifecycle()
    val pairingState by (pairingHandoffState ?: remember { kotlinx.coroutines.flow.MutableStateFlow<PairingHandoffUiState?>(null) })
        .collectAsStateWithLifecycle()
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

    Box(modifier = Modifier.fillMaxSize()) {
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
                        onOpenSettings = { backStack.add(Settings) },
                        onQuickNavigation = { destination ->
                            when (destination) {
                                QuickNavigationDestinationId.SETTINGS -> {
                                    if (backStack.lastOrNull() != Settings) {
                                        backStack.add(Settings)
                                    }
                                }
                                else -> {
                                    while (backStack.lastOrNull() != Main) {
                                        backStack.removeLastOrNull()
                                    }
                                    mainViewModel.clearSelection()
                                }
                            }
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
                entry<Settings> {
                    SettingsScreen(
                        store = AndroidSettingsStore(LocalContext.current.applicationContext),
                        onBack = onBack,
                    )
                }
            },
        )
        when (val state = pairingState) {
            is PairingHandoffUiState.Review -> PairingAppLinkReview(
                profile = state.verified.profile,
                onApprove = { onPairingApproved(state.verified) },
                onDismiss = onPairingDismissed,
            )
            PairingHandoffUiState.Rejected -> PairingAppLinkRejected(onDismiss = onPairingDismissed)
            null -> Unit
        }
    }
}

@Composable
private fun PairingAppLinkReview(
    profile: dev.agentrelay.connection.api.PairingEnrollmentProfile,
    onApprove: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Review secure pairing")
            Spacer(modifier = Modifier.height(12.dp))
            Text("Confirm the verified daemon before enrolling this device.")
            Text("Daemon: ${profile.daemonIdentity.value}")
            Text("Requested scope: secure enrollment and the advertised host route.")
            Text("Authentication phrase: compare the host phrase before approving.")
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onApprove) { Text("Approve pairing") }
            Button(onClick = onDismiss) { Text("Reject") }
        }
    }
}

@Composable
private fun PairingAppLinkRejected(onDismiss: () -> Unit) {
    Card(modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Pairing link rejected")
            Spacer(modifier = Modifier.height(12.dp))
            Text("The link was expired, replayed, or not trusted. Return to Agent Relay and scan a new code.")
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onDismiss) { Text("Return to Agent Relay") }
        }
    }
}
