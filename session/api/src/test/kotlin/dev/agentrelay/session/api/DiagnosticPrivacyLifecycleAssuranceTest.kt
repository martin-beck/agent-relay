/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Synthetic lifecycle proof; raw prompts, routes and credentials never enter the fixture. */
class DiagnosticPrivacyLifecycleAssuranceTest {
    private val event = DiagnosticEvent(
        eventId = "event-privacy-1",
        schemaVersion = 1,
        eventName = "diagnostic.observed",
        clock = DiagnosticClock(1_000, 1),
        traceId = "trace-privacy",
        spanId = "span-privacy",
        correlationId = "corr-privacy",
        severity = DiagnosticSeverity.INFO,
        sensitivity = DiagnosticSensitivity.INTERNAL,
        component = "diagnostic",
        operation = "capture",
        outcome = DiagnosticOutcome.SUCCEEDED,
        attributes = mapOf("state" to "ready", "detail" to "synthetic"),
    )

    @Test
    fun authorizationRedactionExpiryAndDeletionRemainObservable() {
        val store = InMemoryDiagnosticCaptureStore(
            DiagnosticCapturePolicy(maxBytes = 2_000, maxRecords = 4, maxAgeMillis = 100, maxSessionMillis = 50),
            allowedAttributes = setOf("state"),
        )
        val id = DiagnosticCaptureSessionId("privacy-session")
        assertNull(store.append(id, event, 1_000))
        store.beginSession(id, DiagnosticCaptureScope("diagnostic", DiagnosticCaptureSensitivity.INTERNAL, 50, 2_000, true), 1_000)
        val record = assertNotNull(store.append(id, event, 1_001))
        assertEquals(mapOf("state" to "ready"), record.event.attributes)
        assertNull(store.append(id, event.copy(sensitivity = DiagnosticSensitivity.SENSITIVE), 1_002))
        assertEquals(2, store.query(DiagnosticCaptureQuery(), 1_003).counters.rejected)
        assertEquals(1, store.retain(1_200).overwritten)
        assertEquals(0, store.query(DiagnosticCaptureQuery(), 1_200).records.size)
        store.endSession(id)
        assertEquals(0, store.sessions().size)
    }
}
