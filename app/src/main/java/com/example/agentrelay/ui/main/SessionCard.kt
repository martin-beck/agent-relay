package com.example.agentrelay.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.agentrelay.R
import dev.agentrelay.provider.api.AgentSessionState

@Composable
internal fun SessionCard(
    session: SessionUiModel,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accessibilityLabel = sessionAccessibilityLabel(session)
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = accessibilityLabel
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
                    text = session.title.resolve(),
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
                text = session.preview.ifBlank { stringResource(R.string.session_card_no_preview) },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    R.string.session_hub_action_context,
                    session.connectionProviderName,
                    session.connectionLabel,
                    session.agentProviderLabel,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = sessionStateLabel(session.agentState),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (session.requiresActionCount > 0) {
                    Text(
                        text = pluralStringResource(
                            R.plurals.session_card_awaiting_action,
                            session.requiresActionCount,
                            session.requiresActionCount,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (session.isPinned) {
                    Text(
                        text = stringResource(R.string.session_card_pinned),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

@Composable
internal fun sessionStateLabel(state: AgentSessionState): String = stringResource(
    when (state) {
        AgentSessionState.NOT_LOADED -> R.string.session_state_not_loaded
        AgentSessionState.IDLE -> R.string.session_state_idle
        AgentSessionState.RUNNING -> R.string.session_state_running
        AgentSessionState.WAITING_FOR_APPROVAL -> R.string.session_state_waiting_for_approval
        AgentSessionState.FAILED -> R.string.connection_status_failed
        AgentSessionState.UNKNOWN -> R.string.session_state_unknown
    },
)

@Composable
private fun sessionAccessibilityLabel(session: SessionUiModel): String {
    val state = sessionStateLabel(session.agentState)
    val context = stringResource(
        R.string.session_card_accessibility_context,
        session.connectionProviderName,
        session.connectionLabel,
        session.agentProviderLabel,
    )
    val unread = if (session.unreadCount > 0) {
        pluralStringResource(
            R.plurals.session_card_unread,
            session.unreadCount,
            session.unreadCount,
        )
    } else {
        null
    }
    val awaitingAction = if (session.requiresActionCount > 0) {
        pluralStringResource(
            R.plurals.session_card_awaiting_action,
            session.requiresActionCount,
            session.requiresActionCount,
        )
    } else {
        null
    }
    val pinned = stringResource(R.string.session_card_pinned).takeIf { session.isPinned }
    return listOfNotNull(session.title.resolve(), state, context, unread, awaitingAction, pinned)
        .joinToString(stringResource(R.string.accessibility_separator))
}
