/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.diagnostics

enum class DiagnosticIncidentKind {
    CONNECTION,
    PROVIDER,
    PROCESS_DEATH,
    JOURNAL,
    CONCURRENCY,
    POLICY,
    UNCERTAIN_EFFECT,
    STALE_EVIDENCE,
}

enum class DiagnosticRecommendation {
    ATTENTION,
    READ_ONLY_CHECK,
    REPLAY,
    REPAIR,
}

data class DiagnosticPolicy(
    val maxEvents: Int = 64,
    val maxReplayAttempts: Int = 2,
    val allowRepair: Boolean = false,
) {
    init {
        require(maxEvents in 1..256) { "Diagnostic event budget must be bounded" }
        require(maxReplayAttempts in 0..3) { "Diagnostic replay budget must be bounded" }
    }
}

data class DiagnosticIncident(
    val id: String,
    val kind: DiagnosticIncidentKind,
    val symptom: String,
    val eventIds: List<String>,
    val evidenceFresh: Boolean,
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_.-]{1,63}"))) { "Incident id is unstable" }
        require(symptom.isNotBlank()) { "Incident symptom is required" }
        require(eventIds.all { it.matches(Regex("[a-zA-Z0-9_.:-]{1,80}")) }) {
            "Incident event ids must be redacted stable identifiers"
        }
    }
}

data class DiagnosticAssessment(
    val incidentId: String,
    val kind: DiagnosticIncidentKind,
    val consideredEventIds: List<String>,
    val replayAttempts: Int,
    val confidence: Double,
    val missingEvidence: List<String>,
    val recommendation: DiagnosticRecommendation,
)

object DiagnosticTriage {
    fun assess(
        incident: DiagnosticIncident,
        policy: DiagnosticPolicy = DiagnosticPolicy(),
    ): DiagnosticAssessment {
        val considered = incident.eventIds.take(policy.maxEvents)
        val missing = buildList {
            if (considered.isEmpty()) add("typed event evidence")
            if (!incident.evidenceFresh) add("fresh bounded evidence")
        }
        val replayAttempts = if (missing.isEmpty()) policy.maxReplayAttempts else 0
        val recommendation = when {
            !incident.evidenceFresh -> DiagnosticRecommendation.ATTENTION
            considered.isEmpty() -> DiagnosticRecommendation.READ_ONLY_CHECK
            replayAttempts > 0 -> DiagnosticRecommendation.REPLAY
            else -> DiagnosticRecommendation.ATTENTION
        }
        return DiagnosticAssessment(
            incidentId = incident.id,
            kind = incident.kind,
            consideredEventIds = considered,
            replayAttempts = replayAttempts,
            confidence = confidence(incident, considered, missing),
            missingEvidence = missing,
            recommendation = if (recommendation == DiagnosticRecommendation.REPAIR && !policy.allowRepair) {
                DiagnosticRecommendation.ATTENTION
            } else {
                recommendation
            },
        )
    }

    private fun confidence(
        incident: DiagnosticIncident,
        considered: List<String>,
        missing: List<String>,
    ): Double {
        if (missing.isNotEmpty()) return 0.0
        val kindWeight = if (incident.kind == DiagnosticIncidentKind.STALE_EVIDENCE) 0.5 else 1.0
        return (kindWeight * considered.size / (considered.size + 1.0)).coerceIn(0.0, 0.99)
    }
}
