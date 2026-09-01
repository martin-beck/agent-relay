package com.example.agentrelay.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.agentrelay.theme.AgentRelayTheme
import dev.agentrelay.connection.api.ConnectionProfileFieldType

@Composable
internal fun ConnectionProfileEditorDialog(
    state: ConnectionProfileEditorUiState,
    actions: SessionHubActions,
) {
    when (state) {
        ConnectionProfileEditorUiState.Loading -> AlertDialog(
            onDismissRequest = actions.dismissProfileEditor,
            title = { Text("Loading connection profile") },
            text = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = actions.dismissProfileEditor) {
                    Text("Cancel")
                }
            },
        )

        is ConnectionProfileEditorUiState.Editing -> {
            if (state.confirmDelete) {
                DeleteProfileConfirmation(state, actions)
            } else {
                ProfileEditor(state, actions)
            }
        }
    }
}

@Composable
private fun ProfileEditor(
    editor: ConnectionProfileEditorUiState.Editing,
    actions: SessionHubActions,
) {
    AlertDialog(
        onDismissRequest = {
            if (!editor.isBusy) actions.dismissProfileEditor()
        },
        title = { Text(editor.title) },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                editor.notice?.let { notice ->
                    item(key = "profile-notice") {
                        Text(
                            text = notice,
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                editor.error?.let { error ->
                    item(key = "profile-error") {
                        Text(
                            text = error,
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Assertive
                            },
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                items(editor.visibleFields(), key = ConnectionProfileFieldUiModel::id) { field ->
                    ProfileField(
                        field = field,
                        error = editor.fieldErrors[field.id],
                        enabled = !editor.isBusy,
                        onValueChange = { actions.updateProfileField(field.id, it) },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = actions.saveProfile,
                enabled = !editor.isBusy,
            ) {
                if (editor.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(
                    text = "Save",
                    modifier = if (editor.isBusy) Modifier.padding(start = 8.dp) else Modifier,
                )
            }
        },
        dismissButton = {
            Row {
                if (editor.canDelete) {
                    TextButton(
                        onClick = actions.requestProfileDeletion,
                        enabled = !editor.isBusy,
                    ) {
                        Text("Delete")
                    }
                }
                TextButton(
                    onClick = actions.dismissProfileEditor,
                    enabled = !editor.isBusy,
                ) {
                    Text("Close")
                }
            }
        },
    )
}

@Composable
private fun ProfileField(
    field: ConnectionProfileFieldUiModel,
    error: String?,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    when (field.type) {
        ConnectionProfileFieldType.SINGLE_CHOICE -> ChoiceField(
            field = field,
            error = error,
            enabled = enabled,
            onValueChange = onValueChange,
        )

        ConnectionProfileFieldType.READ_ONLY -> Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(field.label, style = MaterialTheme.typography.labelLarge)
            SelectionContainer {
                Text(field.value, style = MaterialTheme.typography.bodyMedium)
            }
            field.supportingText?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }

        else -> {
            val support = error
                ?: if (field.hasStoredSecret && field.value.isEmpty()) {
                    "A secret is stored. Leave this blank to keep it."
                } else {
                    field.supportingText
                }
            OutlinedTextField(
                value = field.value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                label = {
                    Text(if (field.required) "${field.label} (required)" else field.label)
                },
                supportingText = support?.let { message -> { Text(message) } },
                isError = error != null,
                singleLine = field.type != ConnectionProfileFieldType.MULTILINE_SECRET,
                minLines = if (field.type == ConnectionProfileFieldType.MULTILINE_SECRET) 5 else 1,
                visualTransformation = if (field.isSecret) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (field.type) {
                        ConnectionProfileFieldType.PORT -> KeyboardType.Number
                        ConnectionProfileFieldType.PASSWORD,
                        ConnectionProfileFieldType.MULTILINE_SECRET,
                        -> KeyboardType.Password
                        else -> KeyboardType.Text
                    },
                ),
            )
        }
    }
}

@Composable
private fun ChoiceField(
    field: ConnectionProfileFieldUiModel,
    error: String?,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = if (field.required) "${field.label} (required)" else field.label,
            color = if (error == null) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.error
            },
            style = MaterialTheme.typography.labelLarge,
        )
        field.options.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = field.value == option.value,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onValueChange(option.value) },
                    )
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = field.value == option.value,
                    onClick = null,
                    enabled = enabled,
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                    option.supportingText?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        (error ?: field.supportingText)?.let { supporting ->
            Text(
                text = supporting,
                color = if (error == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DeleteProfileConfirmation(
    editor: ConnectionProfileEditorUiState.Editing,
    actions: SessionHubActions,
) {
    AlertDialog(
        onDismissRequest = actions.cancelProfileDeletion,
        title = { Text("Delete connection profile?") },
        text = {
            Text(
                "This removes the profile, its stored credentials, and any saved " +
                    "host identity that is not shared by another profile.",
            )
        },
        confirmButton = {
            Button(
                onClick = actions.deleteProfile,
                enabled = !editor.isBusy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(
                onClick = actions.cancelProfileDeletion,
                enabled = !editor.isBusy,
            ) {
                Text("Cancel")
            }
        },
    )
}

@Preview(
    name = "Connection profile - large text",
    showBackground = true,
    widthDp = 360,
    heightDp = 780,
    fontScale = 1.5f,
)
@Composable
private fun ConnectionProfileEditorLargeTextPreview() {
    AgentRelayTheme {
        ConnectionProfileEditorDialog(
            state = previewConnectionProfileEditor(),
            actions = previewProfileActions(),
        )
    }
}

@Preview(
    name = "Connection profile - delete confirmation",
    showBackground = true,
    widthDp = 360,
    heightDp = 640,
)
@Composable
private fun ConnectionProfileDeletePreview() {
    AgentRelayTheme {
        ConnectionProfileEditorDialog(
            state = previewConnectionProfileEditor().copy(confirmDelete = true),
            actions = previewProfileActions(),
        )
    }
}

private fun previewConnectionProfileEditor() = ConnectionProfileEditorUiState.Editing(
    providerId = "ssh.secure-shell",
    profileId = "preview-profile",
    title = "Edit Secure Shell profile",
    fields = listOf(
        ConnectionProfileFieldUiModel(
            id = "profile-label",
            label = "Profile name",
            type = ConnectionProfileFieldType.TEXT,
            value = "Development server",
            supportingText = "Shown in the connection list.",
            required = true,
            maxLength = 128,
            options = emptyList(),
            visibleWhen = emptyList(),
            hasStoredSecret = false,
        ),
        ConnectionProfileFieldUiModel(
            id = "host",
            label = "Host",
            type = ConnectionProfileFieldType.TEXT,
            value = "example.test",
            supportingText = null,
            required = true,
            maxLength = 255,
            options = emptyList(),
            visibleWhen = emptyList(),
            hasStoredSecret = false,
        ),
        ConnectionProfileFieldUiModel(
            id = "password",
            label = "Password",
            type = ConnectionProfileFieldType.PASSWORD,
            value = "",
            supportingText = null,
            required = false,
            maxLength = 16_384,
            options = emptyList(),
            visibleWhen = emptyList(),
            hasStoredSecret = true,
        ),
    ),
    canDelete = true,
    notice = "Profile saved securely.",
)

private fun previewProfileActions() = SessionHubActions(
    retry = {},
    refresh = {},
    connect = {},
    disconnect = {},
    trustIdentity = { _, _ -> },
    rejectIdentity = {},
    selectSession = {},
    openSession = {},
    dismissError = {},
)
