package dev.agentrelay.provider.api

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ProviderCausalTraceModelsTest {
    @Test
    fun terminalTraceRequiresOutcome() {
        assertFailsWith<IllegalArgumentException> {
            ProviderCausalTrace(
                traceId = ProviderTraceId("trace-1"),
                phase = ProviderTracePhase.COMPLETED,
                observedAtEpochSeconds = 1,
            )
        }
    }

    @Test
    fun traceBoundsAttributes() {
        assertFailsWith<IllegalArgumentException> {
            ProviderCausalTrace(
                traceId = ProviderTraceId("trace-1"),
                phase = ProviderTracePhase.STARTED,
                observedAtEpochSeconds = 1,
                attributes = (0..MAX_TRACE_ATTRIBUTES).associate { "key$it" to "value" },
            )
        }
    }
}
