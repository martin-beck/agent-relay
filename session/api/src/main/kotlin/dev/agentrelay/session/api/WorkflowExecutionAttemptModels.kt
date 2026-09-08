/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.api

/** Stable, provider-neutral identity for one execution attempt. */
@JvmInline
value class WorkflowAttemptId(val value: String) {
    init {
        requireAttemptValue(value, "Attempt id")
    }
}

/** Implementation identity is useful for correlation, but is never workflow truth. */
@JvmInline
value class AgentInstanceId(val value: String) {
    init {
        requireAttemptValue(value, "Agent instance id")
    }
}

@JvmInline
value class TerminalSessionId(val value: String) {
    init {
        requireAttemptValue(value, "Terminal session id")
    }
}

enum class WorkflowAttemptState {
    ADMITTED,
    RUNNING,
    DISCONNECTED,
    RECOVERING,
    SUCCEEDED,
    FAILED,
    UNCERTAIN,
    ABANDONED,
}

enum class AttemptRecoveryCause { PROCESS_LOSS, TERMINAL_DISCONNECT, LEASE_EXPIRED, HOST_RESTART }

enum class AttemptCompletion { SUCCEEDED, FAILED, UNKNOWN }

enum class SessionBoundary { OPEN, DISCONNECTED, RECONNECTED, CLOSED }

data class AttemptRuntimeMetadata(
    val agentLabel: String,
    val terminalLabel: String,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        requireStableAttemptName(agentLabel, "Agent label")
        requireStableAttemptName(terminalLabel, "Terminal label")
        require(attributes.size <= MAX_ATTEMPT_ATTRIBUTES) { "Attempt metadata is too large" }
        attributes.forEach { (key, value) ->
            requireStableAttemptName(key, "Attempt metadata key")
            require(value.isNotEmpty() && value.length <= MAX_ATTEMPT_VALUE_CHARS) {
                "Attempt metadata value is invalid"
            }
            require(key !in FORBIDDEN_ATTEMPT_KEYS) { "Sensitive attempt metadata is forbidden" }
        }
    }

    /** Returns only explicitly allowed, non-sensitive metadata for diagnostics or UI. */
    fun redacted(allowedKeys: Set<String>): AttemptRuntimeMetadata = copy(
        attributes = attributes.filterKeys { it in allowedKeys },
    )
}

data class ExecutionSessionBoundaryRecord(
    val sessionId: TerminalSessionId,
    val agentInstanceId: AgentInstanceId,
    val boundary: SessionBoundary,
    val observedAtMillis: Long,
    val reason: String? = null,
) {
    init {
        require(observedAtMillis >= 0) { "Session boundary timestamp is invalid" }
        reason?.let { requireStableAttemptName(it, "Session boundary reason") }
    }
}

data class AttemptRecoveryEvidence(
    val cause: AttemptRecoveryCause,
    val observedAtMillis: Long,
    val previousSessionId: TerminalSessionId,
    val journalSequence: Long,
    val evidenceDigest: String,
) {
    init {
        require(observedAtMillis >= 0) { "Recovery timestamp is invalid" }
        require(journalSequence >= 0) { "Recovery journal sequence is invalid" }
        requireStableAttemptName(evidenceDigest, "Recovery evidence digest")
    }
}

data class WorkflowExecutionAttempt(
    val id: WorkflowAttemptId,
    val workflowId: WorkflowId,
    val runId: WorkflowRunId,
    val stepId: WorkflowStepId,
    /** Snapshot supplied by the workflow journal; this class cannot mutate workflow truth. */
    val workflowState: WorkflowRunState,
    val stepState: WorkflowStepState,
    val attemptNumber: Int,
    val agentInstanceId: AgentInstanceId,
    val terminalSessionId: TerminalSessionId,
    val metadata: AttemptRuntimeMetadata,
    val state: WorkflowAttemptState = WorkflowAttemptState.ADMITTED,
    val admittedAtMillis: Long,
    val leaseExpiresAtMillis: Long,
    val lastHeartbeatAtMillis: Long,
    val effect: WorkflowEffectState = WorkflowEffectState.NONE,
    val recoveryCount: Int = 0,
    val recoveryEvidence: List<AttemptRecoveryEvidence> = emptyList(),
    val sessionBoundaries: List<ExecutionSessionBoundaryRecord> = emptyList(),
) {
    init {
        require(attemptNumber in 1..MAX_ATTEMPT_NUMBER) { "Attempt number is invalid" }
        require(admittedAtMillis >= 0) { "Admission timestamp is invalid" }
        require(leaseExpiresAtMillis >= admittedAtMillis) { "Attempt lease is invalid" }
        require(lastHeartbeatAtMillis in admittedAtMillis..leaseExpiresAtMillis) {
            "Attempt heartbeat is invalid"
        }
        require(recoveryCount in 0..MAX_RECOVERY_COUNT) { "Recovery count is invalid" }
        require(recoveryEvidence.size <= MAX_RECOVERY_COUNT) { "Recovery evidence is too large" }
        require(sessionBoundaries.size <= MAX_SESSION_BOUNDARIES) { "Session boundaries are too large" }
        require(effect != WorkflowEffectState.UNCERTAIN || state == WorkflowAttemptState.UNCERTAIN) {
            "Uncertain effects require an uncertain attempt"
        }
        require(state != WorkflowAttemptState.UNCERTAIN || effect == WorkflowEffectState.UNCERTAIN) {
            "Uncertain attempts require an uncertain effect"
        }
    }

    val isTerminal: Boolean
        get() = state in TERMINAL_ATTEMPT_STATES

    fun isLeaseStale(nowMillis: Long): Boolean {
        require(nowMillis >= 0) { "Current timestamp is invalid" }
        return leaseExpiresAtMillis <= nowMillis
    }

    companion object {
        private val TERMINAL_ATTEMPT_STATES = setOf(
            WorkflowAttemptState.SUCCEEDED,
            WorkflowAttemptState.FAILED,
            WorkflowAttemptState.UNCERTAIN,
            WorkflowAttemptState.ABANDONED,
        )
    }
}

/** Recovery is evidence attached to an attempt; workflow state remains journal-owned. */
interface WorkflowExecutionAttemptJournal {
    fun admit(attempt: WorkflowExecutionAttempt): WorkflowExecutionAttempt
    fun heartbeat(id: WorkflowAttemptId, nowMillis: Long, leaseExpiresAtMillis: Long): WorkflowExecutionAttempt
    fun disconnect(id: WorkflowAttemptId, cause: AttemptRecoveryCause, nowMillis: Long, journalSequence: Long): WorkflowExecutionAttempt
    fun reconnect(
        id: WorkflowAttemptId,
        replacementAgent: AgentInstanceId,
        replacementSession: TerminalSessionId,
        nowMillis: Long,
        leaseExpiresAtMillis: Long,
        journalSequence: Long,
        evidenceDigest: String,
    ): WorkflowExecutionAttempt
    fun complete(id: WorkflowAttemptId, completion: AttemptCompletion): WorkflowExecutionAttempt
    fun abandon(id: WorkflowAttemptId, nowMillis: Long): WorkflowExecutionAttempt
    fun get(id: WorkflowAttemptId): WorkflowExecutionAttempt?
    fun activeForStep(stepId: WorkflowStepId): WorkflowExecutionAttempt?
}

/** Deterministic journal adapter for lifecycle, recovery and hostile-boundary tests. */
class InMemoryWorkflowExecutionAttemptJournal : WorkflowExecutionAttemptJournal {
    private val attempts = linkedMapOf<WorkflowAttemptId, WorkflowExecutionAttempt>()

    @Synchronized
    override fun admit(attempt: WorkflowExecutionAttempt): WorkflowExecutionAttempt {
        require(attempt.state == WorkflowAttemptState.ADMITTED) { "Only admitted attempts may be created" }
        require(attempt.effect == WorkflowEffectState.NONE) { "Admission cannot carry an effect" }
        require(attempt.runStateIsRunning()) { "Attempts require a running workflow" }
        require(attempt.stepStateIsRunnable()) { "Attempts require a runnable step" }
        require(attempts[attempt.id] == null) { "Attempt id already exists" }
        require(activeForStep(attempt.stepId) == null) { "Step already has an active attempt" }
        attempts[attempt.id] = attempt
        return attempt
    }

    @Synchronized
    override fun heartbeat(id: WorkflowAttemptId, nowMillis: Long, leaseExpiresAtMillis: Long): WorkflowExecutionAttempt {
        val current = getRequired(id)
        require(!current.isTerminal) { "Terminal attempt cannot heartbeat" }
        require(nowMillis >= current.lastHeartbeatAtMillis) { "Heartbeat moved backwards" }
        require(leaseExpiresAtMillis >= nowMillis) { "Heartbeat lease is invalid" }
        return save(
            current.copy(
                state = WorkflowAttemptState.RUNNING,
                lastHeartbeatAtMillis = nowMillis,
                leaseExpiresAtMillis = leaseExpiresAtMillis,
            ),
        )
    }

    @Synchronized
    override fun disconnect(
        id: WorkflowAttemptId,
        cause: AttemptRecoveryCause,
        nowMillis: Long,
        journalSequence: Long,
    ): WorkflowExecutionAttempt {
        val current = getRequired(id)
        require(!current.isTerminal) { "Terminal attempt cannot disconnect" }
        require(nowMillis >= current.lastHeartbeatAtMillis) { "Disconnect moved backwards" }
        require(journalSequence >= 0) { "Disconnect journal sequence is invalid" }
        return save(
            current.copy(
                state = WorkflowAttemptState.DISCONNECTED,
                sessionBoundaries = current.sessionBoundaries + ExecutionSessionBoundaryRecord(
                    current.terminalSessionId,
                    current.agentInstanceId,
                    SessionBoundary.DISCONNECTED,
                    nowMillis,
                    cause.name.lowercase(),
                ),
                recoveryEvidence = current.recoveryEvidence + AttemptRecoveryEvidence(
                    cause,
                    nowMillis,
                    current.terminalSessionId,
                    journalSequence,
                    "pending",
                ),
            ),
        )
    }

    @Synchronized
    override fun reconnect(
        id: WorkflowAttemptId,
        replacementAgent: AgentInstanceId,
        replacementSession: TerminalSessionId,
        nowMillis: Long,
        leaseExpiresAtMillis: Long,
        journalSequence: Long,
        evidenceDigest: String,
    ): WorkflowExecutionAttempt {
        val current = getRequired(id)
        require(
            current.state == WorkflowAttemptState.DISCONNECTED ||
                (current.state == WorkflowAttemptState.RUNNING && current.isLeaseStale(nowMillis)),
        ) {
            "Only a disconnected or stale attempt may reconnect"
        }
        require(current.effect != WorkflowEffectState.UNCERTAIN) { "Uncertain effect requires operator resolution" }
        require(leaseExpiresAtMillis >= nowMillis) { "Reconnect lease is invalid" }
        val evidence = current.recoveryEvidence.lastOrNull()
        val recovered = current.copy(
            agentInstanceId = replacementAgent,
            terminalSessionId = replacementSession,
            state = WorkflowAttemptState.RUNNING,
            leaseExpiresAtMillis = leaseExpiresAtMillis,
            lastHeartbeatAtMillis = nowMillis,
            recoveryCount = current.recoveryCount + 1,
            recoveryEvidence = if (evidence == null) {
                current.recoveryEvidence
            } else {
                current.recoveryEvidence.dropLast(1) +
                    evidence.copy(evidenceDigest = evidenceDigest)
            },
            sessionBoundaries = current.sessionBoundaries + ExecutionSessionBoundaryRecord(
                replacementSession,
                replacementAgent,
                SessionBoundary.RECONNECTED,
                nowMillis,
                "recovery",
            ),
        )
        return save(recovered)
    }

    @Synchronized
    override fun complete(id: WorkflowAttemptId, completion: AttemptCompletion): WorkflowExecutionAttempt {
        val current = getRequired(id)
        if (current.isTerminal) {
            val expected = when (current.state) {
                WorkflowAttemptState.SUCCEEDED -> AttemptCompletion.SUCCEEDED
                WorkflowAttemptState.FAILED -> AttemptCompletion.FAILED
                WorkflowAttemptState.UNCERTAIN -> AttemptCompletion.UNKNOWN
                else -> error("Invalid terminal state")
            }
            require(expected == completion) { "Conflicting duplicate completion" }
            return current
        }
        val nextState = when (completion) {
            AttemptCompletion.SUCCEEDED -> WorkflowAttemptState.SUCCEEDED
            AttemptCompletion.FAILED -> WorkflowAttemptState.FAILED
            AttemptCompletion.UNKNOWN -> WorkflowAttemptState.UNCERTAIN
        }
        return save(
            current.copy(
                state = nextState,
                effect = if (completion == AttemptCompletion.UNKNOWN) {
                    WorkflowEffectState.UNCERTAIN
                } else {
                    current.effect
                },
            ),
        )
    }

    @Synchronized
    override fun abandon(id: WorkflowAttemptId, nowMillis: Long): WorkflowExecutionAttempt {
        val current = getRequired(id)
        require(!current.isTerminal) { "Attempt is already terminal" }
        require(nowMillis >= current.lastHeartbeatAtMillis) { "Abandonment moved backwards" }
        return save(current.copy(state = WorkflowAttemptState.ABANDONED))
    }

    @Synchronized override fun get(id: WorkflowAttemptId): WorkflowExecutionAttempt? = attempts[id]

    @Synchronized override fun activeForStep(stepId: WorkflowStepId): WorkflowExecutionAttempt? =
        attempts.values.firstOrNull { it.stepId == stepId && !it.isTerminal }

    private fun getRequired(id: WorkflowAttemptId): WorkflowExecutionAttempt = attempts[id] ?: error("Unknown attempt")

    private fun save(attempt: WorkflowExecutionAttempt): WorkflowExecutionAttempt {
        attempts[attempt.id] = attempt
        return attempt
    }
}

/* The journal receives a snapshot from the workflow authority, never owns these states. */
private fun WorkflowExecutionAttempt.runStateIsRunning(): Boolean = workflowState == WorkflowRunState.RUNNING
private fun WorkflowExecutionAttempt.stepStateIsRunnable(): Boolean =
    stepState == WorkflowStepState.PENDING || stepState == WorkflowStepState.RETRYING

private fun requireAttemptValue(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_ATTEMPT_VALUE_CHARS && value.none { it.isWhitespace() }) {
        "$label is invalid"
    }
}

private fun requireStableAttemptName(value: String, label: String) {
    require(value.matches(STABLE_ATTEMPT_NAME)) { "$label is invalid" }
}

private val STABLE_ATTEMPT_NAME = Regex("[a-z0-9][a-z0-9_.:-]{0,127}")
private val FORBIDDEN_ATTEMPT_KEYS = setOf("password", "secret", "token", "credential", "authorization", "host", "path", "command")
private const val MAX_ATTEMPT_VALUE_CHARS = 512
private const val MAX_ATTEMPT_ATTRIBUTES = 16
private const val MAX_ATTEMPT_NUMBER = 32
private const val MAX_RECOVERY_COUNT = 16
private const val MAX_SESSION_BOUNDARIES = 32
