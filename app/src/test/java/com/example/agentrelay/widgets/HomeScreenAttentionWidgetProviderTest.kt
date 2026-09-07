package com.example.agentrelay.widgets

import dev.agentrelay.session.api.AttentionUrgency
import dev.agentrelay.session.api.AttentionWidgetAction
import dev.agentrelay.session.api.AttentionWidgetContent
import dev.agentrelay.session.api.AttentionWidgetEntry
import dev.agentrelay.session.api.AttentionWidgetSize
import dev.agentrelay.session.api.AttentionWidgetSurface
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeScreenAttentionWidgetProviderTest {
    @Test
    fun sizeControlsHowManyRankedItemsAreProjected() {
        val content = AttentionWidgetContent(
            revision = 4,
            size = AttentionWidgetSize.EXPANDED,
            surface = AttentionWidgetSurface.HOME_SCREEN,
            entries = (1..4).map { index ->
                AttentionWidgetEntry(
                    id = "item-$index",
                    title = "Attention $index",
                    summary = "Context $index",
                    urgency = AttentionUrgency.HIGH,
                    ageMillis = index.toLong(),
                    canOpen = true,
                    canAcknowledge = true,
                    canDefer = true,
                    canMute = true,
                )
            },
            hasMore = true,
            stale = false,
        )
        fun project(size: AttentionWidgetSize) = HomeScreenAttentionWidgetRenderer.state(
            content,
            size,
            "No attention",
            "Agent Relay",
            "Needs attention",
            "Refresh",
        )

        assertEquals(listOf("Attention 1"), project(AttentionWidgetSize.COMPACT).items)
        assertEquals(listOf("Attention 1", "Attention 2"), project(AttentionWidgetSize.MEDIUM).items)
        assertEquals(
            listOf("Attention 1", "Attention 2", "Attention 3"),
            project(AttentionWidgetSize.EXPANDED).items,
        )
        assertEquals(setOf(AttentionWidgetAction.OPEN_DETAILS), project(AttentionWidgetSize.COMPACT).actions)
        assertEquals(
            setOf(
                AttentionWidgetAction.OPEN_DETAILS,
                AttentionWidgetAction.ACKNOWLEDGE,
                AttentionWidgetAction.DEFER,
            ),
            project(AttentionWidgetSize.MEDIUM).actions,
        )
        assertEquals(AttentionWidgetAction.entries.toSet(), project(AttentionWidgetSize.EXPANDED).actions)
    }

    @Test
    fun loadingAndErrorNeverExposeStaleEntriesOrActions() {
        val content = content()

        listOf(AttentionWidgetRenderPhase.LOADING, AttentionWidgetRenderPhase.ERROR).forEach { phase ->
            val state = HomeScreenAttentionWidgetRenderer.state(
                content = content,
                size = AttentionWidgetSize.EXPANDED,
                noAttention = "No attention",
                quiet = "Agent Relay",
                attention = "Needs attention",
                refresh = "Refresh",
                loading = "Checking attention",
                error = "Attention unavailable",
                phase = phase,
            )

            assertEquals(emptyList<String>(), state.items)
            assertEquals(emptySet<AttentionWidgetAction>(), state.actions)
            assertEquals(null, state.primaryEntry)
        }
    }

    private fun content() = AttentionWidgetContent(
        revision = 4,
        size = AttentionWidgetSize.EXPANDED,
        surface = AttentionWidgetSurface.HOME_SCREEN,
        entries = listOf(
            AttentionWidgetEntry(
                id = "item-1",
                title = "Stale attention",
                summary = "Stale context",
                urgency = AttentionUrgency.HIGH,
                ageMillis = 1,
                canOpen = true,
                canAcknowledge = true,
                canDefer = true,
                canMute = true,
            ),
        ),
        hasMore = false,
        stale = false,
    )
}
