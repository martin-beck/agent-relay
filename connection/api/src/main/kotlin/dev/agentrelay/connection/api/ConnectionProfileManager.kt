/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.connection.api

import java.util.Arrays

@JvmInline
value class ConnectionProfileFieldId(val value: String) {
    init {
        require(value.matches(Regex("[a-z][a-z0-9._-]{1,63}"))) {
            "Connection profile field id must be a stable lowercase identifier"
        }
    }

    override fun toString(): String = value
}

@JvmInline
value class ConnectionProfileOperationId(val value: String) {
    init {
        require(value.matches(Regex("[a-z][a-z0-9._-]{1,63}"))) {
            "Connection profile operation id must be a stable lowercase identifier"
        }
    }

    override fun toString(): String = value
}

enum class ConnectionProfileFieldType {
    TEXT,
    PORT,
    PASSWORD,
    MULTILINE_SECRET,
    SINGLE_CHOICE,
    READ_ONLY,
}

data class ConnectionProfileFieldOption(
    val value: String,
    val label: String,
    val supportingText: String? = null,
) {
    init {
        require(value.isNotBlank()) { "Connection profile option value must not be blank" }
        require(label.isNotBlank()) { "Connection profile option label must not be blank" }
    }
}

data class ConnectionProfileFieldCondition(
    val fieldId: ConnectionProfileFieldId,
    val expectedValue: String,
) {
    init {
        require(expectedValue.isNotBlank()) { "Connection profile field condition must have a value" }
    }
}

data class ConnectionProfileField(
    val id: ConnectionProfileFieldId,
    val label: String,
    val type: ConnectionProfileFieldType,
    val value: String = "",
    val supportingText: String? = null,
    val required: Boolean = false,
    val maxLength: Int = 256,
    val options: List<ConnectionProfileFieldOption> = emptyList(),
    val visibleWhen: List<ConnectionProfileFieldCondition> = emptyList(),
    val hasStoredSecret: Boolean = false,
) {
    init {
        require(label.isNotBlank()) { "Connection profile field label must not be blank" }
        require(maxLength in 1..MAX_PROFILE_FIELD_LENGTH) {
            "Connection profile field maximum length is invalid"
        }
        require(value.length <= maxLength) { "Connection profile field value exceeds its bound" }
        require(
            (type == ConnectionProfileFieldType.SINGLE_CHOICE) == options.isNotEmpty(),
        ) { "Only choice fields may define options, and choice fields require options" }
        require(type != ConnectionProfileFieldType.READ_ONLY || !required) {
            "Read-only connection profile fields cannot be required"
        }
        require(
            !hasStoredSecret ||
                type == ConnectionProfileFieldType.PASSWORD ||
                type == ConnectionProfileFieldType.MULTILINE_SECRET,
        ) { "Only secret connection profile fields can have a stored value" }
    }

    companion object {
        private const val MAX_PROFILE_FIELD_LENGTH = 4 * 1024 * 1024
    }
}

data class ConnectionProfileOperation(
    val id: ConnectionProfileOperationId,
    val label: String,
    val supportingText: String,
    val confirmationTitle: String? = null,
    val confirmationMessage: String? = null,
) {
    val requiresConfirmation: Boolean
        get() = confirmationTitle != null

    init {
        requireProfileUiText(label, MAX_OPERATION_LABEL_CHARS, "operation label")
        requireProfileUiText(
            supportingText,
            MAX_OPERATION_SUPPORTING_TEXT_CHARS,
            "operation supporting text",
        )
        require((confirmationTitle == null) == (confirmationMessage == null)) {
            "Connection profile operation confirmation must define both title and message"
        }
        confirmationTitle?.let {
            requireProfileUiText(it, MAX_OPERATION_LABEL_CHARS, "operation confirmation title")
        }
        confirmationMessage?.let {
            requireProfileUiText(it, MAX_OPERATION_MESSAGE_CHARS, "operation confirmation message")
        }
    }
}

data class ConnectionProfileEditor(
    val providerId: ConnectionProviderId,
    val providerName: String,
    val profileId: ConnectionProfileId?,
    val title: String,
    val fields: List<ConnectionProfileField>,
    val canDelete: Boolean,
    val operations: List<ConnectionProfileOperation> = emptyList(),
) {
    init {
        require(providerName.isNotBlank()) { "Connection provider name must not be blank" }
        require(title.isNotBlank()) { "Connection profile editor title must not be blank" }
        require(fields.isNotEmpty()) { "Connection profile editor must contain fields" }
        require(fields.distinctBy(ConnectionProfileField::id).size == fields.size) {
            "Connection profile editor field ids must be unique"
        }
        require(!canDelete || profileId != null) { "Only an existing profile can be deleted" }
        require(profileId != null || operations.isEmpty()) {
            "Only a saved connection profile can expose operations"
        }
        require(operations.distinctBy(ConnectionProfileOperation::id).size == operations.size) {
            "Connection profile operation ids must be unique"
        }
        val fieldIds = fields.map(ConnectionProfileField::id).toSet()
        require(
            fields.flatMap(ConnectionProfileField::visibleWhen).all { it.fieldId in fieldIds },
        ) {
            "Connection profile field condition references an unknown field"
        }
    }
}

sealed interface ConnectionProfileFieldInput : AutoCloseable {
    class Text(val value: String) : ConnectionProfileFieldInput {
        override fun close() = Unit
    }

    class Secret private constructor(private var value: CharArray?) : ConnectionProfileFieldInput {
        val isEmpty: Boolean
            get() = requireOpen().isEmpty()
        val length: Int
            get() = requireOpen().size

        fun <T> useChars(block: (CharArray) -> T): T {
            val temporary = requireOpen().copyOf()
            return try {
                block(temporary)
            } finally {
                Arrays.fill(temporary, '\u0000')
            }
        }

        override fun close() {
            value?.let { Arrays.fill(it, '\u0000') }
            value = null
        }

        override fun toString(): String = "ConnectionProfileFieldInput.Secret([REDACTED])"

        private fun requireOpen(): CharArray =
            checkNotNull(value) { "Connection profile secret has been closed" }

        companion object {
            fun copyOf(value: CharArray): Secret = Secret(value.copyOf())
        }
    }
}

data class ConnectionProfileUpdate(
    val providerId: ConnectionProviderId,
    val profileId: ConnectionProfileId?,
    val fields: Map<ConnectionProfileFieldId, ConnectionProfileFieldInput>,
) : AutoCloseable {
    init {
        require(fields.isNotEmpty()) { "Connection profile update must contain fields" }
    }

    override fun close() {
        fields.values.forEach(ConnectionProfileFieldInput::close)
    }
}

data class ConnectionProfileSaveResult(
    val profile: ConnectionProfileSummary,
    val notice: String? = null,
)

data class ConnectionProfileOperationResult(val notice: String) {
    init {
        requireProfileUiText(notice, MAX_OPERATION_SUPPORTING_TEXT_CHARS, "operation notice")
    }
}

class ConnectionProfileOperationException(
    val actionableMessage: String,
    cause: Throwable? = null,
) : IllegalStateException(actionableMessage, cause) {
    init {
        requireProfileUiText(actionableMessage, MAX_OPERATION_MESSAGE_CHARS, "operation failure")
    }
}

class ConnectionProfileDeleteException(
    val actionableMessage: String,
    cause: Throwable? = null,
) : IllegalStateException(actionableMessage, cause) {
    init {
        requireProfileUiText(actionableMessage, MAX_OPERATION_MESSAGE_CHARS, "deletion failure")
    }
}

private fun requireProfileUiText(
    value: String,
    maximumLength: Int,
    field: String,
) {
    require(
        value.isNotBlank() &&
            value.length <= maximumLength &&
            value.none(Char::isISOControl),
    ) { "Connection profile $field must be bounded printable text" }
}

private const val MAX_OPERATION_LABEL_CHARS = 128
private const val MAX_OPERATION_SUPPORTING_TEXT_CHARS = 512
private const val MAX_OPERATION_MESSAGE_CHARS = 1_024

class ConnectionProfileValidationException(
    val fieldErrors: Map<ConnectionProfileFieldId, String>,
) : IllegalArgumentException("Connection profile input is invalid") {
    init {
        require(fieldErrors.isNotEmpty()) { "Connection profile validation errors must not be empty" }
        require(fieldErrors.values.all(String::isNotBlank)) {
            "Connection profile validation messages must not be blank"
        }
    }
}

interface ConnectionProfileManager {
    suspend fun editor(profileId: ConnectionProfileId? = null): ConnectionProfileEditor

    suspend fun save(update: ConnectionProfileUpdate): ConnectionProfileSaveResult

    suspend fun delete(profileId: ConnectionProfileId)

    suspend fun performOperation(
        profileId: ConnectionProfileId,
        operationId: ConnectionProfileOperationId,
    ): ConnectionProfileOperationResult {
        throw UnsupportedOperationException("This connection provider does not support profile operations")
    }
}
