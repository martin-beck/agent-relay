package com.example.agentrelay.ui.main

import dev.agentrelay.provider.api.AgentSessionState
import org.junit.Assert.assertEquals
import org.junit.Test

class AttentionSurfaceBucketsTest {
    @Test
    fun `prioritizes attention then running then changed then recent`() {
        val sessions = listOf(
            session("attention", AgentSessionState.IDLE, unread = 2, required = 1),
            session("waiting", AgentSessionState.WAITING_FOR_APPROVAL),
            session("running", AgentSessionState.RUNNING, unread = 4),
            session("changed", AgentSessionState.IDLE, unread = 1),
            session("recent", AgentSessionState.IDLE),
        )

        val buckets = attentionSurfaceBuckets(sessions)

        assertEquals(listOf("attention", "waiting"), buckets.needsAttention.map { it.preview })
        assertEquals(listOf("running"), buckets.running.map { it.preview })
        assertEquals(listOf("changed"), buckets.changed.map { it.preview })
        assertEquals(listOf("recent"), buckets.recentlyCompleted.map { it.preview })
    }

    @Test
    fun `bucket partition preserves order and every session exactly once`() {
        val sessions = listOf(
            session("one", AgentSessionState.RUNNING),
            session("two", AgentSessionState.IDLE, unread = 1),
            session("three", AgentSessionState.IDLE),
        )
        val buckets = attentionSurfaceBuckets(sessions)
        val actual = buckets.needsAttention + buckets.changed + buckets.running + buckets.recentlyCompleted

        assertEquals(sessions.map { it.preview }.toSet(), actual.map { it.preview }.toSet())
        assertEquals(sessions.size, actual.size)
    }

    private fun session(
        preview: String,
        state: AgentSessionState,
        unread: Int = 0,
        required: Int = 0,
    ) = SessionUiModel(
        stableKey = preview,
        title = UiMessage.Verbatim(preview),
        preview = preview,
        connectionLabel = "connection",
        connectionProviderName = "provider",
        agentProviderLabel = "agent",
        projectPath = null,
        agentState = state,
        unreadCount = unread,
        requiresActionCount = required,
        lastActivityAtEpochMillis = null,
        isPinned = false,
    )
}
