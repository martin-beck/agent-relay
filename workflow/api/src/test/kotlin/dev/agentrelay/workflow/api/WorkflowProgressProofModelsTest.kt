/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.workflow.api

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkflowProgressProofModelsTest {
    private val workflowId = WorkflowId("wf_v1_progress-proof")
    private val completion = WorkflowCompletionContract(
        requiredSignals = setOf("result.saved", "checks.passed"),
        failureSignals = setOf("checks.failed"),
    )
    private val policy = WorkflowProgressPolicy(
        staleAfterSeconds = 30,
        evidenceMaxAgeSeconds = 60,
        maxDiagnosisAttempts = 3,
        maxSummaryChars = 80,
    )

    @Test
    fun runningClaimLinksToFreshEvidence() {
        val view = project(listOf(update(1, WorkflowProgressStatus.RUNNING, 1, "heartbeat")), now = 110)

        assertEquals(WorkflowProgressStatus.RUNNING, view.status)
        assertEquals("Running", view.headline)
        assertTrue(view.evidenceFresh)
        assertEquals(listOf(hash('a')), view.evidenceHashes)
        assertNull(view.attention)
        assertNull(view.diagnosis)
    }

    @Test
    fun absenceOfErrorsNeverProvesCompletion() {
        val claimedComplete = update(1, WorkflowProgressStatus.COMPLETE, 3, "heartbeat")
        val view = project(listOf(claimedComplete), now = 110)

        assertEquals(WorkflowProgressStatus.UNCERTAIN, view.status)
        assertEquals("Outcome not proven", view.headline)
        assertEquals(WorkflowProgressAttentionReason.PROOF_UNCERTAIN, view.attention?.reason)
    }

    @Test
    fun freshRequiredSignalsProveCompletionAndFreshFailureProvesFailure() {
        val complete = update(
            sequence = 2,
            status = WorkflowProgressStatus.COMPLETE,
            completed = 3,
            signal = "result.saved",
            evidence = listOf(evidence("result.saved", 'b', 120), evidence("checks.passed", 'c', 120)),
            heartbeat = 120,
        )
        val completedView = project(listOf(update(1, WorkflowProgressStatus.RUNNING, 1, "heartbeat"), complete), 130)
        assertEquals(WorkflowProgressStatus.COMPLETE, completedView.status)
        assertEquals("Complete and verified", completedView.headline)
        assertNull(completedView.attention)

        val failed = update(
            sequence = 2,
            status = WorkflowProgressStatus.FAILED,
            completed = 1,
            signal = "checks.failed",
            heartbeat = 120,
        )
        val failedView = project(listOf(update(1, WorkflowProgressStatus.RUNNING, 1, "heartbeat"), failed), 130)
        assertEquals(WorkflowProgressStatus.FAILED, failedView.status)
        assertEquals(WorkflowProgressAttentionReason.FAILURE_PROVEN, failedView.attention?.reason)
    }

    @Test
    fun staleHeartbeatOpensOneStableAttentionAndBoundedDiagnosis() {
        val running = update(1, WorkflowProgressStatus.RUNNING, 1, "heartbeat")
        val first = project(listOf(running), now = 131)
        val repeated = project(listOf(running), now = 150, completedDiagnosisAttempts = 1)
        val exhausted = project(listOf(running), now = 150, completedDiagnosisAttempts = 3)

        assertEquals(WorkflowProgressStatus.BLOCKED, first.status)
        assertEquals(WorkflowProgressAttentionReason.STALE_HEARTBEAT, first.attention?.reason)
        assertEquals(130, first.attention?.openedAtSeconds)
        assertEquals(first.attention?.id, repeated.attention?.id)
        assertEquals(WorkflowDiagnosisPlan(WorkflowDiagnosisState.START, 1, 3), first.diagnosis)
        assertEquals(WorkflowDiagnosisPlan(WorkflowDiagnosisState.CONTINUE, 2, 3), repeated.diagnosis)
        assertEquals(WorkflowDiagnosisPlan(WorkflowDiagnosisState.EXHAUSTED, 3, 3), exhausted.diagnosis)
    }

    @Test
    fun terminalProofDoesNotBecomeStuckWhenItsHeartbeatAges() {
        val complete = update(
            sequence = 1,
            status = WorkflowProgressStatus.COMPLETE,
            completed = 3,
            signal = "result.saved",
            evidence = listOf(evidence("result.saved", 'b', 100), evidence("checks.passed", 'c', 100)),
        )
        val longEvidencePolicy = policy.copy(evidenceMaxAgeSeconds = 300)
        val view = WorkflowProgressProjector.project(workflowId, completion, longEvidencePolicy, listOf(complete), 150)

        assertEquals(WorkflowProgressStatus.COMPLETE, view.status)
        assertNull(view.attention)
        assertNull(view.diagnosis)
    }

    @Test
    fun staleEvidenceMakesAStatusUncertainAtTheBoundary() {
        val update = update(1, WorkflowProgressStatus.RUNNING, 1, "heartbeat")
        val freshnessOnlyPolicy = policy.copy(staleAfterSeconds = 120)
        val fresh = WorkflowProgressProjector.project(workflowId, completion, freshnessOnlyPolicy, listOf(update), 160)
        assertEquals(WorkflowProgressStatus.RUNNING, fresh.status)

        val stale = WorkflowProgressProjector.project(workflowId, completion, freshnessOnlyPolicy, listOf(update), 161)
        assertEquals(WorkflowProgressStatus.UNCERTAIN, stale.status)
        assertFalse(stale.evidenceFresh)
    }

    @Test
    fun redactsAndBoundsUserVisibleSummary() {
        val secret = "private-host.example"
        val update = update(
            sequence = 1,
            status = WorkflowProgressStatus.RUNNING,
            completed = 1,
            signal = "heartbeat",
            summary = "Connected to $secret with token:abc123 and continuing " + "work ".repeat(30),
            sensitiveValues = setOf(secret),
        )
        val view = project(listOf(update), 110)

        assertFalse(secret in view.summary)
        assertFalse("abc123" in view.summary)
        assertTrue("[REDACTED]" in view.summary)
        assertTrue(view.summary.length <= policy.maxSummaryChars)
        assertTrue(view.summary.endsWith("…"))
    }

    @Test
    fun rejectsInvalidHashesAndNonMonotonicReplay() {
        assertFailsWith<IllegalArgumentException> { WorkflowProgressEvidence("heartbeat", "not-a-hash", 1) }
        val initial = update(1, WorkflowProgressStatus.RUNNING, 2, "heartbeat")
        assertFailsWith<IllegalArgumentException> {
            project(listOf(initial, update(1, WorkflowProgressStatus.RUNNING, 2, "heartbeat", heartbeat = 110)), 120)
        }
        assertFailsWith<IllegalArgumentException> {
            project(listOf(initial, update(2, WorkflowProgressStatus.RUNNING, 1, "heartbeat", heartbeat = 110)), 120)
        }
        assertFailsWith<IllegalArgumentException> {
            project(
                listOf(initial, update(2, WorkflowProgressStatus.RUNNING, 2, "heartbeat", heartbeat = 110, runId = "run_v1_other")),
                120,
            )
        }
    }

    @Test
    fun monotonicProgressPropertyHoldsAcrossDeterministicGeneratedReplays() {
        val random = Random(2163)
        repeat(128) {
            var completed = 0
            var heartbeat = 100L
            val updates = (1L..random.nextLong(2, 24)).map { sequence ->
                completed = (completed + random.nextInt(0, 2)).coerceAtMost(3)
                heartbeat += random.nextLong(0, 4)
                update(sequence, WorkflowProgressStatus.RUNNING, completed, "heartbeat-$sequence", heartbeat = heartbeat)
            }
            val view = project(updates, heartbeat)
            assertEquals(updates.last().completedSteps, view.completedSteps)
            assertEquals(WorkflowProgressStatus.RUNNING, view.status)
            assertTrue(view.evidenceHashes.isNotEmpty())
        }
    }

    @Test
    fun replayFixtureProducesDeterministicUiJourneys() {
        val running = update(1, WorkflowProgressStatus.RUNNING, 1, "heartbeat")
        val blocked = update(2, WorkflowProgressStatus.BLOCKED, 1, "blocked", heartbeat = 120)
        val recovered = update(3, WorkflowProgressStatus.RECOVERED, 2, "recovered", heartbeat = 130)
        val complete = update(
            sequence = 4,
            status = WorkflowProgressStatus.COMPLETE,
            completed = 3,
            signal = "result.saved",
            evidence = listOf(evidence("result.saved", 'b', 140), evidence("checks.passed", 'c', 140)),
            heartbeat = 140,
        )
        val failed = update(2, WorkflowProgressStatus.FAILED, 1, "checks.failed", heartbeat = 120)

        assertEquals("Running", project(listOf(running), 105).headline)
        assertEquals("Needs attention", project(listOf(running, blocked), 125).headline)
        assertEquals("Recovered", project(listOf(running, blocked, recovered), 135).headline)
        assertEquals("Complete and verified", project(listOf(running, blocked, recovered, complete), 145).headline)
        assertEquals("Failed with evidence", project(listOf(running, failed), 125).headline)
        assertNotNull(project(listOf(running, blocked), 125).attention)
    }

    private fun project(
        updates: List<WorkflowProgressUpdate>,
        now: Long,
        completedDiagnosisAttempts: Int = 0,
    ): WorkflowProgressView = WorkflowProgressProjector.project(
        workflowId,
        completion,
        policy,
        updates,
        now,
        completedDiagnosisAttempts,
    )

    private fun update(
        sequence: Long,
        status: WorkflowProgressStatus,
        completed: Int,
        signal: String,
        heartbeat: Long = 100,
        evidence: List<WorkflowProgressEvidence> = listOf(evidence(signal, 'a', heartbeat)),
        summary: String = "$status at step $completed",
        sensitiveValues: Set<String> = emptySet(),
        runId: String = "run_v1_proof",
    ): WorkflowProgressUpdate = WorkflowProgressUpdate(
        runId = runId,
        sequence = sequence,
        status = status,
        completedSteps = completed,
        totalSteps = 3,
        heartbeatAtSeconds = heartbeat,
        evidence = evidence,
        summary = summary,
        sensitiveValues = sensitiveValues,
    )

    private fun evidence(signal: String, character: Char, recordedAt: Long): WorkflowProgressEvidence =
        WorkflowProgressEvidence(signal, hash(character), recordedAt)

    private fun hash(character: Char): String = character.toString().repeat(64)
}
