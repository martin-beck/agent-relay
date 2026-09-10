/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticTriageTest {
    @Test
    fun `fresh typed connection evidence is bounded and replayable`() {
        val assessment = DiagnosticTriage.assess(
            DiagnosticIncident(
                id = "connection-timeout",
                kind = DiagnosticIncidentKind.CONNECTION,
                symptom = "typed handshake timeout",
                eventIds = listOf("connect:1", "probe:2", "retry:3"),
                evidenceFresh = true,
            ),
            DiagnosticPolicy(maxEvents = 2, maxReplayAttempts = 1),
        )

        assertEquals(listOf("connect:1", "probe:2"), assessment.consideredEventIds)
        assertEquals(1, assessment.replayAttempts)
        assertEquals(DiagnosticRecommendation.REPLAY, assessment.recommendation)
        assertTrue(assessment.confidence > 0.5)
    }

    @Test
    fun `stale evidence cannot authorize replay or repair`() {
        val assessment = DiagnosticTriage.assess(
            DiagnosticIncident(
                id = "stale-journal",
                kind = DiagnosticIncidentKind.STALE_EVIDENCE,
                symptom = "journal snapshot expired",
                eventIds = listOf("journal:9"),
                evidenceFresh = false,
            ),
            DiagnosticPolicy(maxReplayAttempts = 3, allowRepair = true),
        )

        assertEquals(0, assessment.replayAttempts)
        assertEquals(DiagnosticRecommendation.ATTENTION, assessment.recommendation)
        assertEquals(listOf("fresh bounded evidence"), assessment.missingEvidence)
        assertEquals(0.0, assessment.confidence, 0.0)
    }

    @Test
    fun `empty typed evidence becomes a read only check`() {
        val assessment = DiagnosticTriage.assess(
            DiagnosticIncident(
                id = "provider-gap",
                kind = DiagnosticIncidentKind.PROVIDER,
                symptom = "provider capability unavailable",
                eventIds = emptyList(),
                evidenceFresh = true,
            ),
        )

        assertEquals(DiagnosticRecommendation.READ_ONLY_CHECK, assessment.recommendation)
        assertEquals(listOf("typed event evidence"), assessment.missingEvidence)
        assertEquals(0, assessment.replayAttempts)
    }
}
