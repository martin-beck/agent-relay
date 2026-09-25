/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.agentrelay.R

@Composable
internal fun SpeechInputControls(
    state: SpeechInputUiState,
    sessionKey: String,
    actions: SpeechInputUiActions,
    modifier: Modifier = Modifier,
) {
    if (state.phase == SpeechInputPhase.UNAVAILABLE ||
        state.targetSessionKey != null && state.targetSessionKey != sessionKey
    ) {
        return
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.speech_input_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
        if (state.canSelectModel) {
            SpeechModelSelector(state, actions.selectModel)
        } else {
            state.selectedModelName?.let { modelName ->
                Text(
                    text = stringResource(R.string.speech_selected_model, modelName),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Text(
            text = state.statusMessage.resolve(),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SpeechInputPhaseControls(state, sessionKey, actions)
    }
}

@Composable
private fun SpeechInputPhaseControls(
    state: SpeechInputUiState,
    sessionKey: String,
    actions: SpeechInputUiActions,
) {
    when (state.phase) {
        SpeechInputPhase.MODEL_REQUIRED -> Button(
            onClick = actions.installModel,
            enabled = state.canInstall,
        ) {
            Text(stringResource(R.string.speech_install_model))
        }

        SpeechInputPhase.INSTALLING -> {
            val progress = state.progressPercent
            if (progress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.speech_download_progress, progress),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            OutlinedButton(
                onClick = actions.cancelModelInstall,
                enabled = state.canCancelInstall,
            ) {
                Text(stringResource(R.string.speech_cancel_model_download))
            }
        }

        SpeechInputPhase.READY -> Button(
            onClick = { actions.requestStart(sessionKey) },
            enabled = state.canStart,
        ) {
            Text(stringResource(R.string.speech_start_input))
        }

        SpeechInputPhase.STARTING,
        SpeechInputPhase.TRANSCRIBING,
        -> {
            CircularProgressIndicator()
            OutlinedButton(
                onClick = { actions.cancel(sessionKey) },
                enabled = state.canCancel,
            ) {
                Text(stringResource(R.string.speech_cancel_input))
            }
        }

        SpeechInputPhase.LISTENING -> FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { actions.stop(sessionKey) },
                enabled = state.canStop,
            ) {
                Text(stringResource(R.string.speech_stop_recording))
            }
            OutlinedButton(
                onClick = { actions.cancel(sessionKey) },
                enabled = state.canCancel,
            ) {
                Text(stringResource(R.string.speech_cancel_input))
            }
        }

        SpeechInputPhase.RESULT -> {
            SelectionContainer {
                Text(
                    text = state.transcript.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { actions.useTranscript(sessionKey) },
                    enabled = state.canUseResult,
                ) {
                    Text(stringResource(R.string.speech_use_transcript))
                }
                OutlinedButton(
                    onClick = { actions.dismiss(sessionKey) },
                    enabled = state.canDismiss,
                ) {
                    Text(stringResource(R.string.speech_discard_transcript))
                }
            }
        }

        SpeechInputPhase.FAILED -> {
            if (state.canInstall) {
                Button(onClick = actions.installModel) {
                    Text(stringResource(R.string.speech_retry_model_installation))
                }
            }
            if (state.canDismiss) {
                OutlinedButton(onClick = { actions.dismiss(sessionKey) }) {
                    Text(stringResource(R.string.speech_dismiss_error))
                }
            }
        }

        SpeechInputPhase.UNAVAILABLE -> Unit
    }
}

@Composable
private fun SpeechModelSelector(
    state: SpeechInputUiState,
    onSelectModel: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.speech_model_label),
            style = MaterialTheme.typography.labelLarge,
        )
        state.models.forEach { model ->
            val selected = model.id == state.selectedModelId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = selected,
                        onClick = { onSelectModel(model.id) },
                        role = Role.RadioButton,
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = selected,
                    onClick = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clearAndSetSemantics {},
                )
                Text(
                    text = if (model.isReady) {
                        stringResource(R.string.speech_model_installed, model.name)
                    } else {
                        model.name
                    },
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}
