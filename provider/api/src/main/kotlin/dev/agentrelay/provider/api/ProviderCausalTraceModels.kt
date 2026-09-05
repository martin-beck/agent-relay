package dev.agentrelay.provider.api

@JvmInline
value class ProviderTraceId(val value: String) {
    init {
        require(value.matches(Regex("[a-z][a-z0-9._:-]{1,127}"))) {
            "Provider trace id must be a bounded lowercase identifier"
        }
    }
}

enum class ProviderTracePhase {
    REQUESTED,
    STARTED,
    PROGRESSING,
    CHECKPOINTED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class ProviderCausalTrace(
    val traceId: ProviderTraceId,
    val parentTraceId: ProviderTraceId? = null,
    val phase: ProviderTracePhase,
    val observedAtEpochSeconds: Long,
    val outcome: ProviderOutcome? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    init {
        require(observedAtEpochSeconds >= 0) { "Observation time must not be negative" }
        require(attributes.size <= MAX_TRACE_ATTRIBUTES) { "Trace has too many attributes" }
        require(attributes.keys.all { it.matches(Regex("[a-z][a-z0-9._-]{0,63}")) }) {
            "Trace attribute keys must be bounded lowercase identifiers"
        }
        require(attributes.values.all { it.length <= MAX_TRACE_ATTRIBUTE_LENGTH }) {
            "Trace attribute values are too long"
        }
        if (phase == ProviderTracePhase.COMPLETED || phase == ProviderTracePhase.FAILED) {
            require(outcome != null) { "Terminal traces require a normalized outcome" }
        }
    }
}

const val MAX_TRACE_ATTRIBUTES = 32
const val MAX_TRACE_ATTRIBUTE_LENGTH = 256
