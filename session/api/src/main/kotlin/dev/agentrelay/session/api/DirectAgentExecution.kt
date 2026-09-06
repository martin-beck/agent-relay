package dev.agentrelay.session.api

/** Adapter state is runtime evidence; workflow state remains journal-owned. */
enum class DirectAgentExecutionState { IDLE, RUNNING, SUCCEEDED, FAILED, CANCELLED, RECOVERABLE }

data class DirectAgentExecutionEvidence(
    val requestId: String,
    val state: DirectAgentExecutionState,
    val observedAtEpochMillis: Long,
    val evidenceDigest: String,
) {
    init {
        require(requestId.matches(DIRECT_REQUEST_ID_PATTERN)) { "Direct request id is invalid" }
        require(observedAtEpochMillis >= 0) { "Direct observation time is invalid" }
        require(evidenceDigest.matches(DIRECT_DIGEST_PATTERN)) { "Direct evidence digest is invalid" }
    }
}

fun interface DirectAgentDriver {
    fun execute(
        request: ExecutionEngineRequest,
        progress: (ExecutionEngineProgress) -> Unit,
        cancellationRequested: () -> Boolean,
    ): ExecutionEngineResult
}

/** Single-agent execution adapter with explicit cancellation and recovery evidence. */
class DirectAgentExecutionAdapter(
    private val driver: DirectAgentDriver,
    private val nowEpochMillis: () -> Long,
) {
    private var state = DirectAgentExecutionState.IDLE
    private var activeRequestId: String? = null
    private var cancellationRequested = false
    private var evidence: DirectAgentExecutionEvidence? = null

    fun state(): DirectAgentExecutionState = state

    fun evidence(): DirectAgentExecutionEvidence? = evidence

    fun execute(request: ExecutionEngineRequest): ExecutionEngineResult {
        require(state == DirectAgentExecutionState.IDLE) { "Direct adapter is not idle" }
        state = DirectAgentExecutionState.RUNNING
        activeRequestId = request.requestId
        cancellationRequested = false
        return try {
            val result = driver.execute(request, {}, ::isCancellationRequested)
            val finalState = when {
                cancellationRequested -> DirectAgentExecutionState.CANCELLED
                result.outcome == ExecutionEngineOutcome.SUCCEEDED -> DirectAgentExecutionState.SUCCEEDED
                result.outcome == ExecutionEngineOutcome.CANCELLED -> DirectAgentExecutionState.CANCELLED
                result.outcome == ExecutionEngineOutcome.UNKNOWN -> DirectAgentExecutionState.RECOVERABLE
                else -> DirectAgentExecutionState.FAILED
            }
            finish(request.requestId, finalState)
            if (finalState == DirectAgentExecutionState.CANCELLED) {
                result.copy(outcome = ExecutionEngineOutcome.CANCELLED, summary = "Direct execution cancelled")
            } else {
                result
            }
        } catch (failure: RuntimeException) {
            finish(request.requestId, DirectAgentExecutionState.RECOVERABLE)
            val detail = failure.message?.takeIf { it.isNotBlank() } ?: failure::class.simpleName
            ExecutionEngineResult(
                ExecutionEngineOutcome.UNKNOWN,
                "Direct execution requires recovery${detail?.let { ": $it" }.orEmpty()}",
            )
        }
    }

    fun cancel(requestId: String) {
        require(activeRequestId == requestId && state == DirectAgentExecutionState.RUNNING) {
            "Only the active direct request may be cancelled"
        }
        cancellationRequested = true
    }

    fun recover(requestId: String, evidenceDigest: String): DirectAgentExecutionEvidence {
        require(activeRequestId == requestId && state == DirectAgentExecutionState.RECOVERABLE) {
            "Only a recoverable request may be recovered"
        }
        val recovered = DirectAgentExecutionEvidence(
            requestId,
            DirectAgentExecutionState.IDLE,
            nowEpochMillis(),
            evidenceDigest,
        )
        state = DirectAgentExecutionState.IDLE
        activeRequestId = null
        evidence = recovered
        return recovered
    }

    private fun isCancellationRequested(): Boolean = cancellationRequested

    private fun finish(requestId: String, finalState: DirectAgentExecutionState) {
        state = finalState
        evidence = DirectAgentExecutionEvidence(
            requestId,
            finalState,
            nowEpochMillis(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        )
        if (finalState != DirectAgentExecutionState.RECOVERABLE) activeRequestId = null
    }
}

private val DIRECT_REQUEST_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
private val DIRECT_DIGEST_PATTERN = Regex("[a-fA-F0-9]{64}")
