package com.example.agentrelay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundConnectionLifetimeTest {
    @Test
    fun onlyBackgroundWithoutExplicitTransportSuspendsProviders() {
        assertFalse(
            shouldSuspendProviderConnections(
                appForeground = true,
                backgroundTransportActive = false,
            ),
        )
        assertFalse(
            shouldSuspendProviderConnections(
                appForeground = true,
                backgroundTransportActive = true,
            ),
        )
        assertFalse(
            shouldSuspendProviderConnections(
                appForeground = false,
                backgroundTransportActive = true,
            ),
        )
        assertTrue(
            shouldSuspendProviderConnections(
                appForeground = false,
                backgroundTransportActive = false,
            ),
        )
    }
}
