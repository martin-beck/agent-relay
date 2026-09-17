/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import com.example.agentrelay.R
import dev.agentrelay.session.api.SessionActionState

@Composable
internal fun SessionHubList(
    hub: SessionHubUiModel,
    actions: SessionHubActions,
    onSelectSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val surfaceBuckets = attentionSurfaceBuckets(hub.sessions)
    val attentionTitle = stringResource(R.string.session_hub_attention_title)
    val attentionSubtitle = stringResource(R.string.session_hub_attention_subtitle)
    val changedTitle = stringResource(R.string.session_detail_changed_files)
    val surfaceSubtitle = stringResource(R.string.session_hub_recent_sessions_subtitle)
    val runningTitle = stringResource(R.string.session_state_running)
    val recentTitle = stringResource(R.string.session_hub_recent_sessions_title)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val undoLabel = stringResource(R.string.action_undo)
    val operationErrorMessage = hub.operationError?.resolve()
    fun dismissWithUndo(message: String) {
        actions.dismissError()
        scope.launch {
            if (snackbarHostState.showSnackbar(message, undoLabel) == SnackbarResult.ActionPerformed) {
                actions.restoreError()
            }
        }
    }
    Box(modifier) {
      LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item(key = "hub-header") {
            HubHeader(hub, actions.refresh)
        }
        operationErrorMessage?.let { resolvedMessage ->
            item(key = "operation-error") {
                MessageCard(
                    resolvedMessage,
                    true,
                    stringResource(R.string.action_dismiss),
                    { dismissWithUndo(resolvedMessage) },
                    onSwipeAction = { dismissWithUndo(resolvedMessage) },
                )
            }
        }
        items(hub.issues, key = { "issue:" + it.id }) { issue ->
            MessageCard(
                message = issue.message.resolve(),
                isError = !issue.recoverable,
                actionLabel = if (issue.recoverable) {
                    stringResource(R.string.action_refresh)
                } else {
                    null
                },
                onAction = if (issue.recoverable) actions.refresh else null,
                onSwipeAction = if (issue.recoverable) {
                    { actions.refresh() }
                } else {
                    null
                },
            )
        }
        if (hub.attentionActions.isNotEmpty()) {
            item(key = "attention-heading") {
                SectionHeading(
                    title = stringResource(R.string.session_hub_attention_title),
                    subtitle = stringResource(R.string.session_hub_attention_subtitle),
                )
            }
            items(
                hub.attentionActions,
                key = { "attention:" + it.stableKey },
            ) { action ->
                AttentionActionCard(
                    action = action,
                    onReview = { onSelectSession(action.sessionKey) },
                )
            }
        }
        item(key = "connections-heading") {
            SectionHeading(
                title = stringResource(R.string.session_hub_connections_title),
                subtitle = stringResource(R.string.session_hub_connections_subtitle),
            )
        }
        items(
            hub.manageableConnectionProviders,
            key = { "add-profile:" + it.stableKey },
        ) { provider ->
            OutlinedButton(
                onClick = { actions.addProfile(provider.stableKey) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.session_hub_add_profile, provider.name))
            }
        }
        if (hub.connections.isEmpty()) {
            item(key = "connections-empty") {
                EmptyCard(stringResource(R.string.session_hub_connections_empty))
            }
        } else {
            items(hub.connections, key = ConnectionUiModel::stableKey) { connection ->
                ConnectionCard(
                    connection = connection,
                    onConnect = { actions.connect(connection.stableKey) },
                    onDisconnect = { actions.disconnect(connection.stableKey) },
                    onTrustIdentity = { replace ->
                        actions.trustIdentity(connection.stableKey, replace)
                    },
                    onRejectIdentity = { actions.rejectIdentity(connection.stableKey) },
                    onEdit = { actions.editProfile(connection.stableKey) },
                )
            }
        }
        sessionLaunchers(hub.sessionLaunchers, actions.openSessionCreator)
        sessionSurfaces(
            sessions = hub.sessions,
            buckets = surfaceBuckets,
            selectedSessionKey = hub.selectedSessionKey,
            attentionTitle = attentionTitle,
            attentionSubtitle = attentionSubtitle,
            changedTitle = changedTitle,
            surfaceSubtitle = surfaceSubtitle,
            runningTitle = runningTitle,
            recentTitle = recentTitle,
            onSelectSession = onSelectSession,
        )
      }
      SnackbarHost(
          hostState = snackbarHostState,
          modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
      )
    }
}

private fun LazyListScope.sessionSurfaces(
    sessions: List<SessionUiModel>,
    buckets: AttentionSurfaceBuckets,
    selectedSessionKey: String?,
    attentionTitle: String,
    attentionSubtitle: String,
    changedTitle: String,
    surfaceSubtitle: String,
    runningTitle: String,
    recentTitle: String,
    onSelectSession: (String) -> Unit,
) {
    if (sessions.isEmpty()) {
        item(key = "sessions-heading") {
            SectionHeading(title = recentTitle, subtitle = surfaceSubtitle)
        }
        item(key = "sessions-empty") {
            EmptyCard(stringResource(R.string.session_hub_sessions_empty))
        }
        return
    }
    sessionSurfaceSection(
        key = "sessions-attention",
        title = attentionTitle,
        subtitle = attentionSubtitle,
        sessions = buckets.needsAttention,
        selectedSessionKey = selectedSessionKey,
        onSelectSession = onSelectSession,
    )
    sessionSurfaceSection(
        key = "sessions-changed",
        title = changedTitle,
        subtitle = surfaceSubtitle,
        sessions = buckets.changed,
        selectedSessionKey = selectedSessionKey,
        onSelectSession = onSelectSession,
    )
    sessionSurfaceSection(
        key = "sessions-running",
        title = runningTitle,
        subtitle = surfaceSubtitle,
        sessions = buckets.running,
        selectedSessionKey = selectedSessionKey,
        onSelectSession = onSelectSession,
    )
    sessionSurfaceSection(
        key = "sessions-recent",
        title = recentTitle,
        subtitle = surfaceSubtitle,
        sessions = buckets.recentlyCompleted,
        selectedSessionKey = selectedSessionKey,
        onSelectSession = onSelectSession,
    )
}

private fun LazyListScope.sessionSurfaceSection(
    key: String,
    title: String,
    subtitle: String,
    sessions: List<SessionUiModel>,
    selectedSessionKey: String?,
    onSelectSession: (String) -> Unit,
) {
    if (sessions.isEmpty()) return
    item(key = "$key-heading") {
        SectionHeading(title = title, subtitle = subtitle)
    }
    items(sessions, key = SessionUiModel::stableKey) { session ->
        SessionCard(
            session = session,
            selected = session.stableKey == selectedSessionKey,
            onClick = { onSelectSession(session.stableKey) },
        )
    }
}

private fun LazyListScope.sessionLaunchers(
    launchers: List<SessionLauncherUiModel>,
    onOpen: (String) -> Unit,
) {
    if (launchers.isEmpty()) {
        return
    }
    item(key = "start-sessions-heading") {
        SectionHeading(
            title = stringResource(R.string.session_creator_title),
            subtitle = stringResource(R.string.session_hub_start_subtitle),
        )
    }
    items(
        launchers,
        key = { "session-launcher:" + it.stableKey },
    ) { launcher ->
        OutlinedButton(
            onClick = { onOpen(launcher.stableKey) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                Text(
                    stringResource(
                        R.string.session_hub_start_on_connection,
                        launcher.agentProviderLabel,
                        launcher.connectionLabel,
                    ),
                )
                Text(launcher.connectionProviderName, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun HubHeader(
    hub: SessionHubUiModel,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = hub.availableConnectionProviders.joinToString(separator = stringResource(R.string.list_separator)),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        OutlinedButton(
            onClick = onRefresh,
            enabled = !hub.isRefreshingProfiles,
            modifier = Modifier.align(Alignment.End),
        ) {
            if (hub.isRefreshingProfiles) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text(stringResource(R.string.action_refresh))
            }
        }
        Text(
            text = stringResource(R.string.session_hub_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeading(
    title: String,
    subtitle: String,
) {
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MessageCard(
    message: String,
    isError: Boolean,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    onSwipeAction: (() -> Unit)? = null,
) {
    SwipeActionSurface(
        modifier = Modifier.fillMaxWidth(),
        accessibilityActionLabel = actionLabel,
        onAction = if (onSwipeAction == null) null else { { onSwipeAction() } },
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
            colors = CardDefaults.cardColors(
                containerColor = if (isError) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.tertiaryContainer
                },
            ),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (actionLabel != null && onAction != null) {
                    TextButton(
                        onClick = onAction,
                        modifier = Modifier.align(Alignment.End),
                    ) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun AttentionActionCard(
    action: SessionActionUiModel,
    onReview: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().semantics {
            liveRegion = LiveRegionMode.Polite
        },
        colors = CardDefaults.cardColors(
            containerColor = if (action.risks.isNotEmpty()) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = if (action.state == SessionActionState.DELIVERING) {
                    stringResource(R.string.session_hub_response_pending_confirmation)
                } else {
                    action.type.localizedLabel()
                },
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = action.title.resolve(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(
                    R.string.session_hub_action_context,
                    action.connectionProviderName,
                    action.connectionLabel,
                    action.agentProviderLabel,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            action.risks.forEach { risk ->
                Text(
                    text = stringResource(R.string.session_hub_risk, risk.localizedLabel()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            OutlinedButton(
                onClick = onReview,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        R.string.session_hub_review_in,
                        action.sessionTitle.resolve(),
                    ),
                )
            }
        }
    }
}

@Composable
private fun EmptyCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(20.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
