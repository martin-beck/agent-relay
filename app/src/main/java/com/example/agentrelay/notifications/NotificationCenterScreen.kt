/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.example.agentrelay.R

internal const val NOTIFICATION_CENTER_TEST_TAG = "notification-center"
internal const val NOTIFICATION_CENTER_ITEMS_TEST_TAG = "notification-center-items"
internal const val NOTIFICATION_CENTER_OFFLINE_TEST_TAG = "notification-center-offline"
internal const val NOTIFICATION_CENTER_UNDO_TEST_TAG = "notification-center-undo"

@Composable
internal fun NotificationCenterScreen(
    state: NotificationCenterState,
    onAction: (NotificationCenterAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val refreshDescription = stringResource(R.string.notification_center_refresh_notifications)
    Column(
        modifier = modifier.fillMaxSize().testTag(NOTIFICATION_CENTER_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text(stringResource(R.string.notification_center_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.notification_center_unread_count, state.unreadCount), style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedButton(
                onClick = { onAction(NotificationCenterAction.RefreshStarted) },
                enabled = !state.isRefreshing,
                modifier = Modifier.semantics {
                    contentDescription = refreshDescription
                },
            ) {
                Text(stringResource(if (state.isRefreshing) R.string.notification_center_refreshing else R.string.notification_center_refresh))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.visibility == NotificationVisibility.ALL,
                onClick = { onAction(NotificationCenterAction.SetVisibility(NotificationVisibility.ALL)) },
                label = { Text(stringResource(R.string.notification_center_all)) },
            )
            FilterChip(
                selected = state.visibility == NotificationVisibility.UNREAD_ONLY,
                onClick = {
                    onAction(NotificationCenterAction.SetVisibility(NotificationVisibility.UNREAD_ONLY))
                },
                label = { Text(stringResource(R.string.notification_center_unread)) },
            )
            FilterChip(
                selected = state.visibility == NotificationVisibility.ACTION_REQUIRED,
                onClick = {
                    onAction(NotificationCenterAction.SetVisibility(NotificationVisibility.ACTION_REQUIRED))
                },
                label = { Text(stringResource(R.string.notification_center_needs_action)) },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TopicChip(stringResource(R.string.notification_center_all_topics), null, state, onAction)
            NotificationTopic.entries.forEach { topic ->
                TopicChip(stringResource(topic.resourceId), topic, state, onAction)
            }
        }
        if (state.isOffline) {
            Text(
                text = state.errorMessage ?: stringResource(R.string.notification_center_offline),
                modifier = Modifier.testTag(NOTIFICATION_CENTER_OFFLINE_TEST_TAG),
                color = MaterialTheme.colorScheme.error,
            )
        }
        state.undoItem?.let { item ->
            Row(
                modifier = Modifier.fillMaxWidth().testTag(NOTIFICATION_CENTER_UNDO_TEST_TAG),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.notification_center_archived))
                Button(onClick = { onAction(NotificationCenterAction.UndoArchive) }) { Text(stringResource(R.string.notification_center_undo)) }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).testTag(NOTIFICATION_CENTER_ITEMS_TEST_TAG),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(state.visibleItems, key = NotificationCenterItem::id) { item ->
                NotificationCenterRow(item, onAction)
            }
        }
    }
}

@Composable
private fun TopicChip(
    label: String,
    topic: NotificationTopic?,
    state: NotificationCenterState,
    onAction: (NotificationCenterAction) -> Unit,
) {
    FilterChip(
        selected = state.selectedTopic == topic,
        onClick = { onAction(NotificationCenterAction.SelectTopic(topic)) },
        label = { Text(label) },
    )
}

@Composable
private fun NotificationCenterRow(
    item: NotificationCenterItem,
    onAction: (NotificationCenterAction) -> Unit,
) {
    val topicLabel = stringResource(item.topic.resourceId)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .semantics { contentDescription = "$topicLabel: ${item.activity.id}" },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.activity.id, style = MaterialTheme.typography.titleMedium)
                Text(topicLabel, style = MaterialTheme.typography.bodySmall)
            }
            if (item.isUnread) {
                OutlinedButton(onClick = { onAction(NotificationCenterAction.MarkRead(item.id)) }) {
                    Text(stringResource(R.string.notification_center_mark_read))
                }
            }
            OutlinedButton(onClick = { onAction(NotificationCenterAction.Archive(item.id)) }) {
                Text(stringResource(R.string.notification_center_archive))
            }
        }
        HorizontalDivider()
    }
}

private val NotificationTopic.resourceId: Int
    get() = when (this) {
        NotificationTopic.SSH_LIFECYCLE -> R.string.notification_topic_ssh_lifecycle
        NotificationTopic.AGENT_FEEDBACK -> R.string.notification_topic_agent_feedback
        NotificationTopic.DECISIONS -> R.string.notification_topic_decisions
        NotificationTopic.TASK_COMPLETION -> R.string.notification_topic_task_completion
        NotificationTopic.TASK_BLOCKED -> R.string.notification_topic_task_blocked
    }
