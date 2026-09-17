/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import dev.agentrelay.provider.api.AgentSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainScreenSurfaceTest {
    @Test
    fun pinnedSurfaceOnlyExposesPinnedSessionsAndClearsUnpinnedSelection() {
        val hub = hubWithSessions()

        val visible = visibleHubForSurface(hub, MainScreenSurface.PINNED_SESSIONS)

        assertEquals(listOf("pinned"), visible.sessions.map(SessionUiModel::stableKey))
        assertNull(visible.selectedSession)
        assertNull(visible.selectedSessionKey)
    }

    @Test
    fun newSessionSurfaceHidesExistingSessionSelection() {
        val hub = hubWithSessions()

        val visible = visibleHubForSurface(hub, MainScreenSurface.NEW_SESSION)

        assertEquals(emptyList<SessionUiModel>(), visible.sessions)
        assertNull(visible.selectedSession)
    }

    private fun hubWithSessions(): SessionHubUiModel {
        fun session(key: String, pinned: Boolean) = SessionUiModel(
            stableKey = key,
            title = UiMessage.Verbatim(key),
            preview = "preview",
            connectionLabel = "connection",
            connectionProviderName = "provider",
            agentProviderLabel = "agent",
            projectPath = null,
            agentState = AgentSessionState.IDLE,
            unreadCount = 0,
            requiresActionCount = 0,
            lastActivityAtEpochMillis = 1L,
            isPinned = pinned,
        )
        return SessionHubUiModel(
            availableConnectionProviders = emptyList(),
            connections = emptyList(),
            sessions = listOf(session("pinned", true), session("regular", false)),
            issues = emptyList(),
            selectedSession = null,
            selectedSessionKey = "regular",
            operationError = null,
            isRefreshingProfiles = false,
        )
    }
}
