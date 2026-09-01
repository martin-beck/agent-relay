package dev.agentrelay.ssh.api

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

class SshReconnectPolicyTest {
    private val policy = SshReconnectPolicy(
        initialDelay = 1.seconds,
        maximumDelay = 10.seconds,
        multiplier = 2.0,
        jitterRatio = 0.2,
        retryLimit = 8,
    )

    @Test
    fun delayIsExponentialAndBounded() {
        assertEquals(1.seconds, policy.delayForAttempt(1))
        assertEquals(2.seconds, policy.delayForAttempt(2))
        assertEquals(8.seconds, policy.delayForAttempt(4))
        assertEquals(10.seconds, policy.delayForAttempt(8))
    }

    @Test
    fun jitterStaysInsideConfiguredWindow() {
        assertEquals(800L, policy.delayForAttempt(1, 0.0).inWholeMilliseconds)
        assertEquals(1200L, policy.delayForAttempt(1, 1.0).inWholeMilliseconds)
        assertEquals(8000L, policy.delayForAttempt(8, 0.0).inWholeMilliseconds)
        assertEquals(12000L, policy.delayForAttempt(8, 1.0).inWholeMilliseconds)
    }

    @Test
    fun invalidPoliciesAndAttemptsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            SshReconnectPolicy(initialDelay = 2.seconds, maximumDelay = 1.seconds)
        }
        assertFailsWith<IllegalArgumentException> {
            SshReconnectPolicy(jitterRatio = 1.1)
        }
        assertFailsWith<IllegalArgumentException> {
            policy.delayForAttempt(0)
        }
    }
}
