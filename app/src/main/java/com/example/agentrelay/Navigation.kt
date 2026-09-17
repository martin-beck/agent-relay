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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
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
import com.example.agentrelay.background.BackgroundTransportState
import com.example.agentrelay.notifications.NotificationCenterAction
import com.example.agentrelay.notifications.NotificationCenterScreen
import com.example.agentrelay.notifications.NotificationCenterState
import com.example.agentrelay.notifications.reduce
import com.example.agentrelay.ui.main.MainScreen
import com.example.agentrelay.ui.main.MainScreenUiState
import com.example.agentrelay.ui.main.MainScreenViewModel
import com.example.agentrelay.ui.main.MainScreenSurface
import com.example.agentrelay.ui.main.QuickNavigationDestination
import com.example.agentrelay.ui.main.QuickNavigationDestinationId
import com.example.agentrelay.ui.main.QuickNavigationFooter
import com.example.agentrelay.ui.main.QUICK_NAVIGATION_FOOTER_TEST_TAG
import com.example.agentrelay.ui.main.SessionDetailRoute
import com.example.agentrelay.ui.main.SpeechInputUiActions
import com.example.agentrelay.ui.main.rememberArtifactSaveRequest
import com.example.agentrelay.ui.main.rememberSpeechStartRequest
import com.example.agentrelay.settings.AndroidSettingsStore
import com.example.agentrelay.settings.SettingsScreen
import androidx.navigation3.runtime.NavKey
import com.example.agentrelay.R

internal fun navigateToTopLevel(backStack: MutableList<NavKey>, route: NavKey) {
    while (backStack.size > 1) backStack.removeAt(backStack.lastIndex)
    if (backStack.lastOrNull() != route) backStack.add(route)
}

internal fun canNavigateBack(backStack: List<NavKey>): Boolean = backStack.size > 1

internal fun topLevelRoot(route: NavKey): NavKey = if (route is SessionDetails) Main else route

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
        if (canNavigateBack(backStack)) {
            backStack.removeLastOrNull()
            if (backStack.lastOrNull() !is SessionDetails) mainViewModel.clearSelection()
        }
    }
    var notificationState by remember { mutableStateOf(NotificationCenterState()) }
    val readyHub = (uiState as? MainScreenUiState.Ready)?.hub
    LaunchedEffect(readyHub?.notificationActivities) {
        readyHub?.notificationActivities?.let { activities ->
            notificationState = notificationState.reduce(NotificationCenterAction.Replace(activities))
        }
    }
    val quickNavigationDestinations = topLevelDestinations(
        selectedRoute = backStack.lastOrNull(),
        onNavigate = { route ->
            navigateToTopLevel(backStack, route)
            mainViewModel.clearSelection()
        },
    )
    val openSession: (String) -> Unit = { key ->
        val route = SessionDetails(key)
        if (backStack.lastOrNull() != route) {
            if (backStack.lastOrNull() is SessionDetails) backStack.removeLastOrNull()
            backStack.add(route)
        }
    }

    LaunchedEffect(notificationNavigationKey, uiState) {
        val sessionKey = notificationNavigationKey ?: return@LaunchedEffect
        val ready = uiState as? MainScreenUiState.Ready ?: return@LaunchedEffect
        if (ready.hub.sessions.any { session -> session.stableKey == sessionKey }) {
            if (backStack.lastOrNull() !is SessionDetails) {
                navigateToTopLevel(backStack, Main)
            }
            openSession(sessionKey)
        } else {
            mainViewModel.selectSession(sessionKey)
        }
        onNotificationNavigationConsumed(sessionKey)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                NavDisplay(
                    backStack = backStack,
                    onBack = onBack,
                    entryProvider =
                    entryProvider {
                        entry<Main> {
                            MainSurfaceRoute(
                                viewModel = mainViewModel,
                                surface = MainScreenSurface.EXISTING_SESSIONS,
                                onOpenSession = openSession,
                                onOpenSettings = { navigateToTopLevel(backStack, Settings) },
                                speechActions = speechActions,
                                saveArtifact = saveArtifact,
                                notificationPermissionState = notificationPermissionState,
                                onRequestNotificationPermission = onRequestNotificationPermission,
                                onOpenNotificationSettings = onOpenNotificationSettings,
                                backgroundTransportState = backgroundTransportState,
                                onStartBackgroundTransport = application.backgroundTransport::start,
                                onStopBackgroundTransport = application.backgroundTransport::stop,
                                quickNavigationDestinations = quickNavigationDestinations,
                            )
                        }
                        entry<PinnedSessions> {
                            MainSurfaceRoute(
                                viewModel = mainViewModel,
                                surface = MainScreenSurface.PINNED_SESSIONS,
                                onOpenSession = openSession,
                                onOpenSettings = { navigateToTopLevel(backStack, Settings) },
                                speechActions = speechActions,
                                saveArtifact = saveArtifact,
                                notificationPermissionState = notificationPermissionState,
                                onRequestNotificationPermission = onRequestNotificationPermission,
                                onOpenNotificationSettings = onOpenNotificationSettings,
                                backgroundTransportState = backgroundTransportState,
                                onStartBackgroundTransport = application.backgroundTransport::start,
                                onStopBackgroundTransport = application.backgroundTransport::stop,
                                quickNavigationDestinations = quickNavigationDestinations,
                            )
                        }
                        entry<NewSession> {
                            MainSurfaceRoute(
                                viewModel = mainViewModel,
                                surface = MainScreenSurface.NEW_SESSION,
                                onOpenSession = openSession,
                                onOpenSettings = { navigateToTopLevel(backStack, Settings) },
                                speechActions = speechActions,
                                saveArtifact = saveArtifact,
                                notificationPermissionState = notificationPermissionState,
                                onRequestNotificationPermission = onRequestNotificationPermission,
                                onOpenNotificationSettings = onOpenNotificationSettings,
                                backgroundTransportState = backgroundTransportState,
                                onStartBackgroundTransport = application.backgroundTransport::start,
                                onStopBackgroundTransport = application.backgroundTransport::stop,
                                quickNavigationDestinations = quickNavigationDestinations,
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
                        entry<NotificationCenter> {
                            NotificationCenterScreen(
                                state = notificationState,
                                onAction = { action ->
                                    notificationState = notificationState.reduce(action)
                                    if (action == NotificationCenterAction.RefreshStarted) {
                                        mainViewModel.refreshProfiles()
                                    }
                                },
                                modifier = Modifier.safeDrawingPadding().padding(16.dp),
                            )
                        }
                    },
                )
            }
            QuickNavigationFooter(
                destinations = quickNavigationDestinations,
                modifier = Modifier.testTag(QUICK_NAVIGATION_FOOTER_TEST_TAG),
            )
        }
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
@Suppress("LongParameterList")
private fun MainSurfaceRoute(
    viewModel: MainScreenViewModel,
    surface: MainScreenSurface,
    onOpenSession: (String) -> Unit,
    onOpenSettings: () -> Unit,
    speechActions: SpeechInputUiActions,
    saveArtifact: (String, String, String) -> Unit,
    notificationPermissionState: SessionNotificationPermissionState,
    onRequestNotificationPermission: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    backgroundTransportState: BackgroundTransportState,
    onStartBackgroundTransport: () -> Unit,
    onStopBackgroundTransport: () -> Unit,
    quickNavigationDestinations: List<QuickNavigationDestination>,
) {
    MainScreen(
        viewModel = viewModel,
        onOpenSession = onOpenSession,
        onOpenSettings = onOpenSettings,
        speechActions = speechActions,
        notificationPermissionState = notificationPermissionState,
        onRequestNotificationPermission = onRequestNotificationPermission,
        onOpenNotificationSettings = onOpenNotificationSettings,
        backgroundTransportState = backgroundTransportState,
        onStartBackgroundTransport = onStartBackgroundTransport,
        onStopBackgroundTransport = onStopBackgroundTransport,
        onSaveArtifact = saveArtifact,
        surface = surface,
        quickNavigationDestinations = quickNavigationDestinations,
        modifier = Modifier.safeDrawingPadding().padding(16.dp),
    )
}

@Composable
private fun topLevelDestinations(
    selectedRoute: NavKey?,
    onNavigate: (NavKey) -> Unit,
): List<QuickNavigationDestination> {
    val root = selectedRoute?.let(::topLevelRoot)
    return listOf(
        QuickNavigationDestination(
            id = QuickNavigationDestinationId.SESSIONS,
            label = stringResource(R.string.quick_navigation_sessions),
            selected = root == Main,
            onClick = { onNavigate(Main) },
        ),
        QuickNavigationDestination(
            id = QuickNavigationDestinationId.PINNED,
            label = stringResource(R.string.session_card_pinned),
            selected = root == PinnedSessions,
            onClick = { onNavigate(PinnedSessions) },
        ),
        QuickNavigationDestination(
            id = QuickNavigationDestinationId.NEW_SESSION,
            label = stringResource(R.string.session_creator_title),
            selected = root == NewSession,
            onClick = { onNavigate(NewSession) },
        ),
        QuickNavigationDestination(
            id = QuickNavigationDestinationId.SETTINGS,
            label = stringResource(R.string.quick_navigation_settings),
            selected = root == Settings,
            onClick = { onNavigate(Settings) },
        ),
        QuickNavigationDestination(
            id = QuickNavigationDestinationId.NOTIFICATIONS,
            label = stringResource(R.string.notification_center_open),
            selected = root == NotificationCenter,
            onClick = { onNavigate(NotificationCenter) },
        ),
    )
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
