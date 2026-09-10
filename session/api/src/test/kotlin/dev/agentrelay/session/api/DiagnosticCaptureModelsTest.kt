/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiagnosticCaptureModelsTest {
    private val policy = DiagnosticCapturePolicy(
        maxBytes = 2_000,
        maxRecords = 2,
        maxAgeMillis = 1_000,
        maxSessionMillis = 500,
    )
    private val event = DiagnosticEvent(
        eventId = "event-1", schemaVersion = 1, eventName = "workflow.started",
        clock = DiagnosticClock(1_000, 1), traceId = "trace-1", spanId = "span-1", correlationId = "corr-1",
        severity = DiagnosticSeverity.INFO, sensitivity = DiagnosticSensitivity.INTERNAL,
        component = "workflow", operation = "run", outcome = DiagnosticOutcome.STARTED,
        references = DiagnosticReferences(workflowId = "wf", runId = "run", stepId = "step", attempt = 1),
        attributes = mapOf("state" to "running"),
    )

    private fun store() = InMemoryDiagnosticCaptureStore(policy, setOf("state"))

    @Test fun authorizationAndComponentScopeAreRequired() {
        val store = store()
        assertNull(store.append(DiagnosticCaptureSessionId("s"), event, 100))
        store.beginSession(
            DiagnosticCaptureSessionId("s"),
            DiagnosticCaptureScope("workflow", DiagnosticCaptureSensitivity.INTERNAL, 200, 500, true),
            100,
        )
        assertNull(store.append(DiagnosticCaptureSessionId("s"), event.copy(component = "session"), 101))
    }

    @Test fun appendRedactsAndQueriesByCursorAndReferences() {
        val store = store()
        val id = DiagnosticCaptureSessionId("s")
        store.beginSession(
            id,
            DiagnosticCaptureScope("workflow", DiagnosticCaptureSensitivity.INTERNAL, 200, 500, true),
            100,
        )
        val first = assertNotNull(store.append(id, event.copy(attributes = mapOf("state" to "running", "detail" to "private")), 101))
        assertEquals(mapOf("state" to "running"), first.event.attributes)
        store.append(id, event.copy(eventId = "event-2", clock = DiagnosticClock(102, 2)), 102)
        val result = store.query(
            DiagnosticCaptureQuery(
                workflowId = "wf",
                cursor = DiagnosticCaptureCursor(first.sequence, first.event.eventId),
            ),
            102,
        )
        assertEquals(listOf("event-2"), result.records.map { it.event.eventId })
    }

    @Test fun boundsRetentionAndCorruptTailRecoveryAreObservable() {
        val store = store()
        val id = DiagnosticCaptureSessionId("s")
        store.beginSession(id, DiagnosticCaptureScope("workflow", DiagnosticCaptureSensitivity.INTERNAL, 500, 500, true), 100)
        store.append(id, event, 101)
        store.append(id, event.copy(eventId = "event-2", clock = DiagnosticClock(102, 2)), 102)
        store.append(id, event.copy(eventId = "event-3", clock = DiagnosticClock(103, 3)), 103)
        assertEquals(2, store.query(DiagnosticCaptureQuery(), 103).records.size)
        assertTrue(store.query(DiagnosticCaptureQuery(), 2_000).records.isEmpty())
        assertEquals(0, store.recover().rejected)
    }
}
