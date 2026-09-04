package dev.agentrelay.session.api

/** Stable categories used by retry policy and durable diagnostics. */
enum class WorkflowFailureCategory {
    TRANSIENT,
    RATE_LIMITED,
    TIMEOUT,
    VALIDATION,
    AUTHORIZATION,
    PERMANENT,
    UNCERTAIN_EFFECT,
}

data class WorkflowRetryPolicy(
    val maxAttempts: Int,
    val baseBackoffMillis: Long,
    val maxBackoffMillis: Long,
) {
    init {
        require(maxAttempts in 1..32) { "Retry attempts are invalid" }
        require(baseBackoffMillis > 0 && maxBackoffMillis >= baseBackoffMillis) {
            "Retry backoff bounds are invalid"
        }
    }

    fun decision(attempt: Int, category: WorkflowFailureCategory, effect: WorkflowEffectState): RetryDecision {
        require(attempt in 1..maxAttempts) { "Attempt is outside retry policy" }
        if (effect == WorkflowEffectState.UNCERTAIN || category == WorkflowFailureCategory.UNCERTAIN_EFFECT) {
            return RetryDecision.DoNotRetry("effect-outcome-uncertain")
        }
        if (category !in setOf(WorkflowFailureCategory.TRANSIENT, WorkflowFailureCategory.RATE_LIMITED, WorkflowFailureCategory.TIMEOUT)) {
            return RetryDecision.DoNotRetry("failure-is-not-retryable")
        }
        if (attempt >= maxAttempts) return RetryDecision.DoNotRetry("retry-budget-exhausted")
        val exponent = (attempt - 1).coerceAtMost(30)
        val delay = (baseBackoffMillis * (1L shl exponent)).coerceAtMost(maxBackoffMillis)
        return RetryDecision.RetryAfter(delay)
    }
}

sealed interface RetryDecision {
    data class RetryAfter(val delayMillis: Long) : RetryDecision
    data class DoNotRetry(val reason: String) : RetryDecision
}

data class WorkflowExecutionPolicy(
    val maxConcurrentRuns: Int,
    val maxConcurrentSteps: Int,
    val leaseMillis: Long,
    val retry: WorkflowRetryPolicy,
) {
    init {
        require(maxConcurrentRuns > 0 && maxConcurrentSteps > 0) { "Concurrency bounds are invalid" }
        require(leaseMillis > 0) { "Lease must be positive" }
    }
}

data class WorkflowExecutionRevision(
    val value: Long,
    val state: WorkflowRunState,
    val cancellationRequested: Boolean,
)

data class WorkflowStepLease(val stepId: WorkflowStepId, val attempt: Int, val expiresAtMillis: Long)

data class WorkflowTransitionRecord(
    val revision: Long,
    val from: WorkflowRunState,
    val to: WorkflowRunState,
    val reason: String,
)

/**
 * Serialized execution boundary. A production adapter can persist the same commands as
 * journal facts; this reference implementation makes revision and lease invariants explicit.
 */
class InMemoryWorkflowExecution(
    private val policy: WorkflowExecutionPolicy,
    private val initialState: WorkflowRunState = WorkflowRunState.QUEUED,
) {
    private var revision = 0L
    private var state = initialState
    private var cancellationRequested = false
    private val leases = mutableMapOf<WorkflowStepId, WorkflowStepLease>()
    private val attempts = mutableMapOf<WorkflowStepId, Int>()
    private val transitions = mutableListOf<WorkflowTransitionRecord>()

    @Synchronized
    fun revision(): WorkflowExecutionRevision = WorkflowExecutionRevision(revision, state, cancellationRequested)

    @Synchronized
    fun transition(expectedRevision: Long, to: WorkflowRunState, reason: String): WorkflowExecutionRevision {
        require(expectedRevision == revision) { "Stale workflow revision" }
        require(reason.isNotBlank() && reason.length <= 256) { "Transition reason is invalid" }
        require(WorkflowTransitions.run(state, to)) { "Illegal workflow transition" }
        require(!cancellationRequested || to == WorkflowRunState.CANCELLED) {
            "Cancellation must be resolved before another transition"
        }
        if (to == WorkflowRunState.RUNNING) {
            require(activeRuns < policy.maxConcurrentRuns) { "Run concurrency limit reached" }
        }
        state = to
        revision += 1
        transitions += WorkflowTransitionRecord(revision, transitions.lastOrNull()?.to ?: initialState, to, reason)
        return revision()
    }

    @Synchronized
    fun requestCancellation(expectedRevision: Long): WorkflowExecutionRevision {
        require(expectedRevision == revision) { "Stale workflow revision" }
        require(state !in TERMINAL_RUN_STATES) { "Terminal workflow cannot be cancelled" }
        cancellationRequested = true
        revision += 1
        return revision()
    }

    @Synchronized
    fun acquireStep(stepId: WorkflowStepId, nowMillis: Long): WorkflowStepLease {
        require(state == WorkflowRunState.RUNNING) { "Steps require a running workflow" }
        require(!cancellationRequested) { "Cancelled workflow cannot acquire steps" }
        require(leases.size < policy.maxConcurrentSteps) { "Step concurrency limit reached" }
        require(stepId !in leases) { "Step already leased" }
        val attempt = attempts.getOrDefault(stepId, 0) + 1
        require(attempt <= policy.retry.maxAttempts) { "Step retry budget exhausted" }
        return WorkflowStepLease(stepId, attempt, nowMillis + policy.leaseMillis).also {
            attempts[stepId] = attempt
            leases[stepId] = it
        }
    }

    @Synchronized
    fun releaseStep(stepId: WorkflowStepId, expectedAttempt: Int) {
        val lease = leases[stepId] ?: error("Step is not leased")
        require(lease.attempt == expectedAttempt) { "Stale step attempt" }
        leases.remove(stepId)
    }

    @Synchronized
    fun retryDecision(
        stepId: WorkflowStepId,
        expectedAttempt: Int,
        category: WorkflowFailureCategory,
        effect: WorkflowEffectState,
    ): RetryDecision {
        val lease = leases[stepId] ?: error("Step is not leased")
        require(lease.attempt == expectedAttempt) { "Stale step attempt" }
        return policy.retry.decision(expectedAttempt, category, effect)
    }

    @Synchronized
    fun expireLeases(nowMillis: Long): List<WorkflowStepLease> = leases.values.filter { it.expiresAtMillis <= nowMillis }
        .also { expired -> expired.forEach { leases.remove(it.stepId) } }

    @Synchronized
    fun transitionHistory(): List<WorkflowTransitionRecord> = transitions.toList()

    private val activeRuns: Int get() = if (state == WorkflowRunState.RUNNING) 1 else 0

    private companion object {
        val TERMINAL_RUN_STATES = setOf(
            WorkflowRunState.SUCCEEDED,
            WorkflowRunState.FAILED,
            WorkflowRunState.UNCERTAIN,
            WorkflowRunState.CANCELLED,
        )
    }
}
