/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.companion.api

/** Bounded extension output projected by the phone; it is never an authority. */
data class ExtensionProjection(
    val extensionId: String,
    val schemaVersion: Int,
    val revision: Long,
    val kind: ExtensionProjectionKind,
    val privacyClass: CompanionPrivacyClass,
    val title: String,
    val redactedBody: String,
    val phoneConfirmationRequired: Boolean,
    val expiresAtEpochMillis: Long,
    val revoked: Boolean = false,
) {
    init {
        require(extensionId.matches(EXTENSION_ID_PATTERN)) { "Extension id is invalid" }
        require(schemaVersion in 1..MAX_SCHEMA_VERSION) { "Extension schema is invalid" }
        require(revision > 0) { "Extension projection revision must be positive" }
        requireBounded(title, "Extension projection title", MAX_TITLE_CHARS)
        requireBounded(redactedBody, "Extension projection body", MAX_BODY_CHARS)
        require(expiresAtEpochMillis > 0) { "Extension projection expiry is invalid" }
        require(!redactedBody.contains("secret", true) && !redactedBody.contains("token", true)) {
            "Extension projection contains protected content"
        }
        require(kind.requiresPhoneConfirmation() == phoneConfirmationRequired) {
            "Projection confirmation policy does not match its kind"
        }
        require(privacyClass != CompanionPrivacyClass.PUBLIC_SUMMARY || !redactedBody.contains("private", true)) {
            "Public projection cannot contain private material"
        }
    }

    fun visibleAt(nowEpochMillis: Long): Boolean =
        !revoked && nowEpochMillis < expiresAtEpochMillis

    fun canExecuteOnWatch(nowEpochMillis: Long): Boolean =
        visibleAt(nowEpochMillis) && !phoneConfirmationRequired
}

enum class ExtensionProjectionKind {
    ALERT,
    WORKFLOW_PROPOSAL,
    SAFE_ACTION,
    SPEECH_INPUT,
    SPEECH_OUTPUT,
    RECOVERY,
    ;

    fun requiresPhoneConfirmation(): Boolean =
        this == WORKFLOW_PROPOSAL || this == RECOVERY
}

private val EXTENSION_ID_PATTERN = Regex("ext_v1_[a-z][a-z0-9_-]{2,63}")
private const val MAX_SCHEMA_VERSION = 1_000_000
private const val MAX_TITLE_CHARS = 120
private const val MAX_BODY_CHARS = 2_000

private fun requireBounded(value: String, field: String, max: Int) {
    require(value.isNotBlank() && value.length <= max) { "$field is invalid or too long" }
    require(!value.contains('\u0000')) { "$field contains a NUL" }
}
