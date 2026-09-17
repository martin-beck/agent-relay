/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import dev.agentrelay.provider.api.AgentSessionState
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionListSearchFilterTest {
    @Test
    fun localProjectionMatchesQueryAndEveryExplicitFilter() {
        val sessions = listOf(
            session("build", "Codex", "office", AgentSessionState.RUNNING, pinned = true, at = 900L),
            session("review", "Aider", "home", AgentSessionState.IDLE, at = 100L),
            session("release", "Codex", "office", AgentSessionState.FAILED, at = 800L),
        )

        assertEquals(
            listOf("build"),
            filterSessionList(
                sessions,
                SessionListSearchFilterState(
                    query = "OFFICE",
                    agent = "Codex",
                    host = "office",
                    state = AgentSessionState.RUNNING,
                    pinnedOnly = true,
                    recency = SessionRecencyFilter.LAST_DAY,
                ),
                nowEpochMillis = 900L,
            ).map(SessionUiModel::stableKey),
        )
    }

    @Test
    fun resetStateReturnsAllSessionsAndRecentUsesLastDayBoundary() {
        val sessions = listOf(
            session("new", "Agent", "host", AgentSessionState.IDLE, at = 86_400_000L),
            session("old", "Agent", "host", AgentSessionState.IDLE, at = -1L),
        )

        assertEquals(2, filterSessionList(sessions, SessionListSearchFilterState(), 86_400_000L).size)
        assertEquals(
            listOf("new"),
            filterSessionList(
                sessions,
                SessionListSearchFilterState(recency = SessionRecencyFilter.LAST_DAY),
                86_400_000L,
            ).map(SessionUiModel::stableKey),
        )
    }

    private fun session(
        key: String,
        agent: String,
        host: String,
        state: AgentSessionState,
        pinned: Boolean = false,
        at: Long,
    ) = SessionUiModel(
        stableKey = key,
        title = UiMessage.Verbatim(key),
        preview = "preview $key",
        connectionLabel = host,
        connectionProviderName = "provider",
        agentProviderLabel = agent,
        projectPath = "/workspace/$key",
        agentState = state,
        unreadCount = 0,
        requiresActionCount = 0,
        lastActivityAtEpochMillis = at,
        isPinned = pinned,
    )
}
