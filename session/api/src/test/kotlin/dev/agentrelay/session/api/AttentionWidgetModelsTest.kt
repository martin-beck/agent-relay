package dev.agentrelay.session.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AttentionWidgetModelsTest {
    private val now = 10_000L

    @Test
    fun rankingPrioritizesUrgencyThenEscalationThenAgeAndId() {
        val ranked = AttentionWidgetRanking.rank(
            listOf(
                item("z-old", AttentionUrgency.NORMAL, AttentionState.OPEN, 1_000),
                item("b-high", AttentionUrgency.HIGH, AttentionState.OPEN, 9_000),
                item("a-high", AttentionUrgency.HIGH, AttentionState.ESCALATED, 9_500),
                item("expired", AttentionUrgency.CRITICAL, AttentionState.OPEN, 1_000, 9_000),
            ),
            now,
        )

        assertEquals(listOf("a-high", "b-high", "z-old"), ranked.map { it.id })
    }

    @Test
    fun compactMediumAndExpandedUseSpaceForIncreasingContext() {
        val snapshot = AttentionWidgetSnapshot(
            7,
            now,
            listOf(
                item("one", AttentionUrgency.CRITICAL, AttentionState.OPEN, 1_000, summary = "safe context"),
                item("two", AttentionUrgency.HIGH, AttentionState.OPEN, 2_000),
                item("three", AttentionUrgency.NORMAL, AttentionState.OPEN, 3_000),
                item("four", AttentionUrgency.LOW, AttentionState.OPEN, 4_000),
            ),
        )

        val compact = snapshot.contentFor(AttentionWidgetSize.COMPACT, AttentionWidgetSurface.HOME_SCREEN, now)
        val medium = snapshot.contentFor(AttentionWidgetSize.MEDIUM, AttentionWidgetSurface.HOME_SCREEN, now)
        val expanded = snapshot.contentFor(AttentionWidgetSize.EXPANDED, AttentionWidgetSurface.HOME_SCREEN, now)

        assertEquals(listOf("one"), compact.entries.map { it.id })
        assertNull(compact.entries.single().ageMillis)
        assertEquals(listOf("one", "two", "three"), medium.entries.map { it.id })
        assertEquals(9_000, medium.entries.first().ageMillis)
        assertEquals(listOf("one", "two", "three", "four"), expanded.entries.map { it.id })
        assertEquals("safe context", expanded.entries.first().summary)
        assertTrue(medium.hasMore)
        assertFalse(expanded.hasMore)
    }

    @Test
    fun lockScreenNeverExposesSummaryOrActions() {
        val content = AttentionWidgetSnapshot(
            revision = 3,
            generatedAtEpochMillis = now,
            items = listOf(item("private", AttentionUrgency.HIGH, AttentionState.OPEN, 1_000, summary = "safe context")),
        ).contentFor(AttentionWidgetSize.EXPANDED, AttentionWidgetSurface.LOCK_SCREEN, now)

        val entry = content.entries.single()
        assertNull(entry.summary)
        assertFalse(entry.canOpen)
        assertFalse(entry.canAcknowledge)
        assertFalse(entry.canDefer)
        assertFalse(entry.canMute)
    }

    @Test
    fun staleAndClosedItemsCannotExposeActions() {
        val stale = item("stale", AttentionUrgency.NORMAL, AttentionState.OPEN, 1_000, 9_000)
        val resolved = item("resolved", AttentionUrgency.HIGH, AttentionState.RESOLVED, 1_000)
        val snapshot = AttentionWidgetSnapshot(4, now, listOf(stale, resolved))
        val content = snapshot.contentFor(AttentionWidgetSize.MEDIUM, AttentionWidgetSurface.HOME_SCREEN, now)

        assertTrue(content.entries.isEmpty())
        assertTrue(content.stale)
    }

    @Test
    fun projectionRanksItemsEvenWhenSnapshotInputIsUnordered() {
        val snapshot = AttentionWidgetSnapshot(
            revision = 5,
            generatedAtEpochMillis = now,
            items = listOf(
                item("low", AttentionUrgency.LOW, AttentionState.OPEN, 1_000),
                item("critical", AttentionUrgency.CRITICAL, AttentionState.OPEN, 2_000),
            ),
        )

        assertEquals(
            listOf("critical", "low"),
            snapshot.contentFor(AttentionWidgetSize.MEDIUM, AttentionWidgetSurface.HOME_SCREEN).entries.map { it.id },
        )
    }

    @Test
    fun protectedMarkersAreRejectedFromWidgetContent() {
        assertFailsWith<IllegalArgumentException> {
            item("protected", AttentionUrgency.HIGH, AttentionState.OPEN, 1_000, summary = "token value")
        }
    }

    private fun item(
        id: String,
        urgency: AttentionUrgency,
        state: AttentionState,
        createdAt: Long,
        expiresAt: Long? = null,
        summary: String? = null,
    ) = AttentionWidgetItem(
        id = id,
        title = "Attention $id",
        summary = summary,
        urgency = urgency,
        state = state,
        createdAtEpochMillis = createdAt,
        expiresAtEpochMillis = expiresAt,
        canOpen = state in setOf(AttentionState.OPEN, AttentionState.ESCALATED, AttentionState.SNOOZED),
        canAcknowledge = state in setOf(AttentionState.OPEN, AttentionState.ESCALATED),
    )
}
