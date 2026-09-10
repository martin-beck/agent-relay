/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.api

/** Bounds for a diagnostic projection. These limits are enforced before persistence. */
data class DiagnosticCapturePolicy(
    val maxBytes: Long = 1_048_576,
    val maxRecords: Int = 2_000,
    val maxAgeMillis: Long = 86_400_000,
    val maxSessionMillis: Long = 900_000,
) {
    init {
        require(maxBytes in 1..MAX_CAPTURE_BYTES)
        require(maxRecords in 1..MAX_CAPTURE_RECORDS)
        require(maxAgeMillis in 1..MAX_CAPTURE_AGE)
        require(maxSessionMillis in 1..maxAgeMillis)
    }
}

enum class DiagnosticCaptureSensitivity { PUBLIC, INTERNAL, SENSITIVE }

data class DiagnosticCaptureScope(
    val component: String,
    val sensitivity: DiagnosticCaptureSensitivity,
    val durationMillis: Long,
    val maxBytes: Long,
    val authorized: Boolean,
) {
    init {
        require(component.matches(Regex("[a-z][a-z0-9_.-]{0,63}")))
        require(durationMillis > 0 && durationMillis <= MAX_CAPTURE_AGE)
        require(maxBytes > 0 && maxBytes <= MAX_CAPTURE_BYTES)
    }
}

@JvmInline
value class DiagnosticCaptureSessionId(val value: String) {
    init {
        require(value.isNotBlank() && value.length <= 128 && value.none(Char::isWhitespace))
    }
}

data class DiagnosticCaptureSession(
    val id: DiagnosticCaptureSessionId,
    val scope: DiagnosticCaptureScope,
    val startedAtMillis: Long,
    val expiresAtMillis: Long,
) {
    init {
        require(startedAtMillis >= 0)
        require(expiresAtMillis >= startedAtMillis)
        require(expiresAtMillis - startedAtMillis <= scope.durationMillis)
    }
    fun activeAt(nowMillis: Long): Boolean = nowMillis in startedAtMillis until expiresAtMillis
}

data class DiagnosticCaptureCursor(val sequence: Long, val eventId: String) {
    init {
        require(sequence >= 0)
        require(eventId.isNotBlank() && eventId.length <= 512 && eventId.none(Char::isWhitespace))
    }
}

data class DiagnosticCaptureQuery(
    val incidentId: String? = null,
    val traceId: String? = null,
    val workflowId: String? = null,
    val runId: String? = null,
    val stepId: String? = null,
    val attempt: Int? = null,
    val component: String? = null,
    val cursor: DiagnosticCaptureCursor? = null,
    val limit: Int = 100,
) {
    init {
        listOf(incidentId, traceId, workflowId, runId, stepId).forEach {
            it?.let { value -> require(value.isNotBlank() && value.length <= 512 && value.none(Char::isWhitespace)) }
        }
        component?.let { require(it.matches(Regex("[a-z][a-z0-9_.-]{0,63}"))) }
        require(attempt == null || attempt in 0..1000)
        require(limit in 1..1000)
    }
}

data class DiagnosticCaptureCounters(
    val sampled: Long = 0,
    val coalesced: Long = 0,
    val truncated: Long = 0,
    val overwritten: Long = 0,
    val rejected: Long = 0,
) {
    init {
        listOf(sampled, coalesced, truncated, overwritten, rejected).forEach { require(it >= 0) }
    }
    operator fun plus(other: DiagnosticCaptureCounters) = DiagnosticCaptureCounters(
        sampled + other.sampled,
        coalesced + other.coalesced,
        truncated + other.truncated,
        overwritten + other.overwritten,
        rejected + other.rejected,
    )
}

data class DiagnosticCaptureRecord(
    val sequence: Long,
    val sessionId: DiagnosticCaptureSessionId,
    val event: DiagnosticEvent,
    val byteSize: Int,
    val checksum: String,
) {
    init {
        require(sequence > 0 && byteSize > 0)
        require(checksum.matches(Regex("[0-9a-f]{64}")))
    }
}

data class DiagnosticCaptureResult(
    val records: List<DiagnosticCaptureRecord>,
    val nextCursor: DiagnosticCaptureCursor?,
    val counters: DiagnosticCaptureCounters,
)

interface DiagnosticCaptureStore {
    fun beginSession(id: DiagnosticCaptureSessionId, scope: DiagnosticCaptureScope, nowMillis: Long): DiagnosticCaptureSession
    fun endSession(id: DiagnosticCaptureSessionId)
    fun append(sessionId: DiagnosticCaptureSessionId, event: DiagnosticEvent, nowMillis: Long): DiagnosticCaptureRecord?
    fun query(query: DiagnosticCaptureQuery, nowMillis: Long): DiagnosticCaptureResult
    fun recover(): DiagnosticCaptureCounters
    fun retain(nowMillis: Long): DiagnosticCaptureCounters
    fun sessions(): List<DiagnosticCaptureSession>
}

/** Deterministic bounded reference store. A production adapter may encrypt its frames. */
class InMemoryDiagnosticCaptureStore(
    private val policy: DiagnosticCapturePolicy = DiagnosticCapturePolicy(),
    private val allowedAttributes: Set<String> = emptySet(),
) : DiagnosticCaptureStore {
    private val sessions = linkedMapOf<DiagnosticCaptureSessionId, DiagnosticCaptureSession>()
    private val records = linkedMapOf<Long, DiagnosticCaptureRecord>()
    private var nextSequence = 1L
    private var counters = DiagnosticCaptureCounters()

    @Synchronized override fun beginSession(
        id: DiagnosticCaptureSessionId,
        scope: DiagnosticCaptureScope,
        nowMillis: Long,
    ): DiagnosticCaptureSession {
        require(scope.authorized) { "Diagnostic capture requires explicit authorization" }
        require(nowMillis >= 0)
        require(id !in sessions) { "Capture session already exists" }
        val session = DiagnosticCaptureSession(id, scope, nowMillis, nowMillis + minOf(scope.durationMillis, policy.maxSessionMillis))
        sessions[id] = session
        return session
    }

    @Synchronized override fun endSession(id: DiagnosticCaptureSessionId) {
        sessions.remove(id)
    }

    @Synchronized override fun append(
        sessionId: DiagnosticCaptureSessionId,
        event: DiagnosticEvent,
        nowMillis: Long,
    ): DiagnosticCaptureRecord? {
        val session = sessions[sessionId] ?: return reject()
        if (!session.activeAt(nowMillis) || event.component != session.scope.component ||
            event.sensitivity == DiagnosticSensitivity.SECRET
        ) {
            return reject()
        }
        val retained = if (event.sensitivity.ordinal > session.scope.sensitivity.ordinal) {
            return reject()
        } else {
            DiagnosticRedactor.redact(event, allowedAttributes)
        }
        val size = estimateBytes(retained)
        if (size > session.scope.maxBytes || size > policy.maxBytes) return reject()
        val record = DiagnosticCaptureRecord(nextSequence++, sessionId, retained, size, checksum(retained))
        records[record.sequence] = record
        counters = counters.copy(sampled = counters.sampled + 1)
        evictIfNeeded()
        return record
    }

    @Synchronized override fun query(query: DiagnosticCaptureQuery, nowMillis: Long): DiagnosticCaptureResult {
        retain(nowMillis)
        val matches = records.values.asSequence().filter { record ->
            val refs = record.event.references
            (query.incidentId == null || record.event.attributes["incident"] == query.incidentId) &&
                (query.traceId == null || record.event.traceId == query.traceId) &&
                (query.workflowId == null || refs.workflowId == query.workflowId) &&
                (query.runId == null || refs.runId == query.runId) &&
                (query.stepId == null || refs.stepId == query.stepId) &&
                (query.attempt == null || refs.attempt == query.attempt) &&
                (query.component == null || record.event.component == query.component) &&
                (query.cursor == null || record.sequence > query.cursor.sequence)
        }.take(query.limit).toList()
        return DiagnosticCaptureResult(
            matches,
            matches.lastOrNull()?.let { DiagnosticCaptureCursor(it.sequence, it.event.eventId) },
            counters,
        )
    }

    @Synchronized override fun recover(): DiagnosticCaptureCounters {
        val before = records.size
        records.entries.removeIf { (_, record) -> record.checksum != checksum(record.event) }
        val removed = before - records.size
        if (removed == 0) return counters
        counters = counters.copy(rejected = counters.rejected + removed)
        return counters
    }

    @Synchronized override fun retain(nowMillis: Long): DiagnosticCaptureCounters {
        require(nowMillis >= 0)
        val cutoff = nowMillis - policy.maxAgeMillis
        val removed = records.entries.removeIf { (_, record) -> record.event.clock.wallEpochMillis < cutoff }
        if (removed) counters = counters.copy(overwritten = counters.overwritten + 1)
        sessions.entries.removeIf { (_, session) -> !session.activeAt(nowMillis) }
        return counters
    }

    @Synchronized override fun sessions(): List<DiagnosticCaptureSession> = sessions.values.toList()

    private fun reject(): Nothing? {
        counters = counters.copy(rejected = counters.rejected + 1)
        return null
    }
    private fun evictIfNeeded() {
        while (records.size > policy.maxRecords || records.values.sumOf { it.byteSize.toLong() } > policy.maxBytes) {
            records.remove(records.keys.first())
            counters = counters.copy(overwritten = counters.overwritten + 1)
        }
    }
}

private fun estimateBytes(event: DiagnosticEvent): Int = event.eventName.length + event.component.length + event.operation.length +
    event.attributes.entries.sumOf { it.key.length + it.value.length } + 128

private fun checksum(event: DiagnosticEvent): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val canonical = buildString {
        append(event.eventId).append('|').append(event.traceId).append('|').append(event.component).append('|')
            .append(event.operation).append('|')
        event.attributes.toSortedMap().forEach { (k, v) -> append(k).append('=').append(v).append(';') }
    }
    return digest.digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
}

private const val MAX_CAPTURE_BYTES = 64L * 1024 * 1024
private const val MAX_CAPTURE_RECORDS = 100_000
private const val MAX_CAPTURE_AGE = 30L * 24 * 60 * 60 * 1000
