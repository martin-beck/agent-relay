/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.notifications

internal enum class SessionNotificationTopic {
    AGENT_FEEDBACK,
    USER_DECISIONS,
    COMPLETION,
    BLOCKED_TASKS,
    TRANSPORT,
}

internal enum class SessionNotificationLevel {
    HIGH_LEVEL,
    ALL_ACTIVITY,
}

internal data class SessionNotificationPreferences(
    val enabled: Boolean = true,
    val topics: Set<SessionNotificationTopic> = DEFAULT_TOPICS,
    val level: SessionNotificationLevel = SessionNotificationLevel.HIGH_LEVEL,
) {
    companion object {
        val DEFAULT_TOPICS: Set<SessionNotificationTopic> =
            setOf(
                SessionNotificationTopic.AGENT_FEEDBACK,
                SessionNotificationTopic.USER_DECISIONS,
                SessionNotificationTopic.COMPLETION,
                SessionNotificationTopic.BLOCKED_TASKS,
            )
    }
}
