/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main
import androidx.annotation.StringRes

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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.agentrelay.R
import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.session.api.SessionActivityType
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@Composable
internal fun SessionDetailRoute(
    state: MainScreenUiState,
    onBack: () -> Unit,
    onDraftChanged: (String, String, Int, Int) -> Unit,
    onSubmitDraft: (String) -> Unit,
    onResumeSession: (String) -> Unit,
    onInterruptSession: (String) -> Unit,
    onRespondToAction: SessionActionResponder,
    onRefreshArtifacts: (String) -> Unit,
    onSaveArtifact: (String, String, String) -> Unit,
    onCancelArtifact: (String) -> Unit,
    modifier: Modifier = Modifier,
    speechActions: SpeechInputUiActions = SpeechInputUiActions(),
) {
    Column(modifier.fillMaxSize()) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text(stringResource(R.string.session_detail_back_to_sessions))
        }
        when (state) {
            MainScreenUiState.Loading -> DetailPlaceholder(stringResource(R.string.session_detail_opening))
            is MainScreenUiState.FatalError -> DetailPlaceholder(state.message.resolve())
            is MainScreenUiState.Ready -> SessionDetailPane(
                detail = state.hub.selectedSession,
                speechInput = state.speechInput,
                speechActions = speechActions,
                modifier = Modifier.weight(1f),
                onDraftChanged = onDraftChanged,
                onSubmitDraft = onSubmitDraft,
                onResumeSession = onResumeSession,
                onInterruptSession = onInterruptSession,
                onRespondToAction = onRespondToAction,
                onRefreshArtifacts = onRefreshArtifacts,
                onSaveArtifact = onSaveArtifact,
                onCancelArtifact = onCancelArtifact,
            )
        }
    }
}

@Composable
internal fun SessionDetailPane(
    detail: SessionDetailUiModel?,
    modifier: Modifier = Modifier,
    speechInput: SpeechInputUiState = unavailableSpeechInputState(),
    speechActions: SpeechInputUiActions = SpeechInputUiActions(),
    onDraftChanged: (String, String, Int, Int) -> Unit = { _, _, _, _ -> },
    onSubmitDraft: (String) -> Unit = {},
    onResumeSession: (String) -> Unit = {},
    onInterruptSession: (String) -> Unit = {},
    onRespondToAction: SessionActionResponder = { _, _, _, _, _ -> },
    onRefreshArtifacts: (String) -> Unit = {},
    onSaveArtifact: (String, String, String) -> Unit = { _, _, _ -> },
    onCancelArtifact: (String) -> Unit = {},
) {
    if (detail == null) {
        Box(
            modifier = modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.session_detail_select_session),
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
        if (detail.actions.isNotEmpty()) {
            item(key = "actions-heading") {
                DetailHeading(stringResource(R.string.session_detail_approvals_and_questions))
            }
            items(
                detail.actions,
                key = { "action:" + it.stableKey },
            ) { action ->
                SessionActionCard(
                    action = action,
                    onRespond = onRespondToAction,
                )
            }
        }
        item(key = "session-composer") {
            SessionComposer(
                detail = detail,
                speechInput = speechInput,
                speechActions = speechActions,
                onDraftChanged = onDraftChanged,
                onSubmitDraft = onSubmitDraft,
                onResumeSession = onResumeSession,
                onInterruptSession = onInterruptSession,
            )
        }
        item(key = "artifacts-heading") {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DetailHeading(stringResource(R.string.session_detail_changed_files))
                if (detail.canRefreshArtifacts) {
                    OutlinedButton(
                        onClick = { onRefreshArtifacts(detail.session.stableKey) },
                        enabled = !detail.isRefreshingArtifacts,
                    ) {
                        Text(stringResource(R.string.session_detail_refresh_changed_files))
                    }
                }
                if (detail.isRefreshingArtifacts) {
                    CircularProgressIndicator()
                }
            }
        }
        if (detail.artifacts.isEmpty()) {
            item(key = "artifacts-empty") {
                DetailPlaceholder(
                    if (detail.canRefreshArtifacts) {
                        stringResource(R.string.session_detail_no_changed_files)
                    } else {
                        stringResource(R.string.session_detail_changed_files_unsupported)
                    },
                )
            }
        } else {
            items(detail.artifacts, key = { "artifact:" + it.stableKey }) { artifact ->
                ArtifactCard(
                    artifact = artifact,
                    onSaveArtifact = onSaveArtifact,
                    onCancelArtifact = onCancelArtifact,
                )
            }
        }
        item(key = "timeline-heading") {
            DetailHeading(stringResource(R.string.session_detail_timeline))
        }
        if (detail.transcript.isEmpty()) {
            item(key = "timeline-empty") {
                DetailPlaceholder(
                    stringResource(R.string.session_detail_timeline_empty),
                )
            }
        } else {
            items(detail.transcript, key = { "transcript:" + it.id }) { entry ->
                TranscriptCard(entry)
            }
        }
        item(key = "activity-heading") {
            DetailHeading(stringResource(R.string.session_detail_activity))
        }
        if (detail.activities.isEmpty()) {
            item(key = "activity-empty") {
                DetailPlaceholder(stringResource(R.string.session_detail_activity_empty))
            }
        } else {
            items(detail.activities, key = { "activity:" + it.id }) { activity ->
                ActivityCard(activity)
            }
        }
    }
}

@get:StringRes
private val AgentFileChangeKind.labelResource: Int
    get() = when (this) {
        AgentFileChangeKind.ADDED -> R.string.session_artifact_change_added
        AgentFileChangeKind.MODIFIED -> R.string.session_artifact_change_modified
        AgentFileChangeKind.DELETED -> R.string.session_artifact_change_deleted
        AgentFileChangeKind.RENAMED -> R.string.session_artifact_change_renamed
        AgentFileChangeKind.UNKNOWN -> R.string.session_artifact_change_unknown
    }

@get:StringRes
private val SessionArtifactAvailabilityStatus.pathFallbackResource: Int?
    get() = when (this) {
        SessionArtifactAvailabilityStatus.DELETED -> R.string.session_artifact_path_deleted
        SessionArtifactAvailabilityStatus.OUTSIDE_WORKSPACE ->
            R.string.session_artifact_path_outside_workspace
        SessionArtifactAvailabilityStatus.WORKSPACE_UNKNOWN ->
            R.string.session_artifact_path_workspace_unknown
        SessionArtifactAvailabilityStatus.RECONNECT,
        SessionArtifactAvailabilityStatus.UNSUPPORTED,
        SessionArtifactAvailabilityStatus.READY,
        -> null
    }

@get:StringRes
private val SessionArtifactAvailabilityStatus.labelResource: Int
    get() = when (this) {
        SessionArtifactAvailabilityStatus.RECONNECT -> R.string.session_artifact_availability_reconnect
        SessionArtifactAvailabilityStatus.UNSUPPORTED -> R.string.session_artifact_availability_unsupported
        SessionArtifactAvailabilityStatus.READY -> R.string.session_artifact_availability_ready
        SessionArtifactAvailabilityStatus.DELETED -> R.string.session_artifact_availability_deleted
        SessionArtifactAvailabilityStatus.OUTSIDE_WORKSPACE ->
            R.string.session_artifact_availability_outside_workspace
        SessionArtifactAvailabilityStatus.WORKSPACE_UNKNOWN ->
            R.string.session_artifact_availability_workspace_unknown
    }

@Composable
private fun ArtifactCard(
    artifact: SessionArtifactUiModel,
    onSaveArtifact: (String, String, String) -> Unit,
    onCancelArtifact: (String) -> Unit,
) {
    val numberFormat = NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0])
    val displayPath = artifact.displayPath ?: stringResource(
        checkNotNull(artifact.availabilityStatus.pathFallbackResource),
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SelectionContainer(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayPath,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(artifact.changeKind.labelResource),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = stringResource(artifact.availabilityStatus.labelResource),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (artifact.isExporting) {
                CircularProgressIndicator()
                Text(
                    text = artifact.totalBytes?.let { totalBytes ->
                        stringResource(
                            R.string.session_artifact_save_progress_total,
                            numberFormat.format(artifact.bytesWritten),
                            numberFormat.format(totalBytes),
                        )
                    } ?: stringResource(
                        R.string.session_artifact_save_progress,
                        numberFormat.format(artifact.bytesWritten),
                    ),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.labelMedium,
                )
                OutlinedButton(onClick = { onCancelArtifact(artifact.stableKey) }) {
                    Text(stringResource(R.string.session_artifact_cancel_saving))
                }
            } else {
                if (artifact.isExportComplete) {
                    Text(
                        text = stringResource(R.string.session_artifact_copy_saved_verified),
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (artifact.isDownloadable) {
                    Button(
                        onClick = {
                            onSaveArtifact(
                                artifact.sessionKey,
                                artifact.stableKey,
                                artifact.suggestedFileName,
                            )
                        },
                        enabled = artifact.canSave,
                    ) {
                        Text(
                            if (artifact.isExportComplete) {
                                stringResource(R.string.session_artifact_save_another_copy)
                            } else {
                                stringResource(R.string.session_artifact_save_copy)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionComposer(
    detail: SessionDetailUiModel,
    speechInput: SpeechInputUiState,
    speechActions: SpeechInputUiActions,
    onDraftChanged: (String, String, Int, Int) -> Unit,
    onSubmitDraft: (String) -> Unit,
    onResumeSession: (String) -> Unit,
    onInterruptSession: (String) -> Unit,
) {
    val composer = detail.composer
    val sessionKey = detail.session.stableKey
    val focusManager = LocalFocusManager.current
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
        DetailHeading(stringResource(R.string.session_composer_message))
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
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    if (event.key != Key.Tab || event.type != KeyEventType.KeyUp) {
                        return@onPreviewKeyEvent false
                    }
                    focusManager.moveFocus(
                        if (event.isShiftPressed) {
                            FocusDirection.Previous
                        } else {
                            FocusDirection.Next
                        },
                    )
                    true
                }
                .testTag("session-composer-input"),
            label = { Text(stringResource(R.string.session_composer_message_to, detail.session.agentProviderLabel)) },
            enabled = !composer.isBusy,
            supportingText = {
                Text(
                    text = composer.statusMessage?.resolve()
                        ?: stringResource(R.string.session_composer_draft_saved),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            },
            minLines = 3,
            maxLines = 8,
        )
        SpeechInputControls(
            state = speechInput,
            sessionKey = sessionKey,
            actions = speechActions,
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
                    Text(stringResource(R.string.session_composer_resume))
                }
            }
            if (composer.canInterrupt) {
                OutlinedButton(onClick = { onInterruptSession(sessionKey) }) {
                    Text(stringResource(R.string.session_composer_interrupt))
                }
            }
            Button(
                onClick = { onSubmitDraft(sessionKey) },
                enabled = composer.canSubmit,
            ) {
                Text(
                    if (composer.submitMode == SessionSubmitMode.STEER) {
                        stringResource(R.string.session_composer_steer)
                    } else {
                        stringResource(R.string.session_composer_send)
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
            text = session.title.resolve(),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(
                R.string.session_detail_context,
                session.connectionProviderName,
                session.connectionLabel,
                session.agentProviderLabel,
            ),
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
                    text = entry.roleLabel.resolve(),
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
                    text = stringResource(R.string.session_timeline_truncated),
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
                text = activity.summary.resolve(),
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

@Composable
private fun activityTypeLabel(type: SessionActivityType): String = when (type) {
    SessionActivityType.NEW_OUTPUT -> stringResource(R.string.session_activity_new_output)
    SessionActivityType.APPROVAL_REQUIRED -> stringResource(R.string.session_activity_approval_required)
    SessionActivityType.QUESTION -> stringResource(R.string.session_activity_question)
    SessionActivityType.FAILURE -> stringResource(R.string.session_activity_failure)
    SessionActivityType.RECONNECTED -> stringResource(R.string.session_activity_reconnected)
    SessionActivityType.TURN_COMPLETED -> stringResource(R.string.session_activity_turn_completed)
}

private fun formatTimestamp(epochMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMillis))
