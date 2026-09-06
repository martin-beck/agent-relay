package dev.agentrelay.session.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DirectAgentExecutionTest {
    @Test
    fun successfulDriverProducesEvidenceAndResult() {
        val adapter = adapter { _, _, _ -> result(ExecutionEngineOutcome.SUCCEEDED, "done") }

        assertEquals(ExecutionEngineOutcome.SUCCEEDED, adapter.execute(request()).outcome)
        assertEquals(DirectAgentExecutionState.SUCCEEDED, adapter.state())
        assertEquals(100L, adapter.evidence()?.observedAtEpochMillis)
    }

    @Test
    fun cancellationIsObservedByDriverAndReturnedAsCancelled() {
        lateinit var adapter: DirectAgentExecutionAdapter
        adapter = adapter { _, _, cancelled ->
            adapter.cancel("request-1")
            if (cancelled()) result(ExecutionEngineOutcome.SUCCEEDED, "ignored") else result(ExecutionEngineOutcome.FAILED, "not cancelled")
        }

        assertEquals(ExecutionEngineOutcome.CANCELLED, adapter.execute(request()).outcome)
        assertEquals(DirectAgentExecutionState.CANCELLED, adapter.state())
    }

    @Test
    fun driverFailureRequiresExplicitRecoveryEvidence() {
        val adapter = adapter { _, _, _ -> error("process lost") }

        assertEquals(ExecutionEngineOutcome.UNKNOWN, adapter.execute(request()).outcome)
        assertEquals(DirectAgentExecutionState.RECOVERABLE, adapter.state())
        assertEquals("request-1", adapter.recover("request-1", DIGEST).requestId)
        assertEquals(DirectAgentExecutionState.IDLE, adapter.state())
        assertFailsWith<IllegalArgumentException> { adapter.recover("request-1", "bad") }
    }

    private fun adapter(driver: DirectAgentDriver) = DirectAgentExecutionAdapter(driver) { 100 }

    private fun request() = ExecutionEngineRequest("request-1", "run", DIGEST, 200, 1_024)

    private fun result(outcome: ExecutionEngineOutcome, summary: String) =
        ExecutionEngineResult(outcome, summary)

    private companion object {
        const val DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
