package com.example.agentrelay.visual

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegressionHarnessContractsTest {
    private val observation = RegressionObservation(
        journeyId = "attention-resolution",
        viewport = ViewportClass.COMPACT,
        textScale = TextScale.LARGE,
        navigationSteps = 3,
        scrolls = 1,
        confirmations = 1,
        clippedElements = 0,
        overlappingElements = 0,
        truncatedLabels = 0,
        missingContentDescriptions = 0,
        undersizedTargets = 0,
        startupMillis = 800,
        interactionMillis = 300,
        primaryActionVisible = true,
    )

    @Test
    fun `snapshot round trips as a redacted machine-readable contract`() {
        val json = Json { encodeDefaults = true }

        assertEquals(observation, json.decodeFromString<RegressionObservation>(json.encodeToString(observation)))
    }

    @Test
    fun `evaluation returns every failed dimension`() {
        val result = RegressionBudgets(3, 1, 1).evaluate(observation.copy(scrolls = 2, truncatedLabels = 1))

        assertFalse(result.passed)
        assertEquals(listOf("scrolls", "truncated-labels"), result.failures)
    }

    @Test
    fun `accessibility and primary action failures are not hidden by latency budget`() {
        val result = RegressionBudgets(3, 1, 1).evaluate(
            observation.copy(missingContentDescriptions = 1, primaryActionVisible = false),
        )

        assertFalse(result.passed)
        assertTrue("missing-content-descriptions" in result.failures)
        assertTrue("primary-action-hidden" in result.failures)
    }
}
