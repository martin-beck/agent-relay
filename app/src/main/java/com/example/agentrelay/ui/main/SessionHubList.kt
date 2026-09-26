/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch
import com.example.agentrelay.R
import dev.agentrelay.session.api.SessionActionState

@Composable
@Suppress("LongMethod", "CognitiveComplexMethod")
internal fun SessionHubList(
    hub: SessionHubUiModel,
    actions: SessionHubActions,
    onSelectSession: (String) -> Unit,
    modifier: Modifier = Modifier,
    mode: SessionHubListMode = SessionHubListMode.SESSIONS,
) {
    var sortOption by rememberSaveable { mutableStateOf(SessionListSortOption.LAST_APP_INTERACTION) }
    val surfaceBuckets = remember(hub.sessions, sortOption) {
        attentionSurfaceBuckets(sortSessionList(hub.sessions, sortOption))
    }
    val attentionTitle = stringResource(R.string.session_hub_attention_title)
    val attentionSubtitle = stringResource(R.string.session_hub_attention_subtitle)
    val changedTitle = stringResource(R.string.session_detail_changed_files)
    val surfaceSubtitle = stringResource(R.string.session_hub_recent_sessions_subtitle)
    val runningTitle = stringResource(R.string.session_state_running)
    val recentTitle = stringResource(R.string.session_hub_recent_sessions_title)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val pullToRefreshState = rememberPullToRefreshState()
    val undoLabel = stringResource(R.string.action_undo)
    val operationErrorMessage = hub.operationError?.resolve()
    val footerClearance = with(LocalDensity.current) {
        (80.dp * fontScale.coerceAtLeast(1f) * 2f) + 16.dp
    }
    fun dismissWithUndo(message: String) {
        actions.dismissError()
        scope.launch {
            if (snackbarHostState.showSnackbar(message, undoLabel) == SnackbarResult.ActionPerformed) {
                actions.restoreError()
            }
        }
    }
    PullToRefreshBox(
        isRefreshing = hub.isRefreshingProfiles,
        onRefresh = actions.refresh,
        state = pullToRefreshState,
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullToRefreshState,
                isRefreshing = hub.isRefreshingProfiles,
                modifier = Modifier.clearAndSetSemantics {},
            )
        },
        modifier = modifier,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(SESSION_HUB_SCROLL_LIST_TEST_TAG),
            // The persistent quick-navigation footer occupies the lower edge of the
            // screen. Leave enough scroll clearance for the last hub card to move above it.
            contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = footerClearance),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "hub-header") {
                HubHeader(hub, actions.refresh, actions.openSettings)
            }
            if (mode != SessionHubListMode.NEW_SESSION) {
                item(key = "session-list-sort") {
                    SessionListSortControl(
                        option = sortOption,
                        onOptionSelected = { sortOption = it },
                    )
                }
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
            if (mode == SessionHubListMode.SESSIONS && hub.attentionActions.isNotEmpty()) {
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
            if (mode == SessionHubListMode.NEW_SESSION) {
                item(key = "connections-heading") {
                    SectionHeading(
                        title = stringResource(R.string.session_hub_connections_title),
                        subtitle = stringResource(R.string.session_hub_connections_subtitle),
                    )
                }
            }
            if (mode == SessionHubListMode.NEW_SESSION) {
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
            }
            if (mode == SessionHubListMode.NEW_SESSION && hub.connections.isEmpty()) {
                item(key = "connections-empty") {
                    EmptyCard(stringResource(R.string.session_hub_connections_empty))
                }
            } else if (mode == SessionHubListMode.NEW_SESSION) {
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
            if (mode == SessionHubListMode.NEW_SESSION) {
                sessionLaunchers(hub.sessionLaunchers, actions.openSessionCreator)
            }
            if (mode == SessionHubListMode.NEW_SESSION && hub.sessions.isEmpty()) {
                item(key = "new-session-sessions-empty-heading") {
                    SectionHeading(title = recentTitle, subtitle = surfaceSubtitle)
                }
                item(key = "new-session-sessions-empty") {
                    EmptyCard(stringResource(R.string.session_hub_sessions_empty))
                }
            }
            if (mode != SessionHubListMode.NEW_SESSION) {
                sessionSurfaces(
                    sessions = sortSessionList(hub.sessions, sortOption),
                    buckets = surfaceBuckets,
                    selectedSessionKey = hub.selectedSessionKey,
                    attentionTitle = attentionTitle,
                    attentionSubtitle = attentionSubtitle,
                    changedTitle = changedTitle,
                    surfaceSubtitle = surfaceSubtitle,
                    runningTitle = runningTitle,
                    recentTitle = recentTitle,
                    onTogglePinned = actions.toggleSessionPinned,
                    onSelectSession = onSelectSession,
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
        )
    }
}

internal enum class SessionHubListMode {
    SESSIONS,
    NEW_SESSION,
}

@Composable
private fun SessionListSortControl(
    option: SessionListSortOption,
    onOptionSelected: (SessionListSortOption) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column {
        TextButton(
            onClick = { expanded = true },
            modifier = Modifier
                .widthIn(min = 48.dp)
                .testTag("session-list-sort-control"),
        ) {
            Text(
                stringResource(
                    R.string.session_list_sort_label,
                    when (option) {
                        SessionListSortOption.LAST_APP_INTERACTION ->
                            stringResource(R.string.session_list_sort_app_interaction)
                        SessionListSortOption.LAST_LLM_RESPONSE ->
                            stringResource(R.string.session_list_sort_llm_response)
                    },
                ),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SessionListSortOption.entries.forEach { candidate ->
                DropdownMenuItem(
                    text = {
                        Text(
                            when (candidate) {
                                SessionListSortOption.LAST_APP_INTERACTION ->
                                    stringResource(R.string.session_list_sort_app_interaction)
                                SessionListSortOption.LAST_LLM_RESPONSE ->
                                    stringResource(R.string.session_list_sort_llm_response)
                            },
                        )
                    },
                    onClick = {
                        onOptionSelected(candidate)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Suppress("LongParameterList")
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
    onTogglePinned: (String) -> Unit,
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
        onTogglePinned = onTogglePinned,
    )
    sessionSurfaceSection(
        key = "sessions-changed",
        title = changedTitle,
        subtitle = surfaceSubtitle,
        sessions = buckets.changed,
        selectedSessionKey = selectedSessionKey,
        onSelectSession = onSelectSession,
        onTogglePinned = onTogglePinned,
    )
    sessionSurfaceSection(
        key = "sessions-running",
        title = runningTitle,
        subtitle = surfaceSubtitle,
        sessions = buckets.running,
        selectedSessionKey = selectedSessionKey,
        onSelectSession = onSelectSession,
        onTogglePinned = onTogglePinned,
    )
    sessionSurfaceSection(
        key = "sessions-recent",
        title = recentTitle,
        subtitle = surfaceSubtitle,
        sessions = buckets.recentlyCompleted,
        selectedSessionKey = selectedSessionKey,
        onSelectSession = onSelectSession,
        onTogglePinned = onTogglePinned,
    )
    if (buckets.recentlyCompleted.isEmpty()) {
        item(key = "sessions-recent-empty-heading") {
            SectionHeading(title = recentTitle, subtitle = surfaceSubtitle)
        }
    }
}

private fun LazyListScope.sessionSurfaceSection(
    key: String,
    title: String,
    subtitle: String,
    sessions: List<SessionUiModel>,
    selectedSessionKey: String?,
    onTogglePinned: (String) -> Unit,
    onSelectSession: (String) -> Unit,
) {
    if (sessions.isEmpty()) return
    item(key = "$key-heading") {
        SectionHeading(title = title, subtitle = subtitle)
    }
    sessions.groupBy(SessionUiModel::agentProviderLabel).forEach { (agent, agentSessions) ->
        item(key = "$key-agent-$agent") {
            SectionHeading(title = agent, subtitle = subtitle)
        }
        items(agentSessions, key = SessionUiModel::stableKey) { session ->
            SessionCard(
                session = session,
                selected = session.stableKey == selectedSessionKey,
                onClick = { onSelectSession(session.stableKey) },
                onTogglePinned = { onTogglePinned(session.stableKey) },
            )
        }
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
    onOpenSettings: () -> Unit,
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.quick_navigation_settings))
            }
            OutlinedButton(
                onClick = onRefresh,
                enabled = !hub.isRefreshingProfiles,
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
internal fun MessageCard(
    message: String,
    isError: Boolean,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onSwipeAction: (() -> Unit)? = null,
) {
    SwipeActionSurface(
        modifier = modifier.fillMaxWidth(),
        accessibilityActionLabel = actionLabel,
        onAction = if (onSwipeAction == null) {
            null
        } else {
            { onSwipeAction() }
        },
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
