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
import androidx.compose.ui.unit.dp

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
        title = { Text("Start a new session") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = state.connectionProviderName + "  -  " +
                        state.connectionLabel + "  -  " + state.agentProviderLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Choose only provider-neutral launch settings. Leave a field empty to use " +
                        "the agent provider's default.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = state.workingDirectory,
                    onValueChange = onWorkingDirectoryChanged,
                    modifier = Modifier.fillMaxWidth().testTag("session-working-directory"),
                    enabled = !state.isBusy,
                    label = { Text("Working directory (optional)") },
                    supportingText = {
                        Text("The path is interpreted on the selected connection provider.")
                    },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.model,
                    onValueChange = onModelChanged,
                    modifier = Modifier.fillMaxWidth().testTag("session-model"),
                    enabled = !state.isBusy,
                    label = { Text("Model (optional)") },
                    supportingText = {
                        Text("Use an exact model identifier supported by this agent provider.")
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
                    Text("Start session")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !state.isBusy,
            ) {
                Text("Cancel")
            }
        },
    )
}
