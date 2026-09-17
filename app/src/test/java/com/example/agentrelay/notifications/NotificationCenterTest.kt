/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.notifications

import dev.agentrelay.session.api.SessionActivity
import dev.agentrelay.session.api.SessionActivitySummary
import dev.agentrelay.session.api.SessionActivityType
import dev.agentrelay.session.api.SessionLocator
import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSessionId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCenterTest {
    @Test
    fun filtersUnreadByTopicAndKeepsActionableItemsFirst() {
        val state = NotificationCenterState().reduce(
            NotificationCenterAction.Replace(
                listOf(
                    activity(SessionActivityType.TURN_COMPLETED, "done", 1L, isRead = true),
                    activity(SessionActivityType.APPROVAL_REQUIRED, "decision", 2L),
                    activity(SessionActivityType.NEW_OUTPUT, "feedback", 3L),
                ),
            ),
        ).reduce(NotificationCenterAction.SetVisibility(NotificationVisibility.UNREAD_ONLY))
            .reduce(NotificationCenterAction.SelectTopic(NotificationTopic.DECISIONS))

        assertEquals(2, state.unreadCount)
        assertEquals(listOf("decision"), state.visibleItems.map { it.id })
    }

    @Test
    fun archiveCanBeUndoneWithoutChangingReadState() {
        val original = activity(SessionActivityType.FAILURE, "blocked", 4L)
        val archived = NotificationCenterState(listOf(original.toItem()))
            .reduce(NotificationCenterAction.Archive("blocked"))

        assertTrue(archived.visibleItems.isEmpty())
        assertEquals("blocked", archived.undoItem?.id)

        val restored = archived.reduce(NotificationCenterAction.UndoArchive)
        assertEquals(listOf("blocked"), restored.visibleItems.map { it.id })
        assertTrue(restored.visibleItems.single().isUnread)
    }

    @Test
    fun refreshFailurePreservesItemsAndReportsOfflineRecoveryState() {
        val state = NotificationCenterState(listOf(activity(SessionActivityType.NEW_OUTPUT, "item", 1L).toItem()))
            .reduce(NotificationCenterAction.RefreshStarted)
            .reduce(NotificationCenterAction.RefreshFailed("Connection unavailable"))

        assertFalse(state.isRefreshing)
        assertTrue(state.isOffline)
        assertEquals("Connection unavailable", state.errorMessage)
        assertEquals(listOf("item"), state.visibleItems.map { it.id })
    }

    private fun activity(
        type: SessionActivityType,
        id: String,
        occurredAt: Long,
        isRead: Boolean = false,
    ) = SessionActivity(
        id = id,
        locator = SessionLocator(
            connectionProviderId = ConnectionProviderId("provider-test"),
            connectionProfileId = ConnectionProfileId("profile-test"),
            agentProviderId = AgentProviderId("agent-test"),
            agentSessionId = AgentSessionId("session-test"),
        ),
        type = type,
        summary = SessionActivitySummary.Verbatim(id),
        eventAnchorId = null,
        occurredAtEpochMillis = occurredAt,
        isRead = isRead,
    )

    private fun SessionActivity.toItem() = NotificationCenterItem(id, this, NotificationTopic.TASK_BLOCKED)
}
