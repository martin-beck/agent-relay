package com.example.agentrelay.ui.main

import dev.agentrelay.connection.api.ConnectionProfileEditor
import dev.agentrelay.connection.api.ConnectionProfileField
import dev.agentrelay.connection.api.ConnectionProfileFieldType
import dev.agentrelay.connection.api.ConnectionProfileOperation

internal sealed interface ConnectionProfileEditorUiState {
    data object Loading : ConnectionProfileEditorUiState

    data class Editing(
        val providerId: String,
        val profileId: String?,
        val title: String,
        val fields: List<ConnectionProfileFieldUiModel>,
        val operations: List<ConnectionProfileOperationUiModel> = emptyList(),
        val canDelete: Boolean,
        val isBusy: Boolean = false,
        val hasUnsavedChanges: Boolean = false,
        val activeOperationId: String? = null,
        val fieldErrors: Map<String, String> = emptyMap(),
        val error: UiMessage? = null,
        val notice: UiMessage? = null,
        val confirmDelete: Boolean = false,
        val confirmOperationId: String? = null,
    ) : ConnectionProfileEditorUiState {
        fun updateField(fieldId: String, value: String): Editing = copy(
            fields = fields.map { field ->
                if (field.id == fieldId) field.copy(value = value.take(field.maxLength)) else field
            },
            fieldErrors = fieldErrors - fieldId,
            error = null,
            notice = null,
            hasUnsavedChanges = true,
        )

        fun visibleFields(): List<ConnectionProfileFieldUiModel> = fields.filter { field ->
            field.visibleWhen.all { condition ->
                fields.firstOrNull { it.id == condition.fieldId }?.value == condition.expectedValue
            }
        }

        fun confirmedOperation(): ConnectionProfileOperationUiModel? =
            operations.firstOrNull { it.id == confirmOperationId }
    }
}

internal data class ConnectionProfileOperationUiModel(
    val id: String,
    val label: String,
    val supportingText: String,
    val confirmationTitle: String?,
    val confirmationMessage: String?,
) {
    val requiresConfirmation: Boolean
        get() = confirmationTitle != null
}

internal data class ConnectionProfileFieldConditionUiModel(
    val fieldId: String,
    val expectedValue: String,
)

internal data class ConnectionProfileFieldOptionUiModel(
    val value: String,
    val label: String,
    val supportingText: String?,
)

internal data class ConnectionProfileFieldUiModel(
    val id: String,
    val label: String,
    val type: ConnectionProfileFieldType,
    val value: String,
    val supportingText: String?,
    val required: Boolean,
    val maxLength: Int,
    val options: List<ConnectionProfileFieldOptionUiModel>,
    val visibleWhen: List<ConnectionProfileFieldConditionUiModel>,
    val hasStoredSecret: Boolean,
) {
    val isSecret: Boolean
        get() = type == ConnectionProfileFieldType.PASSWORD ||
            type == ConnectionProfileFieldType.MULTILINE_SECRET

    override fun toString(): String =
        "ConnectionProfileFieldUiModel(id=$id, type=$type, value=" +
            if (isSecret) "[REDACTED])" else "$value)"
}

internal fun ConnectionProfileEditor.toUiState(
    notice: UiMessage? = null,
): ConnectionProfileEditorUiState.Editing = ConnectionProfileEditorUiState.Editing(
    providerId = providerId.value,
    profileId = profileId?.value,
    title = title,
    fields = fields.map(ConnectionProfileField::toUiModel),
    operations = operations.map(ConnectionProfileOperation::toUiModel),
    canDelete = canDelete,
    notice = notice,
)

private fun ConnectionProfileField.toUiModel() = ConnectionProfileFieldUiModel(
    id = id.value,
    label = label,
    type = type,
    value = value,
    supportingText = supportingText,
    required = required,
    maxLength = maxLength,
    options = options.map {
        ConnectionProfileFieldOptionUiModel(it.value, it.label, it.supportingText)
    },
    visibleWhen = visibleWhen.map {
        ConnectionProfileFieldConditionUiModel(it.fieldId.value, it.expectedValue)
    },
    hasStoredSecret = hasStoredSecret,
)

private fun ConnectionProfileOperation.toUiModel() = ConnectionProfileOperationUiModel(
    id = id.value,
    label = label,
    supportingText = supportingText,
    confirmationTitle = confirmationTitle,
    confirmationMessage = confirmationMessage,
)
