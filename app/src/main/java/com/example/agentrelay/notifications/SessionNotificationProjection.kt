/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.notifications

import dev.agentrelay.session.api.SessionActivity
import dev.agentrelay.session.api.SessionActivityType
import dev.agentrelay.session.api.SessionHubSnapshot
import dev.agentrelay.session.api.SessionNotificationPriority
import dev.agentrelay.session.api.SessionRecord
import java.security.MessageDigest

internal enum class SessionNotificationKind {
    ACTION_REQUIRED,
    FAILURE,
    COMPLETION,
    ACTIVITY,
}

internal data class ProjectedSessionNotification(
    val notificationKey: String,
    val sessionNavigationKey: String,
    val kind: SessionNotificationKind,
    val occurredAtEpochMillis: Long,
) {
    init {
        require(notificationKey.isSha256())
        require(sessionNavigationKey.isSha256())
        require(occurredAtEpochMillis >= 0L)
    }
}

internal object SessionNotificationProjection {
    fun project(snapshot: SessionHubSnapshot): List<ProjectedSessionNotification> {
        val sessions = snapshot.sessions.associateBy(SessionRecord::locator)
        return snapshot.activities
            .asSequence()
            .filter { activity -> activity.shouldProject(sessions[activity.locator]) }
            .sortedWith(
                compareByDescending<SessionActivity> { it.requiresAction }
                    .thenByDescending(SessionActivity::occurredAtEpochMillis),
            )
            .take(MAX_PROJECTED_NOTIFICATIONS)
            .map { activity ->
                ProjectedSessionNotification(
                    notificationKey = stableDigest(
                        NOTIFICATION_NAMESPACE,
                        activity.locator.stableKey,
                        activity.id,
                    ),
                    sessionNavigationKey = stableDigest(activity.locator.stableKey),
                    kind = activity.notificationKind,
                    occurredAtEpochMillis = activity.occurredAtEpochMillis,
                )
            }
            .toList()
    }

    private fun SessionActivity.shouldProject(session: SessionRecord?): Boolean {
        session ?: return false
        if (type.isActionable && isResolved) {
            return false
        }
        if (isRead && !requiresAction) {
            return false
        }
        if (type.isSilentTransportLifecycle) {
            return false
        }
        return when (session.preferences.notificationPriority) {
            SessionNotificationPriority.ALL_ACTIVITY -> true
            SessionNotificationPriority.IMPORTANT_ONLY ->
                requiresAction || type == SessionActivityType.FAILURE
            SessionNotificationPriority.FINAL_OUTPUT_ONLY ->
                requiresAction || type == SessionActivityType.TURN_COMPLETED
            SessionNotificationPriority.MUTED -> false
        }
    }

    private val SessionActivity.notificationKind: SessionNotificationKind
        get() = when {
            requiresAction -> SessionNotificationKind.ACTION_REQUIRED
            type == SessionActivityType.FAILURE -> SessionNotificationKind.FAILURE
            type == SessionActivityType.TURN_COMPLETED -> SessionNotificationKind.COMPLETION
            else -> SessionNotificationKind.ACTIVITY
        }

    private val SessionActivityType.isActionable: Boolean
        get() = this == SessionActivityType.APPROVAL_REQUIRED ||
            this == SessionActivityType.QUESTION

    /** Transport recovery is retained in the activity history but never interrupts the user. */
    private val SessionActivityType.isSilentTransportLifecycle: Boolean
        get() = this == SessionActivityType.RECONNECTED

    private fun stableDigest(vararg values: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(
                values.joinToString(separator = "") { value ->
                    value.length.toString() + ":" + value
                }.encodeToByteArray(),
            )
            .joinToString(separator = "") { "%02x".format(it) }

    private const val MAX_PROJECTED_NOTIFICATIONS = 64
    private const val NOTIFICATION_NAMESPACE = "session-notification"
}

private fun String.isSha256(): Boolean =
    length == 64 && all { character -> character in '0'..'9' || character in 'a'..'f' }
