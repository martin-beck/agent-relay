/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.notifications

import dev.agentrelay.session.api.SessionActivity
import dev.agentrelay.session.api.SessionActivityType

internal enum class NotificationTopic {
    SSH_LIFECYCLE,
    AGENT_FEEDBACK,
    DECISIONS,
    TASK_COMPLETION,
    TASK_BLOCKED,
}

internal enum class NotificationVisibility {
    ALL,
    UNREAD_ONLY,
    ACTION_REQUIRED,
}

internal data class NotificationCenterItem(
    val id: String,
    val activity: SessionActivity,
    val topic: NotificationTopic,
    val archived: Boolean = false,
) {
    val isUnread: Boolean
        get() = !activity.isRead
}

internal data class NotificationCenterState(
    val items: List<NotificationCenterItem> = emptyList(),
    val selectedTopic: NotificationTopic? = null,
    val visibility: NotificationVisibility = NotificationVisibility.ALL,
    val isRefreshing: Boolean = false,
    val isOffline: Boolean = false,
    val errorMessage: String? = null,
    val undoItem: NotificationCenterItem? = null,
) {
    val unreadCount: Int
        get() = items.count { it.isUnread && !it.archived }

    val visibleItems: List<NotificationCenterItem>
        get() = items.asSequence()
            .filterNot(NotificationCenterItem::archived)
            .filter { item -> selectedTopic == null || item.topic == selectedTopic }
            .filter { item ->
                when (visibility) {
                    NotificationVisibility.ALL -> true
                    NotificationVisibility.UNREAD_ONLY -> item.isUnread
                    NotificationVisibility.ACTION_REQUIRED -> item.activity.requiresAction
                }
            }
            .sortedWith(
                compareByDescending<NotificationCenterItem> { it.activity.requiresAction }
                    .thenByDescending { it.activity.occurredAtEpochMillis },
            )
            .toList()
}

internal sealed interface NotificationCenterAction {
    data class Replace(val activities: List<SessionActivity>) : NotificationCenterAction
    data class MarkRead(val id: String) : NotificationCenterAction
    data object MarkAllRead : NotificationCenterAction
    data class Archive(val id: String) : NotificationCenterAction
    data object UndoArchive : NotificationCenterAction
    data class SelectTopic(val topic: NotificationTopic?) : NotificationCenterAction
    data class SetVisibility(val visibility: NotificationVisibility) : NotificationCenterAction
    data object RefreshStarted : NotificationCenterAction
    data class RefreshSucceeded(val activities: List<SessionActivity>) : NotificationCenterAction
    data class RefreshFailed(val message: String) : NotificationCenterAction
}

internal fun NotificationCenterState.reduce(action: NotificationCenterAction): NotificationCenterState = when (action) {
    is NotificationCenterAction.Replace -> copy(items = action.activities.toItems(), errorMessage = null)
    is NotificationCenterAction.MarkRead -> copy(items = items.markRead(action.id))
    NotificationCenterAction.MarkAllRead -> copy(
        items = items.map { item ->
            item.copy(activity = item.activity.copy(isRead = true))
        },
    )
    is NotificationCenterAction.Archive -> {
        val item = items.firstOrNull { it.id == action.id }
        if (item == null) {
            this
        } else {
            copy(
                items = items.map { candidate ->
                    if (candidate.id == action.id) candidate.copy(archived = true) else candidate
                },
                undoItem = item,
            )
        }
    }
    NotificationCenterAction.UndoArchive -> {
        val item = undoItem ?: return this
        copy(
            items = items.map { candidate -> if (candidate.id == item.id) item else candidate },
            undoItem = null,
        )
    }
    is NotificationCenterAction.SelectTopic -> copy(selectedTopic = action.topic)
    is NotificationCenterAction.SetVisibility -> copy(visibility = action.visibility)
    NotificationCenterAction.RefreshStarted -> copy(isRefreshing = true, errorMessage = null)
    is NotificationCenterAction.RefreshSucceeded -> copy(
        items = action.activities.toItems(),
        isRefreshing = false,
        isOffline = false,
        errorMessage = null,
    )
    is NotificationCenterAction.RefreshFailed -> copy(
        isRefreshing = false,
        isOffline = true,
        errorMessage = action.message,
    )
}

private fun List<NotificationCenterItem>.markRead(id: String): List<NotificationCenterItem> = map { item ->
    if (item.id == id) item.copy(activity = item.activity.copy(isRead = true)) else item
}

private fun List<SessionActivity>.toItems(): List<NotificationCenterItem> =
    asSequence()
        .filter { activity -> activity.type != SessionActivityType.RECONNECTED }
        .distinctBy(SessionActivity::id)
        .take(MAX_ITEMS)
        .map { activity ->
            NotificationCenterItem(
                id = activity.id,
                activity = activity,
                topic = activity.topic,
            )
        }
        .toList()

private val SessionActivity.topic: NotificationTopic
    get() = when (type) {
        SessionActivityType.APPROVAL_REQUIRED,
        SessionActivityType.QUESTION,
        -> NotificationTopic.DECISIONS
        SessionActivityType.FAILURE -> NotificationTopic.TASK_BLOCKED
        SessionActivityType.TURN_COMPLETED -> NotificationTopic.TASK_COMPLETION
        SessionActivityType.NEW_OUTPUT -> NotificationTopic.AGENT_FEEDBACK
        SessionActivityType.RECONNECTED -> NotificationTopic.SSH_LIFECYCLE
    }

private const val MAX_ITEMS = 64
