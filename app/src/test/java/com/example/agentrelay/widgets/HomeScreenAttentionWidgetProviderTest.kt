package com.example.agentrelay.widgets

import dev.agentrelay.session.api.AttentionUrgency
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
    }
}
