/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.api

enum class DiagnosticSeverity { TRACE, DEBUG, INFO, WARN, ERROR, FATAL }
enum class DiagnosticSensitivity { PUBLIC, INTERNAL, SENSITIVE, SECRET }
enum class DiagnosticOutcome { STARTED, SUCCEEDED, FAILED, CANCELLED, RETRIED, DROPPED }
enum class DiagnosticRetention { EPHEMERAL, SESSION, AUDIT }
enum class DiagnosticExport { NONE, REDACTED, FULL }

data class DiagnosticReferences(
    val projectId: String? = null,
    val workflowId: String? = null,
    val runId: String? = null,
    val stepId: String? = null,
    val attempt: Int? = null,
) {
    init {
        listOf(projectId, workflowId, runId, stepId).forEach { it?.let { value -> requireOpaque(value, "Reference") } }
        require(attempt == null || attempt in 0..MAX_ATTEMPTS) { "Attempt is invalid" }
    }
}

data class DiagnosticClock(val wallEpochMillis: Long, val monotonicNanos: Long) {
    init {
        require(wallEpochMillis > 0) { "Wall clock must be positive" }
        require(monotonicNanos >= 0) { "Monotonic clock must not be negative" }
    }
}

data class DiagnosticFailure(val category: String, val code: String, val retryable: Boolean) {
    init {
        requireStable(category, "Failure category")
        requireStable(code, "Failure code")
    }
}

data class DiagnosticEvent(
    val eventId: String,
    val schemaVersion: Int,
    val eventName: String,
    val clock: DiagnosticClock,
    val traceId: String,
    val spanId: String,
    val correlationId: String,
    val causationId: String? = null,
    val severity: DiagnosticSeverity,
    val sensitivity: DiagnosticSensitivity,
    val component: String,
    val operation: String,
    val outcome: DiagnosticOutcome,
    val references: DiagnosticReferences = DiagnosticReferences(),
    val stateFrom: String? = null,
    val stateTo: String? = null,
    val retry: Int = 0,
    val durationMillis: Long? = null,
    val failure: DiagnosticFailure? = null,
    val attributes: Map<String, String> = emptyMap(),
    val payloadDigest: String? = null,
    val truncated: Boolean = false,
    val droppedRecords: Int = 0,
    val retention: DiagnosticRetention = DiagnosticRetention.EPHEMERAL,
    val export: DiagnosticExport = DiagnosticExport.NONE,
) {
    init {
        requireOpaque(eventId, "Event id")
        require(schemaVersion in 1..MAX_SCHEMA_VERSION) { "Schema version is invalid" }
        requireStable(eventName, "Event name")
        listOf(traceId, spanId, correlationId, causationId).forEach { it?.let { value -> requireOpaque(value, "Causal id") } }
        requireStable(component, "Component")
        requireStable(operation, "Operation")
        stateFrom?.let { requireStable(it, "Previous state") }
        stateTo?.let { requireStable(it, "Next state") }
        require(retry >= 0) { "Retry must not be negative" }
        require(durationMillis == null || durationMillis >= 0) { "Duration must not be negative" }
        require(droppedRecords >= 0) { "Dropped records must not be negative" }
        require(attributes.size <= MAX_ATTRIBUTES) { "Diagnostic attributes are too large" }
        attributes.forEach { (key, value) ->
            requireStable(key, "Attribute key")
            require(value.length <= MAX_VALUE_CHARS) { "Diagnostic attribute is too large" }
            require(key !in FORBIDDEN_KEYS) { "Sensitive attribute is forbidden: $key" }
        }
        require(sensitivity != DiagnosticSensitivity.SECRET) { "Secret diagnostics must not be emitted" }
        require(export != DiagnosticExport.FULL || sensitivity == DiagnosticSensitivity.PUBLIC)
        require(outcome != DiagnosticOutcome.FAILED || failure != null) { "Failed diagnostics need a stable failure" }
        require(failure == null || outcome == DiagnosticOutcome.FAILED) { "Failure requires failed outcome" }
    }

    fun acceptsBy(compatibility: DiagnosticCompatibility): Boolean = compatibility.accepts(this)
}

data class DiagnosticCompatibility(val minimumSchemaVersion: Int, val maximumSchemaVersion: Int) {
    init {
        require(minimumSchemaVersion in 1..maximumSchemaVersion)
        require(maximumSchemaVersion <= MAX_SCHEMA_VERSION)
    }

    fun accepts(event: DiagnosticEvent): Boolean = event.schemaVersion in minimumSchemaVersion..maximumSchemaVersion
}

object DiagnosticRedactor {
    fun redact(event: DiagnosticEvent, allowedAttributes: Set<String>): DiagnosticEvent = event.copy(
        sensitivity = DiagnosticSensitivity.INTERNAL,
        export = DiagnosticExport.REDACTED,
        attributes = event.attributes.filterKeys { it in allowedAttributes },
    )
}

private fun requireOpaque(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_ID_CHARS && value.none { it.isWhitespace() }) { "$label is invalid" }
}

private fun requireStable(value: String, label: String) {
    require(value.matches(STABLE_NAME)) { "$label is not stable" }
}

private val STABLE_NAME = Regex("[a-z][a-z0-9_.-]{0,63}")
private val FORBIDDEN_KEYS = setOf("password", "secret", "token", "credential", "authorization", "host", "path")
private const val MAX_ID_CHARS = 512
private const val MAX_VALUE_CHARS = 1_024
private const val MAX_ATTRIBUTES = 32
private const val MAX_SCHEMA_VERSION = 16
private const val MAX_ATTEMPTS = 1_000
