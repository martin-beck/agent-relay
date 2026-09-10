/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.api

enum class PrivacySensitivity { PUBLIC, INTERNAL, SENSITIVE, SECRET }

data class PrivacyRecord(
    val id: String,
    val sensitivity: PrivacySensitivity,
    val purpose: String,
    val retentionUntilMillis: Long,
    val contentDigest: String,
    val credentialVersion: Long = 0,
) {
    init {
        require(id.matches(ID_PATTERN)) { "Privacy record id is invalid" }
        require(purpose.matches(ID_PATTERN)) { "Privacy purpose is invalid" }
        require(retentionUntilMillis > 0) { "Retention deadline is invalid" }
        require(contentDigest.matches(DIGEST_PATTERN)) { "Content digest is invalid" }
        require(credentialVersion >= 0) { "Credential version is invalid" }
    }
}

enum class PrivacyProjection { FULL, REDACTED, DENIED, DELETED }

data class PrivacyExportDecision(val recordId: String, val projection: PrivacyProjection)

data class PrivacyAuditEntry(
    val recordId: String,
    val action: String,
    val credentialVersion: Long,
)

/** Fail-closed retention, deletion, redaction, and credential-rotation ledger. */
class PrivacyLifecycleLedger {
    private val records = linkedMapOf<String, PrivacyRecord>()
    private val deleted = linkedSetOf<String>()
    private val revokedCredentials = linkedMapOf<String, Long>()
    private val audit = mutableListOf<PrivacyAuditEntry>()

    @Synchronized
    fun put(record: PrivacyRecord) {
        require(record.id !in deleted) { "Deleted records cannot be resurrected" }
        val current = records[record.id]
        require(current == null || record.credentialVersion >= current.credentialVersion) {
            "Credential version regressed"
        }
        records[record.id] = record
        audit += PrivacyAuditEntry(record.id, "put", record.credentialVersion)
    }

    @Synchronized
    fun expire(nowMillis: Long): Int {
        val expired = records.values.filter { it.retentionUntilMillis <= nowMillis }.map { it.id }
        expired.forEach { deleteInternal(it, "expire") }
        return expired.size
    }

    @Synchronized
    fun delete(recordId: String): Boolean {
        if (recordId in deleted) return false
        deleteInternal(recordId, "delete")
        return true
    }

    @Synchronized
    fun export(recordId: String, redacted: Boolean): PrivacyExportDecision {
        if (recordId in deleted) return PrivacyExportDecision(recordId, PrivacyProjection.DELETED)
        val record = records[recordId] ?: return PrivacyExportDecision(recordId, PrivacyProjection.DENIED)
        val projection = when {
            record.sensitivity == PrivacySensitivity.SECRET -> PrivacyProjection.DENIED
            redacted || record.sensitivity == PrivacySensitivity.SENSITIVE -> PrivacyProjection.REDACTED
            else -> PrivacyProjection.FULL
        }
        audit += PrivacyAuditEntry(recordId, "export_${projection.name.lowercase()}", record.credentialVersion)
        return PrivacyExportDecision(recordId, projection)
    }

    @Synchronized
    fun rotateCredential(recordId: String, nextVersion: Long): Boolean {
        val record = records[recordId] ?: return false
        require(nextVersion > record.credentialVersion) { "Credential version must advance" }
        revokedCredentials[recordId] = record.credentialVersion
        records[recordId] = record.copy(credentialVersion = nextVersion)
        audit += PrivacyAuditEntry(recordId, "rotate", nextVersion)
        return true
    }

    @Synchronized
    fun isCredentialRevoked(recordId: String, version: Long): Boolean =
        version <= (revokedCredentials[recordId] ?: Long.MIN_VALUE)

    @Synchronized
    fun auditTrail(): List<PrivacyAuditEntry> = audit.toList()

    private fun deleteInternal(recordId: String, action: String) {
        records.remove(recordId)
        deleted += recordId
        audit += PrivacyAuditEntry(recordId, action, 0)
    }
}

private val ID_PATTERN = Regex("[a-z][a-z0-9-]{2,63}")
private val DIGEST_PATTERN = Regex("[A-Za-z0-9_-]{43}")
