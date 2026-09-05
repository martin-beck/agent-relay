package com.example.agentrelay.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTokensTest {
    @Test
    fun `spacing keeps progressive hierarchy`() {
        val spacing = AgentRelayThemeTokens().spacing

        assertEquals(0f, spacing.none.value)
        assertTrue(spacing.compact < spacing.standard)
        assertTrue(spacing.standard < spacing.spacious)
        assertTrue(spacing.spacious < spacing.section)
    }

    @Test
    fun `motion and touch target remain accessible`() {
        val tokens = AgentRelayThemeTokens()

        assertTrue(tokens.minimumTouchTarget.value >= 48f)
        assertTrue(tokens.motion.shortMillis in 1..tokens.motion.standardMillis)
    }
}
