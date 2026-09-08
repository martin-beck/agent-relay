package com.example.agentrelay.widgets

import dev.agentrelay.session.api.AttentionUrgency
import dev.agentrelay.session.api.AttentionWidgetContent
import dev.agentrelay.session.api.AttentionWidgetEntry
import dev.agentrelay.session.api.AttentionWidgetSize
import dev.agentrelay.session.api.AttentionWidgetSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LockScreenAttentionWidgetProviderTest {
    @Test
    fun emptyStateIsExplicitAndHidesProtectedSummary() {
        val state = LockScreenAttentionWidgetRenderer.state(
            null,
            "No attention needed",
            "Agent Relay",
            "Needs attention",
            "Refresh",
        )
        assertEquals("No attention needed", state.title)
        assertEquals("Agent Relay", state.status)
    }

    @Test
    fun lockScreenRendererShowsOnlyRedactedTitleAndStatus() {
        val content = AttentionWidgetContent(
            revision = 3,
            size = AttentionWidgetSize.EXPANDED,
            surface = AttentionWidgetSurface.LOCK_SCREEN,
            entries = listOf(
                AttentionWidgetEntry(
                    id = "attention-1",
                    title = "Approve a safe action",
                    summary = null,
                    urgency = AttentionUrgency.HIGH,
                    ageMillis = 4_000,
                    canOpen = false,
                    canAcknowledge = false,
                    canDefer = false,
                    canMute = false,
                ),
            ),
            hasMore = false,
            stale = false,
        )

        val state = LockScreenAttentionWidgetRenderer.state(
            content,
            "No attention needed",
            "Agent Relay",
            "Needs attention",
            "Refresh",
        )
        assertEquals("Approve a safe action", state.title)
        assertEquals("Needs attention", state.status)
        assertNull(content.entries.single().summary)
        assertEquals(false, content.entries.single().canOpen)
        assertEquals(false, content.entries.single().canAcknowledge)
        assertEquals(false, content.entries.single().canDefer)
        assertEquals(false, content.entries.single().canMute)
    }

    @Test
    fun loadingAndErrorNeverExposeAPreviouslyVisibleTitle() {
        val content = AttentionWidgetContent(
            revision = 3,
            size = AttentionWidgetSize.EXPANDED,
            surface = AttentionWidgetSurface.LOCK_SCREEN,
            entries = listOf(
                AttentionWidgetEntry(
                    id = "attention-1",
                    title = "Previously visible attention",
                    summary = "Private upstream context",
                    urgency = AttentionUrgency.HIGH,
                    ageMillis = 4_000,
                    canOpen = true,
                    canAcknowledge = true,
                    canDefer = true,
                    canMute = true,
                ),
            ),
            hasMore = false,
            stale = false,
        )

        val loading = state(content, AttentionWidgetRenderPhase.LOADING)
        val error = state(content, AttentionWidgetRenderPhase.ERROR)

        assertEquals("Checking attention", loading.title)
        assertEquals("Agent Relay", loading.status)
        assertEquals("Attention unavailable", error.title)
        assertEquals("Refresh", error.status)
    }

    private fun state(
        content: AttentionWidgetContent,
        phase: AttentionWidgetRenderPhase,
    ) = LockScreenAttentionWidgetRenderer.state(
        content = content,
        noAttention = "No attention needed",
        quiet = "Agent Relay",
        attention = "Needs attention",
        refresh = "Refresh",
        loading = "Checking attention",
        error = "Attention unavailable",
        phase = phase,
    )
}
