package dev.agentrelay.session.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NormalizedEventModelsTest {
    private val event = NormalizedEvent(
        id = "event-2",
        schemaVersion = 1,
        streamId = "stream-1",
        sequence = 2,
        kind = NormalizedEventKind.COMMAND,
        sensitivity = EventSensitivity.INTERNAL,
        causation = EventCausation("correlation-1", actor = "daemon"),
        payload = mapOf("state" to "accepted"),
    )

    @Test
    fun replayDecisionsAcceptNextAndClassifyDuplicateOrGap() {
        val cursor = EventCursor("stream-1", sequence = 1, eventId = "event-1")
        assertEquals(ReplayDecision.APPLY, event.replayDecision(cursor))
        assertEquals(ReplayDecision.APPLY, event.replayDecision(null))
        assertEquals(ReplayDecision.DUPLICATE, event.copy(sequence = 1).replayDecision(cursor))
        assertEquals(ReplayDecision.GAP, event.copy(sequence = 4).replayDecision(cursor))
    }

    @Test
    fun compatibilityIsKindAndVersionBounded() {
        val compatibility = ProjectionCompatibility(NormalizedEventKind.COMMAND, 1, 2, 3)
        assertTrue(compatibility.accepts(event))
        assertFalse(compatibility.accepts(event.copy(kind = NormalizedEventKind.CHECK)))
        assertFalse(compatibility.accepts(event.copy(schemaVersion = 3)))
    }

    @Test
    fun rejectsSecretsInvalidSequencesAndCrossStreamReplay() {
        assertFailsWith<IllegalStateException> { event.copy(sensitivity = EventSensitivity.SECRET) }
        assertFailsWith<IllegalArgumentException> { event.copy(sequence = 0) }
        assertFailsWith<IllegalArgumentException> {
            event.replayDecision(EventCursor("other-stream", 1, "event-1"))
        }
    }
}
