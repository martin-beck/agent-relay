package com.example.agentrelay.journeys

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class JourneyCriticality { CRITICAL, IMPORTANT, ROUTINE }

@Serializable
enum class JourneyStartContext { FRESH_START, ATTENTION_ITEM, SESSION_DETAIL, CONNECTION_PROFILE, RECOVERY_NOTICE }

@Serializable
enum class JourneyRecovery { RETRY_IN_PLACE, RESTORE_CONTEXT, EXPLICIT_REAUTHORIZATION, SAFE_ABORT }

@Serializable
data class InteractionCostBudget(
    val navigationSteps: Int,
    val scrolls: Int,
    val entryFields: Int,
    val confirmations: Int,
    val waitMillis: Long,
    val errors: Int,
) {
    init {
        require(navigationSteps >= 0 && scrolls >= 0 && entryFields >= 0)
        require(confirmations >= 0 && waitMillis >= 0 && errors >= 0)
    }

    fun accepts(cost: InteractionCost): Boolean =
        cost.navigationSteps <= navigationSteps && cost.scrolls <= scrolls &&
            cost.entryFields <= entryFields && cost.confirmations <= confirmations &&
            cost.waitMillis <= waitMillis && cost.errors <= errors
}

@Serializable
data class InteractionCost(
    val navigationSteps: Int = 0,
    val scrolls: Int = 0,
    val entryFields: Int = 0,
    val confirmations: Int = 0,
    val waitMillis: Long = 0,
    val errors: Int = 0,
) {
    init {
        require(navigationSteps >= 0 && scrolls >= 0 && entryFields >= 0)
        require(confirmations >= 0 && waitMillis >= 0 && errors >= 0)
    }

    fun weightedScore(): Long =
        navigationSteps * 1L + scrolls * 2L + entryFields * 3L + confirmations * 4L +
            (waitMillis / 1_000) + errors * 8L
}

@Serializable
data class JourneyContract(
    val id: String,
    val title: String,
    val criticality: JourneyCriticality,
    val starts: Set<JourneyStartContext>,
    val outcome: String,
    val recovery: JourneyRecovery,
    val budget: InteractionCostBudget,
    val checkpoints: List<String>,
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9-]{2,63}")))
        require(title.isNotBlank() && title.length <= 120)
        require(starts.isNotEmpty())
        require(outcome.isNotBlank() && outcome.length <= 240)
        require(checkpoints.isNotEmpty() && checkpoints.all { it.isNotBlank() && it.length <= 120 })
    }

    fun accepts(cost: InteractionCost): Boolean = budget.accepts(cost)
}

@Serializable
data class UserJourneyCatalog(val schemaVersion: Int, val contracts: List<JourneyContract>) {
    init {
        require(schemaVersion == 1)
        require(contracts.isNotEmpty())
        require(contracts.map(JourneyContract::id).toSet().size == contracts.size)
    }

    fun contract(id: String): JourneyContract = contracts.first { it.id == id }
    fun canonicalJson(): String = JSON.encodeToString(this)

    companion object {
        private val JSON = Json {
            encodeDefaults = true
            prettyPrint = true
            explicitNulls = false
        }
        val current = UserJourneyCatalog(
            schemaVersion = 1,
            contracts = listOf(
                JourneyContract(
                    "fresh-start",
                    "Start a first useful session",
                    JourneyCriticality.IMPORTANT,
                    setOf(JourneyStartContext.FRESH_START),
                    "A provider is connected and the user can submit the first request.",
                    JourneyRecovery.RETRY_IN_PLACE,
                    InteractionCostBudget(6, 2, 4, 2, 30_000, 1),
                    listOf("choose-provider", "confirm-connection", "submit-request"),
                ),
                JourneyContract(
                    "attention-resolution",
                    "Resolve one attention item",
                    JourneyCriticality.CRITICAL,
                    setOf(JourneyStartContext.ATTENTION_ITEM),
                    "The pending approval or question is answered with visible result state.",
                    JourneyRecovery.RESTORE_CONTEXT,
                    InteractionCostBudget(4, 1, 0, 1, 15_000, 1),
                    listOf("open-attention", "inspect-context", "answer-item"),
                ),
                JourneyContract(
                    "session-switch",
                    "Switch to a known session",
                    JourneyCriticality.ROUTINE,
                    setOf(JourneyStartContext.SESSION_DETAIL),
                    "The selected session is visible without losing its draft or context.",
                    JourneyRecovery.RESTORE_CONTEXT,
                    InteractionCostBudget(3, 1, 0, 0, 10_000, 1),
                    listOf("open-session-hub", "select-session", "restore-draft"),
                ),
                JourneyContract(
                    "connection-recovery",
                    "Recover a disconnected connection",
                    JourneyCriticality.CRITICAL,
                    setOf(JourneyStartContext.CONNECTION_PROFILE, JourneyStartContext.RECOVERY_NOTICE),
                    "The connection recovers or the user receives a safe actionable failure.",
                    JourneyRecovery.EXPLICIT_REAUTHORIZATION,
                    InteractionCostBudget(5, 2, 2, 1, 45_000, 2),
                    listOf("inspect-status", "retry-connection", "verify-identity"),
                ),
                JourneyContract(
                    "artifact-export",
                    "Export a completed artifact",
                    JourneyCriticality.IMPORTANT,
                    setOf(JourneyStartContext.SESSION_DETAIL),
                    "The selected artifact is saved to the user-approved destination.",
                    JourneyRecovery.SAFE_ABORT,
                    InteractionCostBudget(5, 2, 0, 1, 30_000, 1),
                    listOf("open-artifact", "choose-destination", "confirm-export"),
                ),
            ),
        )
    }
}
