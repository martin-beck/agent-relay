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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun SessionHubList(
    hub: SessionHubUiModel,
    actions: SessionHubActions,
    onSelectSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "hub-header") {
            HubHeader(hub, actions.refresh)
        }
        hub.operationError?.let { message ->
            item(key = "operation-error") {
                MessageCard(message, true, "Dismiss", actions.dismissError)
            }
        }
        items(hub.issues, key = { "issue:" + it.id }) { issue ->
            MessageCard(
                message = issue.message,
                isError = !issue.recoverable,
                actionLabel = if (issue.recoverable) "Refresh" else null,
                onAction = if (issue.recoverable) actions.refresh else null,
            )
        }
        item(key = "connections-heading") {
            SectionHeading(
                title = "Connections",
                subtitle = "Local and remote access share one provider-neutral session hub.",
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
                Text("Add ${provider.name} profile")
            }
        }
        if (hub.connections.isEmpty()) {
            item(key = "connections-empty") {
                EmptyCard("No connection profiles are available. Refresh to try again.")
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
        item(key = "sessions-heading") {
            SectionHeading(
                title = "Recent sessions",
                subtitle = "Unread output and required decisions stay visible across connections.",
            )
        }
        if (hub.sessions.isEmpty()) {
            item(key = "sessions-empty") {
                EmptyCard(
                    "No sessions have been discovered yet. Connect a profile to check its agent providers.",
                )
            }
        } else {
            items(hub.sessions, key = SessionUiModel::stableKey) { session ->
                SessionCard(
                    session = session,
                    selected = session.stableKey == hub.selectedSessionKey,
                    onClick = { onSelectSession(session.stableKey) },
                )
            }
        }
    }
}

@Composable
private fun HubHeader(
    hub: SessionHubUiModel,
    onRefresh: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Agent Relay",
                    modifier = Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = hub.availableConnectionProviders.joinToString(separator = "  -  "),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
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
                    Text("Refresh")
                }
            }
        }
        Text(
            text = "Continue agent work across this device and trusted SSH hosts.",
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
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel)
                }
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
