package com.example.agentrelay.visual

import kotlinx.serialization.Serializable

/** A redacted, deterministic observation shared by journey and screenshot harnesses. */
@Serializable
data class RegressionObservation(
    val journeyId: String,
    val viewport: ViewportClass,
    val textScale: TextScale,
    val navigationSteps: Int,
    val scrolls: Int,
    val confirmations: Int,
    val clippedElements: Int,
    val overlappingElements: Int,
    val truncatedLabels: Int,
    val missingContentDescriptions: Int,
    val undersizedTargets: Int,
    val startupMillis: Long,
    val interactionMillis: Long,
    val primaryActionVisible: Boolean,
) {
    init {
        require(journeyId.matches(Regex("[a-z][a-z0-9-]{2,63}")))
        require(navigationSteps >= 0 && scrolls >= 0 && confirmations >= 0)
        require(
            clippedElements >= 0 && overlappingElements >= 0 && truncatedLabels >= 0 &&
                missingContentDescriptions >= 0 && undersizedTargets >= 0,
        )
        require(startupMillis >= 0 && interactionMillis >= 0)
    }
}

@Serializable
data class RegressionBudgets(
    val maxNavigationSteps: Int,
    val maxScrolls: Int,
    val maxConfirmations: Int,
    val maxClippedElements: Int = 0,
    val maxOverlappingElements: Int = 0,
    val maxTruncatedLabels: Int = 0,
    val maxMissingContentDescriptions: Int = 0,
    val maxUndersizedTargets: Int = 0,
    val maxStartupMillis: Long = 2_000,
    val maxInteractionMillis: Long = 1_000,
) {
    init {
        require(maxNavigationSteps >= 0 && maxScrolls >= 0 && maxConfirmations >= 0)
        require(
            maxClippedElements >= 0 && maxOverlappingElements >= 0 && maxTruncatedLabels >= 0 &&
                maxMissingContentDescriptions >= 0 && maxUndersizedTargets >= 0,
        )
        require(maxStartupMillis > 0 && maxInteractionMillis > 0)
    }
}

data class RegressionEvaluation(
    val passed: Boolean,
    val failures: List<String>,
)

fun RegressionBudgets.evaluate(observation: RegressionObservation): RegressionEvaluation {
    val failures = buildList {
        if (observation.navigationSteps > maxNavigationSteps) add("navigation-steps")
        if (observation.scrolls > maxScrolls) add("scrolls")
        if (observation.confirmations > maxConfirmations) add("confirmations")
        if (observation.clippedElements > maxClippedElements) add("clipped-elements")
        if (observation.overlappingElements > maxOverlappingElements) add("overlapping-elements")
        if (observation.truncatedLabels > maxTruncatedLabels) add("truncated-labels")
        if (observation.missingContentDescriptions > maxMissingContentDescriptions) {
            add("missing-content-descriptions")
        }
        if (observation.undersizedTargets > maxUndersizedTargets) add("undersized-targets")
        if (observation.startupMillis > maxStartupMillis) add("startup-latency")
        if (observation.interactionMillis > maxInteractionMillis) add("interaction-latency")
        if (!observation.primaryActionVisible) add("primary-action-hidden")
    }
    return RegressionEvaluation(failures.isEmpty(), failures)
}
