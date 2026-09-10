/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticModelsTest {
    private val event = DiagnosticEvent(
        eventId = "event-1", schemaVersion = 1, eventName = "session.opened",
        clock = DiagnosticClock(1_700_000_000_000, 4), traceId = "trace-1", spanId = "span-1",
        correlationId = "corr-1", severity = DiagnosticSeverity.INFO,
        sensitivity = DiagnosticSensitivity.INTERNAL, component = "session", operation = "open",
        outcome = DiagnosticOutcome.SUCCEEDED, attributes = mapOf("state" to "ready", "detail" to "safe"),
    )

    @Test
    fun redactionKeepsOnlyExplicitLowCardinalityAttributes() {
        val redacted = DiagnosticRedactor.redact(event.copy(attributes = mapOf("state" to "ready", "detail" to "safe")), setOf("state"))
        assertEquals(mapOf("state" to "ready"), redacted.attributes)
        assertEquals(DiagnosticExport.REDACTED, redacted.export)
    }

    @Test
    fun schemaCompatibilityIsExplicitAndVersionBounded() {
        val compatibility = DiagnosticCompatibility(1, 2)
        assertTrue(event.acceptsBy(compatibility))
        assertFalse(event.copy(schemaVersion = 3).acceptsBy(compatibility))
        assertFailsWith<IllegalArgumentException> { DiagnosticCompatibility(0, 1) }
    }

    @Test
    fun hostileOrAmbiguousRecordsFailClosed() {
        assertFailsWith<IllegalArgumentException> { event.copy(eventName = "Session Opened") }
        assertFailsWith<IllegalArgumentException> { event.copy(attributes = mapOf("token" to "secret")) }
        assertFailsWith<IllegalArgumentException> { event.copy(outcome = DiagnosticOutcome.FAILED) }
        assertFailsWith<IllegalArgumentException> {
            event.copy(sensitivity = DiagnosticSensitivity.SENSITIVE, export = DiagnosticExport.FULL)
        }
        assertFailsWith<IllegalArgumentException> { event.copy(clock = DiagnosticClock(0, 1)) }
    }

    @Test
    fun stableFailuresAndCausalReferencesAreBounded() {
        val failed = event.copy(
            outcome = DiagnosticOutcome.FAILED,
            failure = DiagnosticFailure("transport", "timeout", retryable = true),
            references = DiagnosticReferences(projectId = "project", workflowId = "workflow", attempt = 2),
        )
        assertTrue(failed.failure!!.retryable)
        assertFailsWith<IllegalArgumentException> { DiagnosticFailure("Transport", "timeout", false) }
        assertFailsWith<IllegalArgumentException> { event.copy(traceId = "trace id") }
    }
}
