/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.example.agentrelay.R
import dev.agentrelay.session.api.SessionActionState

@Composable
internal fun SessionHubList(
    hub: SessionHubUiModel,
    actions: SessionHubActions,
    onSelectSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filters by remember { mutableStateOf(SessionListSearchFilterState()) }
    var sortOption by rememberSaveable { mutableStateOf(SessionListSortOption.LAST_APP_INTERACTION) }
    val filteredSessions = filterSessionList(hub.sessions, filters, System.currentTimeMillis())
    val surfaceBuckets = attentionSurfaceBuckets(sortSessionList(filteredSessions, sortOption))
    val labels = sessionSurfaceLabels()
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "hub-header") {
            HubHeader(hub, actions.refresh)
        }
        item(key = "session-list-search-filter") {
            SessionListSearchFilterItem(hub, filters, filteredSessions.size) { filters = it }
        }
        item(key = "session-list-sort") {
            SessionListSortControl(
                option = sortOption,
                onOptionSelected = { sortOption = it },
            )
        }
        hub.operationError?.let { message ->
            item(key = "operation-error") {
                MessageCard(
                    message.resolve(),
                    true,
                    stringResource(R.string.action_dismiss),
                    actions.dismissError,
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
            sessions = filteredSessions,
            buckets = surfaceBuckets,
            selectedSessionKey = hub.selectedSessionKey,
            labels = labels,
            onTogglePinned = actions.toggleSessionPinned,
            onSelectSession = onSelectSession,
            hasFilters = filters != SessionListSearchFilterState(),
        )
    }
}

@Composable
private fun sessionSurfaceLabels() = SessionSurfaceLabels(
    attentionTitle = stringResource(R.string.session_hub_attention_title),
    attentionSubtitle = stringResource(R.string.session_hub_attention_subtitle),
    changedTitle = stringResource(R.string.session_detail_changed_files),
    surfaceSubtitle = stringResource(R.string.session_hub_recent_sessions_subtitle),
    runningTitle = stringResource(R.string.session_state_running),
    recentTitle = stringResource(R.string.session_hub_recent_sessions_title),
)

@Composable
private fun SessionListSearchFilterItem(
    hub: SessionHubUiModel,
    filters: SessionListSearchFilterState,
    resultCount: Int,
    onFiltersChanged: (SessionListSearchFilterState) -> Unit,
) {
    SessionListSearchFilterControls(
        filters = filters,
        resultCount = resultCount,
        availableAgents = hub.sessions.map(SessionUiModel::agentProviderLabel).distinct().sorted(),
        availableHosts = hub.sessions.map(SessionUiModel::connectionLabel).distinct().sorted(),
        availableStates = hub.sessions.map(SessionUiModel::agentState).distinct().sortedBy { it.name },
        onFiltersChanged = onFiltersChanged,
    )
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
            modifier = Modifier.testTag("session-list-sort-control"),
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

private fun LazyListScope.sessionSurfaces(
    sessions: List<SessionUiModel>,
    buckets: AttentionSurfaceBuckets,
    selectedSessionKey: String?,
    labels: SessionSurfaceLabels,
    onTogglePinned: (String) -> Unit,
    onSelectSession: (String) -> Unit,
    hasFilters: Boolean,
) {
    if (sessions.isEmpty()) {
        item(key = "sessions-heading") {
            SectionHeading(title = labels.recentTitle, subtitle = labels.surfaceSubtitle)
        }
        item(key = "sessions-empty") {
            EmptyCard(
                stringResource(
                    if (hasFilters) {
                        R.string.session_list_filter_empty
                    } else {
                        R.string.session_hub_sessions_empty
                    },
                ),
            )
        }
        return
    }
    sessionSurfaceSection(
        key = "sessions-attention",
        title = labels.attentionTitle,
        subtitle = labels.attentionSubtitle,
        sessions = buckets.needsAttention,
        selectedSessionKey = selectedSessionKey,
        onSelectSession = onSelectSession,
        onTogglePinned = onTogglePinned,
    )
    sessionSurfaceSection(
        key = "sessions-changed",
        title = labels.changedTitle,
        subtitle = labels.surfaceSubtitle,
        sessions = buckets.changed,
        selectedSessionKey = selectedSessionKey,
        onSelectSession = onSelectSession,
        onTogglePinned = onTogglePinned,
    )
    sessionSurfaceSection(
        key = "sessions-running",
        title = labels.runningTitle,
        subtitle = labels.surfaceSubtitle,
        sessions = buckets.running,
        selectedSessionKey = selectedSessionKey,
        onSelectSession = onSelectSession,
        onTogglePinned = onTogglePinned,
    )
    sessionSurfaceSection(
        key = "sessions-recent",
        title = labels.recentTitle,
        subtitle = labels.surfaceSubtitle,
        sessions = buckets.recentlyCompleted,
        selectedSessionKey = selectedSessionKey,
        onSelectSession = onSelectSession,
        onTogglePinned = onTogglePinned,
    )
}

private data class SessionSurfaceLabels(
    val attentionTitle: String,
    val attentionSubtitle: String,
    val changedTitle: String,
    val surfaceSubtitle: String,
    val runningTitle: String,
    val recentTitle: String,
)

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
