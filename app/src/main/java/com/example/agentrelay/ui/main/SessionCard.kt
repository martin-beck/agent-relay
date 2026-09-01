package com.example.agentrelay.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentrelay.provider.api.AgentSessionState

@Composable
internal fun SessionCard(
    session: SessionUiModel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = sessionAccessibilityLabel(session)
                this.selected = selected
            },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = session.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (session.unreadCount > 0) {
                    Text(
                        text = session.unreadCount.toString(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = session.preview.ifBlank { "No preview is available." },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = session.connectionProviderName + "  -  " +
                    session.connectionLabel + "  -  " + session.agentProviderLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = sessionStateLabel(session.agentState),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (session.requiresActionCount > 0) {
                    Text(
                        text = "${session.requiresActionCount} awaiting action",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (session.isPinned) {
                    Text(
                        text = "Pinned",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

internal fun sessionStateLabel(state: AgentSessionState): String = when (state) {
    AgentSessionState.NOT_LOADED -> "Not loaded"
    AgentSessionState.IDLE -> "Idle"
    AgentSessionState.RUNNING -> "Running"
    AgentSessionState.WAITING_FOR_APPROVAL -> "Waiting for approval"
    AgentSessionState.FAILED -> "Failed"
    AgentSessionState.UNKNOWN -> "Unknown"
}

private fun sessionAccessibilityLabel(session: SessionUiModel): String = buildList {
    add(session.title)
    add(sessionStateLabel(session.agentState))
    add(
        session.connectionProviderName + " connection " +
            session.connectionLabel + ", " + session.agentProviderLabel + " agent",
    )
    if (session.unreadCount > 0) {
        add("${session.unreadCount} unread")
    }
    if (session.requiresActionCount > 0) {
        add("${session.requiresActionCount} awaiting action")
    }
    if (session.isPinned) {
        add("Pinned")
    }
}.joinToString(separator = ". ")
