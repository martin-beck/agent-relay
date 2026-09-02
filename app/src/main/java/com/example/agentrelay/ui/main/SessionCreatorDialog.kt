package com.example.agentrelay.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.agentrelay.R

@Composable
internal fun SessionCreatorDialog(
    state: SessionCreatorUiState,
    onWorkingDirectoryChanged: (String) -> Unit,
    onModelChanged: (String) -> Unit,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("session-creator-dialog"),
        onDismissRequest = {
            if (!state.isBusy) {
                onDismiss()
            }
        },
        title = { Text(stringResource(R.string.session_creator_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(
                        R.string.session_creator_context,
                        state.connectionProviderName,
                        state.connectionLabel,
                        state.agentProviderLabel,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.session_creator_guidance),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = state.workingDirectory,
                    onValueChange = onWorkingDirectoryChanged,
                    modifier = Modifier.fillMaxWidth().testTag("session-working-directory"),
                    enabled = !state.isBusy,
                    label = { Text(stringResource(R.string.session_creator_working_directory)) },
                    supportingText = {
                        Text(stringResource(R.string.session_creator_working_directory_support))
                    },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.model,
                    onValueChange = onModelChanged,
                    modifier = Modifier.fillMaxWidth().testTag("session-model"),
                    enabled = !state.isBusy,
                    label = { Text(stringResource(R.string.session_creator_model)) },
                    supportingText = {
                        Text(stringResource(R.string.session_creator_model_support))
                    },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onStart,
                enabled = !state.isBusy,
                modifier = Modifier.testTag("start-session"),
            ) {
                if (state.isBusy) {
                    CircularProgressIndicator()
                } else {
                    Text(stringResource(R.string.session_creator_start))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !state.isBusy,
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
