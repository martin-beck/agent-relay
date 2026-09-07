package dev.agentrelay.workflow.api

enum class WorkflowProgressStatus { RUNNING, BLOCKED, FAILED, RECOVERED, COMPLETE, UNCERTAIN }

enum class WorkflowProgressAttentionReason {
    STALE_HEARTBEAT,
    EXPLICIT_BLOCK,
    FAILURE_PROVEN,
    PROOF_UNCERTAIN,
}

enum class WorkflowDiagnosisState { START, CONTINUE, EXHAUSTED }

data class WorkflowProgressPolicy(
    val staleAfterSeconds: Long,
    val evidenceMaxAgeSeconds: Long,
    val maxDiagnosisAttempts: Int,
    val maxSummaryChars: Int,
) {
    init {
        require(staleAfterSeconds in 1..MAX_DURATION_SECONDS) { "Stale heartbeat budget is invalid" }
        require(evidenceMaxAgeSeconds in 1..MAX_DURATION_SECONDS) { "Evidence freshness budget is invalid" }
        require(maxDiagnosisAttempts in 1..MAX_DIAGNOSIS_ATTEMPTS) { "Diagnosis budget is invalid" }
        require(maxSummaryChars in MIN_SUMMARY_CHARS..MAX_SUMMARY_CHARS) { "Summary budget is invalid" }
    }
}

data class WorkflowProgressEvidence(
    val signal: String,
    val sha256: String,
    val recordedAtSeconds: Long,
) {
    init {
        require(signal.matches(SIGNAL_PATTERN)) { "Evidence signal is invalid" }
        require(sha256.matches(SHA256_PATTERN)) { "Evidence hash must be lowercase SHA-256" }
        require(recordedAtSeconds >= 0) { "Evidence timestamp is invalid" }
    }

    fun isFreshAt(nowSeconds: Long, maxAgeSeconds: Long): Boolean {
        require(nowSeconds >= 0) { "Current timestamp is invalid" }
        require(maxAgeSeconds > 0) { "Evidence freshness budget is invalid" }
        return nowSeconds >= recordedAtSeconds && nowSeconds - recordedAtSeconds <= maxAgeSeconds
    }
}

data class WorkflowProgressUpdate(
    val runId: String,
    val sequence: Long,
    val status: WorkflowProgressStatus,
    val completedSteps: Int,
    val totalSteps: Int,
    val heartbeatAtSeconds: Long,
    val evidence: List<WorkflowProgressEvidence>,
    val summary: String,
    val sensitiveValues: Set<String> = emptySet(),
) {
    init {
        require(runId.matches(RUN_ID_PATTERN)) { "Progress run id is invalid" }
        require(sequence >= 0) { "Progress sequence is invalid" }
        require(totalSteps in 1..MAX_PROGRESS_STEPS) { "Total step count is invalid" }
        require(completedSteps in 0..totalSteps) { "Completed step count is invalid" }
        require(heartbeatAtSeconds >= 0) { "Heartbeat timestamp is invalid" }
        require(evidence.isNotEmpty() && evidence.size <= MAX_EVIDENCE_ITEMS) {
            "Every progress claim needs bounded evidence"
        }
        require(evidence.map(WorkflowProgressEvidence::signal).toSet().size == evidence.size) {
            "Progress evidence signals must be unique"
        }
        require(evidence.all { it.recordedAtSeconds <= heartbeatAtSeconds }) {
            "Evidence cannot postdate its progress heartbeat"
        }
        require(summary.isNotBlank() && summary.length <= MAX_RAW_SUMMARY_CHARS) { "Progress summary is invalid" }
        require(sensitiveValues.size <= MAX_SENSITIVE_VALUES) { "Too many sensitive summary values" }
        require(sensitiveValues.all { it.isNotBlank() && it.length <= MAX_SENSITIVE_VALUE_CHARS }) {
            "Sensitive summary value is invalid"
        }
    }
}

data class WorkflowProgressAttention(
    val id: String,
    val reason: WorkflowProgressAttentionReason,
    val openedAtSeconds: Long,
)

data class WorkflowDiagnosisPlan(
    val state: WorkflowDiagnosisState,
    val attempt: Int,
    val maxAttempts: Int,
)

data class WorkflowProgressView(
    val status: WorkflowProgressStatus,
    val headline: String,
    val summary: String,
    val completedSteps: Int,
    val totalSteps: Int,
    val evidenceHashes: List<String>,
    val evidenceFresh: Boolean,
    val attention: WorkflowProgressAttention?,
    val diagnosis: WorkflowDiagnosisPlan?,
)

object WorkflowProgressProjector {
    fun project(
        workflowId: WorkflowId,
        completion: WorkflowCompletionContract,
        policy: WorkflowProgressPolicy,
        updates: List<WorkflowProgressUpdate>,
        nowSeconds: Long,
        completedDiagnosisAttempts: Int = 0,
    ): WorkflowProgressView {
        require(updates.isNotEmpty() && updates.size <= MAX_PROGRESS_UPDATES) {
            "Progress replay is empty or unbounded"
        }
        require(nowSeconds >= 0) { "Current timestamp is invalid" }
        require(completedDiagnosisAttempts in 0..policy.maxDiagnosisAttempts) {
            "Completed diagnosis count is invalid"
        }
        validateReplay(updates)
        val latest = updates.last()
        require(nowSeconds >= latest.heartbeatAtSeconds) { "Current time predates the latest heartbeat" }

        val allFreshEvidence = updates
            .asSequence()
            .flatMap { it.evidence.asSequence() }
            .filter { it.isFreshAt(nowSeconds, policy.evidenceMaxAgeSeconds) }
            .associateBy(WorkflowProgressEvidence::signal)
        val latestEvidenceFresh = latest.evidence.any {
            it.isFreshAt(nowSeconds, policy.evidenceMaxAgeSeconds)
        }
        val staleHeartbeat = nowSeconds - latest.heartbeatAtSeconds > policy.staleAfterSeconds
        val stalledActiveRun = staleHeartbeat && latest.status in ACTIVE_STATUSES
        val projectedStatus = projectedStatus(
            latest,
            completion,
            allFreshEvidence.keys,
            latestEvidenceFresh,
            stalledActiveRun,
        )
        val reason = attentionReason(projectedStatus, stalledActiveRun)
        val attention = reason?.let {
            WorkflowProgressAttention(
                id = attentionId(workflowId, latest.runId),
                reason = it,
                openedAtSeconds = if (stalledActiveRun) {
                    latest.heartbeatAtSeconds + policy.staleAfterSeconds
                } else {
                    latest.heartbeatAtSeconds
                },
            )
        }
        return WorkflowProgressView(
            status = projectedStatus,
            headline = headline(projectedStatus),
            summary = WorkflowProgressSummary.redactAndBound(
                latest.summary,
                latest.sensitiveValues,
                policy.maxSummaryChars,
            ),
            completedSteps = latest.completedSteps,
            totalSteps = latest.totalSteps,
            evidenceHashes = allFreshEvidence.values.map(WorkflowProgressEvidence::sha256).distinct().sorted(),
            evidenceFresh = latestEvidenceFresh,
            attention = attention,
            diagnosis = if (stalledActiveRun) diagnosis(policy, completedDiagnosisAttempts) else null,
        )
    }

    private fun projectedStatus(
        latest: WorkflowProgressUpdate,
        completion: WorkflowCompletionContract,
        freshSignals: Set<String>,
        latestEvidenceFresh: Boolean,
        stalledActiveRun: Boolean,
    ): WorkflowProgressStatus {
        if (stalledActiveRun) return WorkflowProgressStatus.BLOCKED
        if (!latestEvidenceFresh) return WorkflowProgressStatus.UNCERTAIN
        return when (latest.status) {
            WorkflowProgressStatus.COMPLETE -> completionStatus(latest, completion, freshSignals)
            WorkflowProgressStatus.FAILED -> failureStatus(completion, freshSignals)
            else -> latest.status
        }
    }

    private fun completionStatus(
        latest: WorkflowProgressUpdate,
        completion: WorkflowCompletionContract,
        freshSignals: Set<String>,
    ): WorkflowProgressStatus {
        val successProven = completion.requiredSignals.all(freshSignals::contains)
        val failurePresent = completion.failureSignals.any(freshSignals::contains)
        return if (successProven && !failurePresent && latest.completedSteps == latest.totalSteps) {
            WorkflowProgressStatus.COMPLETE
        } else {
            WorkflowProgressStatus.UNCERTAIN
        }
    }

    private fun failureStatus(
        completion: WorkflowCompletionContract,
        freshSignals: Set<String>,
    ): WorkflowProgressStatus = if (
        completion.failureSignals.isEmpty() || completion.failureSignals.any(freshSignals::contains)
    ) {
        WorkflowProgressStatus.FAILED
    } else {
        WorkflowProgressStatus.UNCERTAIN
    }

    private fun attentionReason(
        status: WorkflowProgressStatus,
        stalledActiveRun: Boolean,
    ): WorkflowProgressAttentionReason? = when {
        stalledActiveRun -> WorkflowProgressAttentionReason.STALE_HEARTBEAT
        status == WorkflowProgressStatus.BLOCKED -> WorkflowProgressAttentionReason.EXPLICIT_BLOCK
        status == WorkflowProgressStatus.FAILED -> WorkflowProgressAttentionReason.FAILURE_PROVEN
        status == WorkflowProgressStatus.UNCERTAIN -> WorkflowProgressAttentionReason.PROOF_UNCERTAIN
        else -> null
    }

    private fun diagnosis(
        policy: WorkflowProgressPolicy,
        completedAttempts: Int,
    ): WorkflowDiagnosisPlan = if (completedAttempts == policy.maxDiagnosisAttempts) {
        WorkflowDiagnosisPlan(WorkflowDiagnosisState.EXHAUSTED, completedAttempts, policy.maxDiagnosisAttempts)
    } else {
        WorkflowDiagnosisPlan(
            state = if (completedAttempts == 0) WorkflowDiagnosisState.START else WorkflowDiagnosisState.CONTINUE,
            attempt = completedAttempts + 1,
            maxAttempts = policy.maxDiagnosisAttempts,
        )
    }

    private fun validateReplay(updates: List<WorkflowProgressUpdate>) {
        updates.zipWithNext().forEach { (previous, next) ->
            require(next.runId == previous.runId) { "Progress replay cannot mix runs" }
            require(next.sequence > previous.sequence) { "Progress sequence must increase" }
            require(next.heartbeatAtSeconds >= previous.heartbeatAtSeconds) { "Heartbeat must not move backward" }
            require(next.totalSteps == previous.totalSteps) { "Total step count cannot change during a run" }
            require(next.completedSteps >= previous.completedSteps) { "Completed step count must be monotonic" }
            require(transitionAllowed(previous.status, next.status)) { "Progress status transition is invalid" }
        }
    }

    private fun transitionAllowed(
        from: WorkflowProgressStatus,
        to: WorkflowProgressStatus,
    ): Boolean = when (from) {
        WorkflowProgressStatus.RUNNING -> to in setOf(
            WorkflowProgressStatus.RUNNING,
            WorkflowProgressStatus.BLOCKED,
            WorkflowProgressStatus.FAILED,
            WorkflowProgressStatus.COMPLETE,
            WorkflowProgressStatus.UNCERTAIN,
        )
        WorkflowProgressStatus.BLOCKED -> to in setOf(
            WorkflowProgressStatus.BLOCKED,
            WorkflowProgressStatus.RECOVERED,
            WorkflowProgressStatus.FAILED,
            WorkflowProgressStatus.UNCERTAIN,
        )
        WorkflowProgressStatus.RECOVERED -> to in ACTIVE_STATUSES + setOf(
            WorkflowProgressStatus.FAILED,
            WorkflowProgressStatus.COMPLETE,
            WorkflowProgressStatus.UNCERTAIN,
        )
        WorkflowProgressStatus.UNCERTAIN -> to in setOf(
            WorkflowProgressStatus.UNCERTAIN,
            WorkflowProgressStatus.RECOVERED,
            WorkflowProgressStatus.FAILED,
            WorkflowProgressStatus.COMPLETE,
        )
        WorkflowProgressStatus.FAILED -> to == WorkflowProgressStatus.FAILED
        WorkflowProgressStatus.COMPLETE -> to == WorkflowProgressStatus.COMPLETE
    }

    private fun headline(status: WorkflowProgressStatus): String = when (status) {
        WorkflowProgressStatus.RUNNING -> "Running"
        WorkflowProgressStatus.BLOCKED -> "Needs attention"
        WorkflowProgressStatus.FAILED -> "Failed with evidence"
        WorkflowProgressStatus.RECOVERED -> "Recovered"
        WorkflowProgressStatus.COMPLETE -> "Complete and verified"
        WorkflowProgressStatus.UNCERTAIN -> "Outcome not proven"
    }

    private fun attentionId(workflowId: WorkflowId, runId: String): String =
        "attention_v1_${workflowId.value.removePrefix("wf_v1_")}_${runId.removePrefix("run_v1_")}_progress"
}

object WorkflowProgressSummary {
    fun redactAndBound(raw: String, sensitiveValues: Set<String>, maxChars: Int): String {
        require(raw.isNotBlank() && raw.length <= MAX_RAW_SUMMARY_CHARS) { "Progress summary is invalid" }
        require(maxChars in MIN_SUMMARY_CHARS..MAX_SUMMARY_CHARS) { "Summary budget is invalid" }
        require(sensitiveValues.size <= MAX_SENSITIVE_VALUES) { "Too many sensitive summary values" }
        var redacted = raw
        sensitiveValues.sortedByDescending(String::length).forEach { sensitive ->
            require(sensitive.isNotBlank() && sensitive.length <= MAX_SENSITIVE_VALUE_CHARS) {
                "Sensitive summary value is invalid"
            }
            redacted = redacted.replace(sensitive, REDACTED)
        }
        redacted = SECRET_ASSIGNMENT.replace(redacted) { match -> "${match.groupValues[1]}=$REDACTED" }
        val normalized = WHITESPACE.replace(redacted, " ").trim()
        return if (normalized.length <= maxChars) normalized else normalized.take(maxChars - 1).trimEnd() + ELLIPSIS
    }
}

private val ACTIVE_STATUSES = setOf(WorkflowProgressStatus.RUNNING, WorkflowProgressStatus.RECOVERED)
private val SIGNAL_PATTERN = Regex("[a-z][a-z0-9_.-]{0,63}")
private val RUN_ID_PATTERN = Regex("run_v1_[a-z0-9-]{1,48}")
private val SHA256_PATTERN = Regex("[a-f0-9]{64}")
private val SECRET_ASSIGNMENT = Regex("(?i)\\b(token|password|secret|authorization)\\s*[:=]\\s*\\S+")
private val WHITESPACE = Regex("\\s+")
private const val REDACTED = "[REDACTED]"
private const val ELLIPSIS = "…"
private const val MAX_DURATION_SECONDS = 31_536_000L
private const val MAX_DIAGNOSIS_ATTEMPTS = 16
private const val MAX_PROGRESS_STEPS = 10_000
private const val MAX_EVIDENCE_ITEMS = 128
private const val MAX_PROGRESS_UPDATES = 10_000
private const val MIN_SUMMARY_CHARS = 24
private const val MAX_SUMMARY_CHARS = 1_000
private const val MAX_RAW_SUMMARY_CHARS = 4_096
private const val MAX_SENSITIVE_VALUES = 32
private const val MAX_SENSITIVE_VALUE_CHARS = 256
