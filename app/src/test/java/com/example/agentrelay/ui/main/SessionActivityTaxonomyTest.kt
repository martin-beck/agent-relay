/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import dev.agentrelay.session.api.SessionActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionActivityTaxonomyTest {
    @Test
    fun topicsAreStableAndTransportIsDiagnosticOnly() {
        val activities = SessionActivityType.entries.map { type ->
            SessionActivityUiModel(
                id = type.name,
                type = type,
                summary = UiMessage.Verbatim(type.name),
                occurredAtEpochMillis = 1L,
                requiresAction = type == SessionActivityType.QUESTION,
                isRead = false,
            )
        }

        assertEquals(
            listOf(
                SessionActivityTopic.TRANSPORT,
                SessionActivityTopic.AGENT_FEEDBACK,
                SessionActivityTopic.USER_DECISIONS,
                SessionActivityTopic.COMPLETION,
                SessionActivityTopic.BLOCKED_TASKS,
            ),
            activitySections(activities).map { it.topic },
        )
        assertEquals(5, highLevelActivities(activities).size)
        assertTrue(activitySections(activities).single { it.topic == SessionActivityTopic.TRANSPORT }.isDiagnostic)
    }

    @Test
    fun actionableAndFailedEventsHaveDeterministicSeverity() {
        val question = activity(SessionActivityType.QUESTION, requiresAction = true)
        val failure = activity(SessionActivityType.FAILURE)
        assertEquals(SessionActivitySeverity.ACTION_REQUIRED, question.severity)
        assertEquals(SessionActivitySeverity.ERROR, failure.severity)
    }

    private fun activity(type: SessionActivityType, requiresAction: Boolean = false) =
        SessionActivityUiModel(
            id = type.name,
            type = type,
            summary = UiMessage.Verbatim(type.name),
            occurredAtEpochMillis = 1L,
            requiresAction = requiresAction,
            isRead = false,
        )
}
