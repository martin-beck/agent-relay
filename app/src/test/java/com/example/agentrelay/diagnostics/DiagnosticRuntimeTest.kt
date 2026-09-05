package com.example.agentrelay.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DiagnosticRuntimeTest {
    @Test
    fun `diagnostic mode permits explicitly expiring coroutine dumps`() {
        val config = DiagnosticRuntimeConfig(AndroidBuildMode.DIAGNOSTIC, false, true, true)

        assertTrue(config.allowsCoroutineDump(explicitOptIn = true, nowMillis = 99, expiresAtMillis = 100))
        assertFalse(config.allowsCoroutineDump(explicitOptIn = false, nowMillis = 99, expiresAtMillis = 100))
        assertFalse(config.allowsCoroutineDump(explicitOptIn = true, nowMillis = 100, expiresAtMillis = 100))
    }

    @Test
    fun `release-like modes disable runtime capture and dumps`() {
        val config = DiagnosticRuntimeConfig(AndroidBuildMode.PROFILEABLE, true, false, false)

        assertFalse(config.runtimeProbes)
        assertFalse(config.allowsCoroutineDump(explicitOptIn = true, nowMillis = 1, expiresAtMillis = 2))
    }

    @Test
    fun `trace section names are stable`() {
        val config = DiagnosticRuntimeConfig(AndroidBuildMode.DEBUG, false, true, true)

        try {
            DiagnosticTrace.section(config, "raw secret") { Unit }
            fail("Expected unstable trace section to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
