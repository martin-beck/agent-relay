package com.example.agentrelay.ui.main

import dev.agentrelay.connection.api.ConnectionProfileEditor
import dev.agentrelay.connection.api.ConnectionProfileField
import dev.agentrelay.connection.api.ConnectionProfileFieldType

internal sealed interface ConnectionProfileEditorUiState {
    data object Loading : ConnectionProfileEditorUiState

    data class Editing(
        val providerId: String,
        val profileId: String?,
        val title: String,
        val fields: List<ConnectionProfileFieldUiModel>,
        val canDelete: Boolean,
        val isBusy: Boolean = false,
        val fieldErrors: Map<String, String> = emptyMap(),
        val error: String? = null,
        val notice: String? = null,
        val confirmDelete: Boolean = false,
    ) : ConnectionProfileEditorUiState {
        fun updateField(fieldId: String, value: String): Editing = copy(
            fields = fields.map { field ->
                if (field.id == fieldId) field.copy(value = value.take(field.maxLength)) else field
            },
            fieldErrors = fieldErrors - fieldId,
            error = null,
            notice = null,
        )

        fun visibleFields(): List<ConnectionProfileFieldUiModel> = fields.filter { field ->
            field.visibleWhen.all { condition ->
                fields.firstOrNull { it.id == condition.fieldId }?.value == condition.expectedValue
            }
        }
    }
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
    notice: String? = null,
): ConnectionProfileEditorUiState.Editing = ConnectionProfileEditorUiState.Editing(
    providerId = providerId.value,
    profileId = profileId?.value,
    title = title,
    fields = fields.map(ConnectionProfileField::toUiModel),
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
