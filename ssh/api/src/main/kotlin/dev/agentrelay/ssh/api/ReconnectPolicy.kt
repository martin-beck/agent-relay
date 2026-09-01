package dev.agentrelay.ssh.api

import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class SshReconnectPolicy(
    val initialDelay: Duration = 1.seconds,
    val maximumDelay: Duration = 30.seconds,
    val multiplier: Double = 2.0,
    val jitterRatio: Double = 0.2,
    val retryLimit: Int = 8,
) {
    init {
        require(initialDelay.isPositive()) { "Initial reconnect delay must be positive" }
        require(maximumDelay >= initialDelay) { "Maximum reconnect delay must not be shorter than initial delay" }
        require(multiplier >= 1.0) { "Reconnect multiplier must be at least one" }
        require(jitterRatio in 0.0..1.0) { "Reconnect jitter ratio must be between zero and one" }
        require(retryLimit >= 0) { "Reconnect retry limit must not be negative" }
    }

    fun delayForAttempt(attempt: Int, unitRandom: Double = 0.5): Duration {
        require(attempt >= 1) { "Reconnect attempt numbers start at one" }
        require(unitRandom in 0.0..1.0) { "Random input must be between zero and one" }

        val exponentialMillis = initialDelay.inWholeMilliseconds *
            multiplier.pow((attempt - 1).toDouble())
        val boundedMillis = exponentialMillis.coerceAtMost(maximumDelay.inWholeMilliseconds.toDouble())
        val jitterMultiplier = 1.0 + ((unitRandom * 2.0) - 1.0) * jitterRatio
        return (boundedMillis * jitterMultiplier)
            .toLong()
            .coerceAtLeast(1L)
            .milliseconds
    }
}

fun interface SshDelay {
    suspend fun wait(duration: Duration)
}

fun interface SshRandom {
    fun nextUnitDouble(): Double
}

fun interface SshClock {
    fun epochMillis(): Long
}
