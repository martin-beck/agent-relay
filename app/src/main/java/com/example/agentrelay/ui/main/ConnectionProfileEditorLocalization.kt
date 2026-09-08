/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.agentrelay.R
import dev.agentrelay.ssh.api.SshConnectionProfileSchema
import dev.agentrelay.ssh.api.SshConnectionProvider

@Composable
internal fun ConnectionProfileEditorUiState.Editing.localizedConnectionProfile(): ConnectionProfileEditorUiState.Editing {
    val catalog = CONNECTION_PROFILE_CATALOGS[providerId] ?: return this
    val localizedFields = mutableListOf<ConnectionProfileFieldUiModel>()
    for (field in fields) {
        val localizedOptions = mutableListOf<ConnectionProfileFieldOptionUiModel>()
        for (option in field.options) {
            localizedOptions += option.copy(
                label = localizedString(
                    catalog.optionLabel(field.id, option.value),
                    option.label,
                ),
                supportingText = localizedNullableString(
                    catalog.optionSupportingText(field.id, option.value),
                    option.supportingText,
                ),
            )
        }
        localizedFields += field.copy(
            label = localizedString(catalog.fieldLabel(field.id), field.label),
            value = if (field.value.isEmpty()) {
                localizedString(catalog.emptyFieldValue(field.id), field.value)
            } else {
                field.value
            },
            supportingText = localizedNullableString(
                catalog.fieldSupportingText(field.id),
                field.supportingText,
            ),
            options = localizedOptions,
        )
    }

    val localizedOperations = mutableListOf<ConnectionProfileOperationUiModel>()
    for (operation in operations) {
        localizedOperations += operation.copy(
            label = localizedString(catalog.operationLabel(operation.id), operation.label),
            supportingText = localizedString(
                catalog.operationSupportingText(operation.id),
                operation.supportingText,
            ),
            confirmationTitle = localizedNullableString(
                catalog.operationConfirmationTitle(operation.id),
                operation.confirmationTitle,
            ),
            confirmationMessage = localizedNullableString(
                catalog.operationConfirmationMessage(operation.id),
                operation.confirmationMessage,
            ),
        )
    }

    return copy(
        title = localizedString(catalog.title(profileId == null), title),
        fields = localizedFields,
        operations = localizedOperations,
    )
}

@Composable
private fun localizedString(@StringRes resource: Int?, fallback: String): String =
    resource?.let { stringResource(it) } ?: fallback

@Composable
private fun localizedNullableString(@StringRes resource: Int?, fallback: String?): String? =
    resource?.let { stringResource(it) } ?: fallback

private interface ConnectionProfileStringCatalog {
    @StringRes
    fun title(isNew: Boolean): Int?

    @StringRes
    fun fieldLabel(fieldId: String): Int?

    @StringRes
    fun fieldSupportingText(fieldId: String): Int?

    @StringRes
    fun emptyFieldValue(fieldId: String): Int?

    @StringRes
    fun optionLabel(fieldId: String, value: String): Int?

    @StringRes
    fun optionSupportingText(fieldId: String, value: String): Int?

    @StringRes
    fun operationLabel(operationId: String): Int?

    @StringRes
    fun operationSupportingText(operationId: String): Int?

    @StringRes
    fun operationConfirmationTitle(operationId: String): Int?

    @StringRes
    fun operationConfirmationMessage(operationId: String): Int?
}

private object SshConnectionProfileStringCatalog : ConnectionProfileStringCatalog {
    override fun title(isNew: Boolean) =
        if (isNew) R.string.ssh_profile_title_add else R.string.ssh_profile_title_edit

    override fun fieldLabel(fieldId: String) = when (fieldId) {
        SshConnectionProfileSchema.PROFILE_LABEL.value -> R.string.ssh_profile_field_name
        SshConnectionProfileSchema.HOST.value -> R.string.ssh_profile_field_host
        SshConnectionProfileSchema.PORT.value -> R.string.ssh_profile_field_port
        SshConnectionProfileSchema.USERNAME.value -> R.string.ssh_profile_field_username
        SshConnectionProfileSchema.JUMP_HOST.value -> R.string.ssh_profile_field_jump_host
        SshConnectionProfileSchema.AUTHENTICATION.value -> R.string.ssh_profile_field_authentication
        SshConnectionProfileSchema.PASSWORD.value -> R.string.ssh_profile_field_password
        SshConnectionProfileSchema.PRIVATE_KEY.value -> R.string.ssh_profile_field_private_key
        SshConnectionProfileSchema.PASSPHRASE_MODE.value -> R.string.ssh_profile_field_passphrase_mode
        SshConnectionProfileSchema.PASSPHRASE.value -> R.string.ssh_profile_field_new_passphrase
        SshConnectionProfileSchema.PUBLIC_KEY.value -> R.string.ssh_profile_field_public_key
        else -> null
    }

    override fun fieldSupportingText(fieldId: String) = when (fieldId) {
        SshConnectionProfileSchema.PROFILE_LABEL.value -> R.string.ssh_profile_support_name
        SshConnectionProfileSchema.JUMP_HOST.value -> R.string.ssh_profile_support_jump_host
        SshConnectionProfileSchema.PASSWORD.value -> R.string.ssh_profile_support_password
        SshConnectionProfileSchema.PRIVATE_KEY.value -> R.string.ssh_profile_support_private_key
        SshConnectionProfileSchema.PASSPHRASE.value -> R.string.ssh_profile_support_passphrase
        SshConnectionProfileSchema.PUBLIC_KEY.value -> R.string.ssh_profile_support_public_key
        else -> null
    }

    override fun emptyFieldValue(fieldId: String) =
        if (fieldId == SshConnectionProfileSchema.PUBLIC_KEY.value) {
            R.string.ssh_profile_public_key_pending
        } else {
            null
        }

    override fun optionLabel(fieldId: String, value: String) = when (fieldId to value) {
        SshConnectionProfileSchema.JUMP_HOST.value to
            SshConnectionProfileSchema.DIRECT_CONNECTION,
        -> R.string.ssh_profile_option_direct
        SshConnectionProfileSchema.PASSPHRASE_MODE.value to
            SshConnectionProfileSchema.PASSPHRASE_KEEP,
        -> R.string.ssh_profile_passphrase_keep
        SshConnectionProfileSchema.PASSPHRASE_MODE.value to
            SshConnectionProfileSchema.PASSPHRASE_NONE,
        -> R.string.ssh_profile_passphrase_none
        SshConnectionProfileSchema.PASSPHRASE_MODE.value to
            SshConnectionProfileSchema.PASSPHRASE_REPLACE,
        -> R.string.ssh_profile_passphrase_replace
        SshConnectionProfileSchema.AUTHENTICATION.value to
            SshConnectionProfileSchema.AUTHENTICATION_PASSWORD,
        -> R.string.ssh_profile_auth_password
        SshConnectionProfileSchema.AUTHENTICATION.value to
            SshConnectionProfileSchema.AUTHENTICATION_IMPORTED_KEY,
        ->
            R.string.ssh_profile_auth_imported_key
        SshConnectionProfileSchema.AUTHENTICATION.value to
            SshConnectionProfileSchema.AUTHENTICATION_AGENT_BACKED,
        ->
            R.string.ssh_profile_auth_keystore_key
        else -> null
    }

    override fun optionSupportingText(fieldId: String, value: String) =
        when (fieldId to value) {
            SshConnectionProfileSchema.AUTHENTICATION.value to
                SshConnectionProfileSchema.AUTHENTICATION_PASSWORD,
            ->
                R.string.ssh_profile_auth_password_support
            SshConnectionProfileSchema.AUTHENTICATION.value to
                SshConnectionProfileSchema.AUTHENTICATION_IMPORTED_KEY,
            ->
                R.string.ssh_profile_auth_imported_key_support
            SshConnectionProfileSchema.AUTHENTICATION.value to
                SshConnectionProfileSchema.AUTHENTICATION_AGENT_BACKED,
            ->
                R.string.ssh_profile_auth_keystore_key_support
            else -> null
        }

    override fun operationLabel(operationId: String) = when (operationId) {
        SshConnectionProfileSchema.INSTALL_PUBLIC_KEY.value -> R.string.ssh_profile_operation_install_key
        SshConnectionProfileSchema.VERIFY_KEY_LOGIN.value -> R.string.ssh_profile_operation_verify_key
        else -> null
    }

    override fun operationSupportingText(operationId: String) = when (operationId) {
        SshConnectionProfileSchema.INSTALL_PUBLIC_KEY.value ->
            R.string.ssh_profile_operation_install_key_support
        SshConnectionProfileSchema.VERIFY_KEY_LOGIN.value ->
            R.string.ssh_profile_operation_verify_key_support
        else -> null
    }

    override fun operationConfirmationTitle(operationId: String) =
        if (operationId == SshConnectionProfileSchema.INSTALL_PUBLIC_KEY.value) {
            R.string.ssh_profile_operation_install_key_title
        } else {
            null
        }

    override fun operationConfirmationMessage(operationId: String) =
        if (operationId == SshConnectionProfileSchema.INSTALL_PUBLIC_KEY.value) {
            R.string.ssh_profile_operation_install_key_message
        } else {
            null
        }
}

private val CONNECTION_PROFILE_CATALOGS = mapOf(
    SshConnectionProvider.ID.value to SshConnectionProfileStringCatalog,
)
