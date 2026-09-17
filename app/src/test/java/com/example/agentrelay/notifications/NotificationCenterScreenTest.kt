/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.notifications

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.agentrelay.session.api.SessionActivity
import dev.agentrelay.session.api.SessionActivitySummary
import dev.agentrelay.session.api.SessionActivityType
import dev.agentrelay.session.api.SessionLocator
import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSessionId
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationCenterScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun exposesFiltersAndOfflineRecoverySemantics() {
        val item = activity("blocked", SessionActivityType.FAILURE).toItem()
        composeRule.setContent {
            NotificationCenterScreen(
                state = NotificationCenterState(
                    items = listOf(item),
                    isOffline = true,
                    errorMessage = "Connection unavailable",
                ),
                onAction = {},
            )
        }

        composeRule.onNodeWithTag(NOTIFICATION_CENTER_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Unread").assertIsDisplayed()
        composeRule.onNodeWithText("Needs action").assertIsDisplayed()
        composeRule.onNodeWithTag(NOTIFICATION_CENTER_OFFLINE_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Archive").performClick()
    }

    private fun activity(id: String, type: SessionActivityType) = SessionActivity(
        id = id,
        locator = SessionLocator(
            ConnectionProviderId("provider-test"),
            ConnectionProfileId("profile-test"),
            AgentProviderId("agent-test"),
            AgentSessionId("session-test"),
        ),
        type = type,
        summary = SessionActivitySummary.Verbatim(id),
        eventAnchorId = null,
        occurredAtEpochMillis = 1L,
    )

    private fun SessionActivity.toItem() = NotificationCenterItem(id, this, NotificationTopic.TASK_BLOCKED)
}
