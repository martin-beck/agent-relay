package dev.agentrelay.provider.api

/** Normalized provider-level observations below workflow semantics. */
sealed interface ProviderOutcome {
    val observedAtEpochSeconds: Long

    data class Progress(
        override val observedAtEpochSeconds: Long,
        val completedUnits: Long,
        val totalUnits: Long? = null,
        val message: String? = null,
    ) : ProviderOutcome {
        init {
            require(observedAtEpochSeconds >= 0) { "Observation time must not be negative" }
            require(completedUnits >= 0) { "Completed units must not be negative" }
            require(totalUnits == null || totalUnits >= completedUnits) {
                "Total units must be greater than or equal to completed units"
            }
            require(message == null || message.length <= MAX_OUTCOME_MESSAGE_LENGTH) {
                "Progress message is too long"
            }
        }
    }

    data class Result(
        override val observedAtEpochSeconds: Long,
        val outcome: ProviderResultKind,
        val summary: String? = null,
    ) : ProviderOutcome {
        init {
            require(observedAtEpochSeconds >= 0) { "Observation time must not be negative" }
            require(summary == null || summary.length <= MAX_OUTCOME_MESSAGE_LENGTH) {
                "Result summary is too long"
            }
        }
    }

    data class Checkpoint(
        override val observedAtEpochSeconds: Long,
        val token: String,
        val resumable: Boolean = true,
    ) : ProviderOutcome {
        init {
            require(observedAtEpochSeconds >= 0) { "Observation time must not be negative" }
            require(token.matches(Regex("[A-Za-z0-9._:-]{1,128}"))) {
                "Checkpoint token must be a bounded opaque identifier"
            }
        }
    }

    data class Resource(
        override val observedAtEpochSeconds: Long,
        val kind: ProviderResourceKind,
        val consumedUnits: Long,
        val limitUnits: Long? = null,
    ) : ProviderOutcome {
        init {
            require(observedAtEpochSeconds >= 0) { "Observation time must not be negative" }
            require(consumedUnits >= 0) { "Consumed units must not be negative" }
            require(limitUnits == null || limitUnits >= consumedUnits) {
                "Resource limit must be greater than or equal to consumed units"
            }
        }
    }

    data class Failure(
        override val observedAtEpochSeconds: Long,
        val kind: ProviderFailureKind,
        val recoverable: Boolean,
        val detail: String? = null,
    ) : ProviderOutcome {
        init {
            require(observedAtEpochSeconds >= 0) { "Observation time must not be negative" }
            require(detail == null || detail.length <= MAX_OUTCOME_MESSAGE_LENGTH) {
                "Failure detail is too long"
            }
        }
    }
}

enum class ProviderResultKind {
    SUCCEEDED,
    CANCELLED,
    PARTIAL,
}

enum class ProviderResourceKind {
    TOKENS,
    BYTES,
    REQUESTS,
    TIME_MILLIS,
}

enum class ProviderFailureKind {
    AUTHENTICATION,
    AUTHORIZATION,
    NETWORK,
    RATE_LIMIT,
    INVALID_REQUEST,
    PROVIDER,
    UNKNOWN,
}

const val MAX_OUTCOME_MESSAGE_LENGTH = 1024
