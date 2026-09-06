package dev.agentrelay.session.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkflowReplayModelsTest {
    private val history = ReplayHistory(
        ReplayHistoryId("history-1"),
        1,
        WorkflowId("workflow-1"),
        listOf(
            ReplayEvent(0, "event-0", ReplayBoundary.WORKFLOW, "digest-0"),
            ReplayEvent(1, "event-1", ReplayBoundary.EFFECT, "digest-1", externalEffect = true),
            ReplayEvent(2, "event-2", ReplayBoundary.OUTCOME, "digest-2"),
        ),
    )

    @Test
    fun compatibilityReportsSmallestUnsupportedBoundary() {
        val result = WorkflowReplay.from(history).compatibility(1, setOf(ReplayBoundary.WORKFLOW))
        assertEquals(false, result.compatible)
        assertEquals(ReplayBoundary.EFFECT, result.boundary)
        assertEquals("unsupported-boundary:event-1", result.reason)
    }

    @Test
    fun replayIsBoundedAndForksAreSeparateSyntheticRuns() {
        val replay = WorkflowReplay.from(history)
        assertEquals(listOf("event-0", "event-1"), replay.replayedEvents(1).map { it.eventId })
        val fork = replay.fork(1, ReplayForkId("fork-1"), seed = 17, mapOf("outcome" to "timeout"))
        assertEquals(ReplayHistoryId("history-1"), fork.source)
        assertEquals(mapOf("outcome" to "timeout"), fork.syntheticObservations)
    }

    @Test
    fun malformedAndTruncatedHistoriesFailClosed() {
        assertFailsWith<IllegalArgumentException> {
            ReplayHistory(
                ReplayHistoryId("history-2"),
                1,
                WorkflowId("workflow-1"),
                listOf(ReplayEvent(1, "event-1", ReplayBoundary.WORKFLOW, "digest-1")),
            )
        }
        assertFailsWith<IllegalArgumentException> { WorkflowReplay.from(history).fork(9, ReplayForkId("fork-2"), 1) }
    }
}
