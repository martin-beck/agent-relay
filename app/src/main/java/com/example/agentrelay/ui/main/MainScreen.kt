package com.example.agentrelay.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.agentrelay.R
import com.example.agentrelay.background.BackgroundTransportState
import com.example.agentrelay.notifications.SessionNotificationPermissionState
import dev.agentrelay.provider.api.AgentApprovalDecision

internal const val MAIN_LOADING_TEST_TAG = "main-loading"
internal const val MAIN_FATAL_ERROR_TEST_TAG = "main-fatal-error"
internal const val SESSION_HUB_LIST_TEST_TAG = "session-hub-list"
internal const val SESSION_DETAIL_PANE_TEST_TAG = "session-detail-pane"
internal const val NOTIFICATION_PERMISSION_TEST_TAG = "notification-permission"
internal const val BACKGROUND_TRANSPORT_TEST_TAG = "background-transport"

@Composable
internal fun MainScreen(
    viewModel: MainScreenViewModel,
    onOpenSession: (String) -> Unit,
    onSaveArtifact: (String, String, String) -> Unit,
    speechActions: SpeechInputUiActions,
    modifier: Modifier = Modifier,
    notificationPermissionState: SessionNotificationPermissionState =
        SessionNotificationPermissionState.HIDDEN,
    onRequestNotificationPermission: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    backgroundTransportState: BackgroundTransportState = BackgroundTransportState.STOPPED,
    onStartBackgroundTransport: () -> Unit = {},
    onStopBackgroundTransport: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val actions = remember(viewModel, onOpenSession, onSaveArtifact, speechActions) {
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
            addProfile = viewModel::addProfile,
            editProfile = viewModel::editProfile,
            updateProfileField = viewModel::updateProfileField,
            dismissProfileEditor = viewModel::dismissProfileEditor,
            saveProfile = viewModel::saveProfile,
            requestProfileOperation = viewModel.profileOperations::request,
            cancelProfileOperation = viewModel.profileOperations::cancel,
            confirmProfileOperation = viewModel.profileOperations::confirm,
            requestProfileDeletion = viewModel::requestProfileDeletion,
            cancelProfileDeletion = viewModel::cancelProfileDeletion,
            deleteProfile = viewModel::deleteProfile,
            updateSessionDraft = viewModel::updateSessionDraft,
            submitSessionDraft = viewModel::submitSessionDraft,
            resumeSession = viewModel::resumeSession,
            interruptSession = viewModel::interruptSession,
            openSessionCreator = viewModel::openSessionCreator,
            updateSessionCreatorWorkingDirectory = viewModel::updateSessionCreatorWorkingDirectory,
            updateSessionCreatorModel = viewModel::updateSessionCreatorModel,
            dismissSessionCreator = viewModel::dismissSessionCreator,
            startSession = viewModel::startSession,
            respondToAction = viewModel::respondToAction,
            refreshArtifacts = viewModel.artifactInteractions::refreshArtifacts,
            saveArtifact = onSaveArtifact,
            cancelArtifactExport = viewModel.artifactInteractions::cancelArtifactExport,
            speechInput = speechActions,
        )
    }
    MainScreenContent(
        state = state,
        actions = actions,
        notificationPermissionState = notificationPermissionState,
        onRequestNotificationPermission = onRequestNotificationPermission,
        onOpenNotificationSettings = onOpenNotificationSettings,
        backgroundTransportState = backgroundTransportState,
        onStartBackgroundTransport = onStartBackgroundTransport,
        onStopBackgroundTransport = onStopBackgroundTransport,
        modifier = modifier,
    )
}

@Composable
internal fun MainScreenContent(
    state: MainScreenUiState,
    actions: SessionHubActions,
    modifier: Modifier = Modifier,
    notificationPermissionState: SessionNotificationPermissionState =
        SessionNotificationPermissionState.HIDDEN,
    onRequestNotificationPermission: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    backgroundTransportState: BackgroundTransportState = BackgroundTransportState.STOPPED,
    onStartBackgroundTransport: () -> Unit = {},
    onStopBackgroundTransport: () -> Unit = {},
) {
    when (state) {
        MainScreenUiState.Loading -> Box(
            modifier = modifier.fillMaxSize().testTag(MAIN_LOADING_TEST_TAG),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(R.string.main_loading),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        is MainScreenUiState.FatalError -> Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp)
                .testTag(MAIN_FATAL_ERROR_TEST_TAG),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = state.message.resolve(),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = actions.retry,
                ) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }

        is MainScreenUiState.Ready -> {
            Column(modifier.fillMaxSize()) {
                if (notificationPermissionState != SessionNotificationPermissionState.HIDDEN) {
                    SessionNotificationPermissionCard(
                        state = notificationPermissionState,
                        onRequestPermission = onRequestNotificationPermission,
                        onOpenSettings = onOpenNotificationSettings,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    )
                }
                BackgroundTransportCard(
                    state = backgroundTransportState,
                    notificationsAvailable =
                    notificationPermissionState == SessionNotificationPermissionState.HIDDEN,
                    onStart = onStartBackgroundTransport,
                    onStop = onStopBackgroundTransport,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                )
                AdaptiveSessionHub(
                    hub = state.hub,
                    speechInput = state.speechInput,
                    actions = actions,
                    modifier = Modifier.weight(1f),
                )
            }
            state.profileEditor?.let { editor ->
                ConnectionProfileEditorDialog(editor, actions)
            }
            state.sessionCreator?.let { creator ->
                SessionCreatorDialog(
                    state = creator,
                    onWorkingDirectoryChanged = actions.updateSessionCreatorWorkingDirectory,
                    onModelChanged = actions.updateSessionCreatorModel,
                    onDismiss = actions.dismissSessionCreator,
                    onStart = actions.startSession,
                )
            }
        }
    }
}

@Composable
private fun SessionNotificationPermissionCard(
    state: SessionNotificationPermissionState,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val explanation = when (state) {
        SessionNotificationPermissionState.REQUESTABLE ->
            stringResource(R.string.notification_permission_request_explanation)
        SessionNotificationPermissionState.RATIONALE ->
            stringResource(R.string.notification_permission_rationale)
        SessionNotificationPermissionState.SETTINGS_REQUIRED ->
            stringResource(R.string.notification_permission_settings_explanation)
        SessionNotificationPermissionState.HIDDEN -> return
    }
    val buttonLabel = if (state == SessionNotificationPermissionState.SETTINGS_REQUIRED) {
        stringResource(R.string.notification_permission_open_settings)
    } else {
        stringResource(R.string.notification_permission_allow)
    }
    val onClick = if (state == SessionNotificationPermissionState.SETTINGS_REQUIRED) {
        onOpenSettings
    } else {
        onRequestPermission
    }

    Card(modifier.testTag(NOTIFICATION_PERMISSION_TEST_TAG)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.notification_permission_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = onClick,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(buttonLabel)
            }
        }
    }
}

@Composable
private fun BackgroundTransportCard(
    state: BackgroundTransportState,
    notificationsAvailable: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val explanation = when (state) {
        BackgroundTransportState.STOPPED ->
            if (notificationsAvailable) {
                stringResource(R.string.background_transport_stopped)
            } else {
                stringResource(R.string.background_transport_requires_notifications)
            }
        BackgroundTransportState.STARTING ->
            stringResource(R.string.background_transport_starting)
        BackgroundTransportState.ACTIVE ->
            stringResource(R.string.background_transport_active)
        BackgroundTransportState.STOPPING ->
            stringResource(R.string.background_transport_stopping)
        BackgroundTransportState.START_FAILED ->
            stringResource(R.string.background_transport_failed)
    }
    val buttonLabel = when (state) {
        BackgroundTransportState.STOPPED ->
            stringResource(R.string.background_transport_start)
        BackgroundTransportState.STARTING ->
            stringResource(R.string.background_transport_starting)
        BackgroundTransportState.ACTIVE ->
            stringResource(R.string.background_transport_stop_button)
        BackgroundTransportState.STOPPING ->
            stringResource(R.string.background_transport_stopping)
        BackgroundTransportState.START_FAILED ->
            stringResource(R.string.background_transport_retry)
    }
    val stopRequested = state == BackgroundTransportState.ACTIVE ||
        state == BackgroundTransportState.STOPPING
    val buttonEnabled = when (state) {
        BackgroundTransportState.STOPPED,
        BackgroundTransportState.START_FAILED,
        -> notificationsAvailable
        BackgroundTransportState.ACTIVE -> true
        BackgroundTransportState.STARTING,
        BackgroundTransportState.STOPPING,
        -> false
    }

    Card(modifier.testTag(BACKGROUND_TRANSPORT_TEST_TAG)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.background_transport_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(
                onClick = if (stopRequested) onStop else onStart,
                enabled = buttonEnabled,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(buttonLabel)
            }
        }
    }
}

@Composable
private fun AdaptiveSessionHub(
    hub: SessionHubUiModel,
    speechInput: SpeechInputUiState,
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
                    modifier = Modifier
                        .weight(0.44f)
                        .testTag(SESSION_HUB_LIST_TEST_TAG),
                )
                VerticalDivider()
                SessionDetailPane(
                    detail = hub.selectedSession,
                    speechInput = speechInput,
                    speechActions = actions.speechInput,
                    modifier = Modifier
                        .weight(0.56f)
                        .testTag(SESSION_DETAIL_PANE_TEST_TAG),
                    onDraftChanged = actions.updateSessionDraft,
                    onSubmitDraft = actions.submitSessionDraft,
                    onResumeSession = actions.resumeSession,
                    onInterruptSession = actions.interruptSession,
                    onRespondToAction = actions.respondToAction,
                    onRefreshArtifacts = actions.refreshArtifacts,
                    onSaveArtifact = actions.saveArtifact,
                    onCancelArtifact = actions.cancelArtifactExport,
                )
            }
        } else {
            SessionHubList(
                hub = hub,
                actions = actions,
                onSelectSession = selectSession,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(SESSION_HUB_LIST_TEST_TAG),
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
    val addProfile: (String) -> Unit = {},
    val editProfile: (String) -> Unit = {},
    val updateProfileField: (String, String) -> Unit = { _, _ -> },
    val dismissProfileEditor: () -> Unit = {},
    val saveProfile: () -> Unit = {},
    val requestProfileOperation: (String) -> Unit = {},
    val cancelProfileOperation: () -> Unit = {},
    val confirmProfileOperation: () -> Unit = {},
    val requestProfileDeletion: () -> Unit = {},
    val cancelProfileDeletion: () -> Unit = {},
    val deleteProfile: () -> Unit = {},
    val updateSessionDraft: (String, String, Int, Int) -> Unit = { _, _, _, _ -> },
    val submitSessionDraft: (String) -> Unit = {},
    val resumeSession: (String) -> Unit = {},
    val interruptSession: (String) -> Unit = {},
    val openSessionCreator: (String) -> Unit = {},
    val updateSessionCreatorWorkingDirectory: (String) -> Unit = {},
    val updateSessionCreatorModel: (String) -> Unit = {},
    val dismissSessionCreator: () -> Unit = {},
    val startSession: () -> Unit = {},
    val respondToAction: (
        String,
        String,
        AgentApprovalDecision,
        Map<String, List<String>>,
        Boolean,
    ) -> Unit = { _, _, _, _, _ -> },
    val refreshArtifacts: (String) -> Unit = {},
    val saveArtifact: (String, String, String) -> Unit = { _, _, _ -> },
    val cancelArtifactExport: (String) -> Unit = {},
    val speechInput: SpeechInputUiActions = SpeechInputUiActions(),
)

private val EXPANDED_LAYOUT_MIN_WIDTH = 840.dp
