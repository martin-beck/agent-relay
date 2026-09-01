package com.example.agentrelay.ui.main

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.agentrelay.session.api.SessionActivityType
import java.text.DateFormat
import java.util.Date

@Composable
internal fun SessionDetailRoute(
    state: MainScreenUiState,
    onBack: () -> Unit,
    onDraftChanged: (String, String, Int, Int) -> Unit,
    onSubmitDraft: (String) -> Unit,
    onResumeSession: (String) -> Unit,
    onInterruptSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text("Back to sessions")
        }
        when (state) {
            MainScreenUiState.Loading -> DetailPlaceholder("Opening session state...")
            is MainScreenUiState.FatalError -> DetailPlaceholder(state.message)
            is MainScreenUiState.Ready -> SessionDetailPane(
                detail = state.hub.selectedSession,
                modifier = Modifier.weight(1f),
                onDraftChanged = onDraftChanged,
                onSubmitDraft = onSubmitDraft,
                onResumeSession = onResumeSession,
                onInterruptSession = onInterruptSession,
            )
        }
    }
}

@Composable
internal fun SessionDetailPane(
    detail: SessionDetailUiModel?,
    modifier: Modifier = Modifier,
    onDraftChanged: (String, String, Int, Int) -> Unit = { _, _, _, _ -> },
    onSubmitDraft: (String) -> Unit = {},
    onResumeSession: (String) -> Unit = {},
    onInterruptSession: (String) -> Unit = {},
) {
    if (detail == null) {
        Box(
            modifier = modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Select a session to inspect its transcript and activity.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "detail-header") {
            SessionDetailHeader(detail.session)
        }
        item(key = "session-composer") {
            SessionComposer(
                detail = detail,
                onDraftChanged = onDraftChanged,
                onSubmitDraft = onSubmitDraft,
                onResumeSession = onResumeSession,
                onInterruptSession = onInterruptSession,
            )
        }
        item(key = "timeline-heading") {
            DetailHeading("Timeline")
        }
        if (detail.transcript.isEmpty()) {
            item(key = "timeline-empty") {
                DetailPlaceholder(
                    "No timeline entries have been cached. Reconnect to refresh this session.",
                )
            }
        } else {
            items(detail.transcript, key = { "transcript:" + it.id }) { entry ->
                TranscriptCard(entry)
            }
        }
        item(key = "activity-heading") {
            DetailHeading("Activity")
        }
        if (detail.activities.isEmpty()) {
            item(key = "activity-empty") {
                DetailPlaceholder("No recent activity is recorded for this session.")
            }
        } else {
            items(detail.activities, key = { "activity:" + it.id }) { activity ->
                ActivityCard(activity)
            }
        }
    }
}

@Composable
private fun SessionComposer(
    detail: SessionDetailUiModel,
    onDraftChanged: (String, String, Int, Int) -> Unit,
    onSubmitDraft: (String) -> Unit,
    onResumeSession: (String) -> Unit,
    onInterruptSession: (String) -> Unit,
) {
    val composer = detail.composer
    val sessionKey = detail.session.stableKey
    val projectedValue = TextFieldValue(
        text = composer.draftText,
        selection = TextRange(composer.selectionStart, composer.selectionEnd),
    )
    var editorValue by remember(sessionKey) { mutableStateOf(projectedValue) }
    LaunchedEffect(
        composer.draftText,
        composer.selectionStart,
        composer.selectionEnd,
    ) {
        if (editorValue.text != projectedValue.text || editorValue.selection != projectedValue.selection) {
            editorValue = projectedValue
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DetailHeading("Message")
        OutlinedTextField(
            value = editorValue,
            onValueChange = { changed ->
                if (changed.text.length <= MAX_SESSION_DRAFT_CHARS) {
                    editorValue = changed
                }
                onDraftChanged(
                    sessionKey,
                    changed.text,
                    changed.selection.start,
                    changed.selection.end,
                )
            },
            modifier = Modifier.fillMaxWidth().testTag("session-composer-input"),
            label = { Text("Message to ${detail.session.agentProviderLabel}") },
            enabled = !composer.isBusy,
            supportingText = {
                Text(
                    text = composer.statusMessage
                        ?: "This draft stays with the session until you send it.",
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            },
            minLines = 3,
            maxLines = 8,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (composer.isBusy) {
                CircularProgressIndicator()
            }
            if (composer.canResume) {
                OutlinedButton(onClick = { onResumeSession(sessionKey) }) {
                    Text("Resume session")
                }
            }
            if (composer.canInterrupt) {
                OutlinedButton(onClick = { onInterruptSession(sessionKey) }) {
                    Text("Interrupt turn")
                }
            }
            Button(
                onClick = { onSubmitDraft(sessionKey) },
                enabled = composer.canSubmit,
            ) {
                Text(
                    if (composer.submitMode == SessionSubmitMode.STEER) {
                        "Steer active turn"
                    } else {
                        "Send"
                    },
                )
            }
        }
    }
}

@Composable
private fun SessionDetailHeader(session: SessionUiModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = session.title,
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = session.connectionProviderName + "  -  " +
                session.connectionLabel + "  -  " + session.agentProviderLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        session.projectPath?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = sessionStateLabel(session.agentState),
                style = MaterialTheme.typography.labelMedium,
            )
            session.lastActivityAtEpochMillis?.let {
                Text(
                    text = formatTimestamp(it),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (session.preview.isNotBlank()) {
            Text(
                text = session.preview,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun DetailHeading(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun TranscriptCard(entry: TranscriptEntryUiModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (entry.kind) {
                TimelineEntryKind.USER_MESSAGE -> MaterialTheme.colorScheme.primaryContainer
                TimelineEntryKind.AGENT_FINAL -> MaterialTheme.colorScheme.secondaryContainer
                TimelineEntryKind.PLAN -> MaterialTheme.colorScheme.tertiaryContainer
                TimelineEntryKind.TOOL -> MaterialTheme.colorScheme.surfaceContainerHigh
                TimelineEntryKind.SYSTEM -> MaterialTheme.colorScheme.surfaceContainerLowest
                TimelineEntryKind.AGENT_COMMENTARY,
                TimelineEntryKind.REASONING_SUMMARY,
                -> MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.roleLabel,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                entry.createdAtEpochMillis?.let {
                    Text(
                        text = formatTimestamp(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            SelectionContainer {
                Text(
                    text = entry.text,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (entry.wasTruncated) {
                Text(
                    text = "Long entry truncated for safe rendering.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ActivityCard(activity: SessionActivityUiModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (activity.requiresAction) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row {
                Text(
                    text = activityTypeLabel(activity.type),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (activity.requiresAction) FontWeight.Bold else FontWeight.Medium,
                )
                Text(
                    text = formatTimestamp(activity.occurredAtEpochMillis),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                text = activity.summary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DetailPlaceholder(message: String) {
    Text(
        text = message,
        modifier = Modifier.padding(20.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun activityTypeLabel(type: SessionActivityType): String = when (type) {
    SessionActivityType.NEW_OUTPUT -> "New output"
    SessionActivityType.APPROVAL_REQUIRED -> "Approval required"
    SessionActivityType.QUESTION -> "Question"
    SessionActivityType.FAILURE -> "Failure"
    SessionActivityType.RECONNECTED -> "Reconnected"
    SessionActivityType.TURN_COMPLETED -> "Turn completed"
}

private fun formatTimestamp(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMillis))
