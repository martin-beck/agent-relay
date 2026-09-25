/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.agentrelay.background.BackgroundTransportState
import com.example.agentrelay.notifications.SessionNotificationPermissionState
import com.example.agentrelay.theme.AgentRelayTheme
import org.junit.Rule
import org.junit.Test

class MainScreenBackgroundTransportTest {

    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backgroundTransportRequiresNotificationsAndSupportsExplicitStartStop() {
        var state by mutableStateOf(BackgroundTransportState.STOPPED)
        var permissionState by mutableStateOf(SessionNotificationPermissionState.HIDDEN)
        var starts = 0
        var stops = 0
        var manageConnections = 0
        composeTestRule.setContent {
            AgentRelayTheme {
                ConnectionSettingsScreen(
                    notificationPermissionState = permissionState,
                    onRequestNotificationPermission = {},
                    onOpenNotificationSettings = {},
                    backgroundTransportState = state,
                    onStartBackgroundTransport = {
                        starts += 1
                        state = BackgroundTransportState.ACTIVE
                    },
                    onStopBackgroundTransport = {
                        stops += 1
                        state = BackgroundTransportState.STOPPED
                    },
                    onManageConnections = { manageConnections += 1 },
                    modifier = Modifier.requiredSize(width = 1_000.dp, height = 900.dp),
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(BACKGROUND_TRANSPORT_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Keep connections active").performClick()
        composeTestRule.onNodeWithText("Stop background connection").performClick()
        check(starts == 1)
        check(stops == 1)

        composeTestRule.runOnIdle {
            permissionState = SessionNotificationPermissionState.REQUESTABLE
            state = BackgroundTransportState.STOPPED
        }
        composeTestRule
            .onNodeWithText("Allow notifications before starting a background connection.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Keep connections active").assertIsNotEnabled()
        check(starts == 1)

        composeTestRule.onNode(hasText("Connections") and hasClickAction()).performClick()
        check(manageConnections == 1)
    }
}
