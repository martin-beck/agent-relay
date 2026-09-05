package dev.agentrelay.connection.api

/** Named, reproducible fault families used by local connectivity tests. */
enum class ConnectivityFaultScenarioName {
    DELAY,
    RESET,
    TRUNCATION,
    PARTITION,
    PATH_SWITCHING,
}

enum class ConnectivityFaultAction {
    DELAY,
    RESET,
    TRUNCATE,
    PARTITION,
    SWITCH_PATH,
}

data class ConnectivityFaultEvent(
    val sequence: Int,
    val action: ConnectivityFaultAction,
    val value: Int,
) {
    init {
        require(sequence >= 1)
        require(value >= 0)
    }
}

/**
 * A bounded fault plan. The generator is deliberately pure: replaying a plan cannot
 * perform I/O, expose endpoint data, or mutate connection state.
 */
data class ConnectivityFaultScenario(
    val name: ConnectivityFaultScenarioName,
    val seed: Long,
    val steps: Int = DEFAULT_FAULT_SCENARIO_STEPS,
) {
    init {
        require(seed >= 0) { "Fault scenario seed must be non-negative" }
        require(steps in 1..MAX_FAULT_SCENARIO_STEPS) { "Fault scenario is too large" }
    }

    fun replay(): List<ConnectivityFaultEvent> {
        var state = seed xor name.ordinal.toLong()
        return (1..steps).map { sequence ->
            state = nextState(state)
            ConnectivityFaultEvent(sequence, name.action(), valueFor(name, state))
        }
    }

    private fun nextState(value: Long): Long {
        var next = value + LCG_INCREMENT
        next = next xor (next ushr 30)
        next *= LCG_MULTIPLIER
        next = next xor (next ushr 27)
        next *= LCG_MULTIPLIER_2
        return next xor (next ushr 31)
    }

    private fun valueFor(
        scenario: ConnectivityFaultScenarioName,
        state: Long,
    ): Int = when (scenario) {
        ConnectivityFaultScenarioName.DELAY -> bounded(state, MAX_DELAY_MILLIS) + 1
        ConnectivityFaultScenarioName.RESET,
        ConnectivityFaultScenarioName.PARTITION,
        ConnectivityFaultScenarioName.PATH_SWITCHING,
        -> 0
        ConnectivityFaultScenarioName.TRUNCATION -> bounded(state, MAX_TRUNCATED_BYTES) + 1
    }

    private fun ConnectivityFaultScenarioName.action(): ConnectivityFaultAction = when (this) {
        ConnectivityFaultScenarioName.DELAY -> ConnectivityFaultAction.DELAY
        ConnectivityFaultScenarioName.RESET -> ConnectivityFaultAction.RESET
        ConnectivityFaultScenarioName.TRUNCATION -> ConnectivityFaultAction.TRUNCATE
        ConnectivityFaultScenarioName.PARTITION -> ConnectivityFaultAction.PARTITION
        ConnectivityFaultScenarioName.PATH_SWITCHING -> ConnectivityFaultAction.SWITCH_PATH
    }

    private fun bounded(value: Long, bound: Int): Int =
        ((value xor (value ushr 32)) and Long.MAX_VALUE).mod(bound)
}

private const val DEFAULT_FAULT_SCENARIO_STEPS = 8
private const val MAX_FAULT_SCENARIO_STEPS = 64
private const val MAX_DELAY_MILLIS = 500
private const val MAX_TRUNCATED_BYTES = 4096
private const val LCG_INCREMENT = -7046029254386353131L
private const val LCG_MULTIPLIER = -4658895280553007687L
private const val LCG_MULTIPLIER_2 = -7723592293110705685L
