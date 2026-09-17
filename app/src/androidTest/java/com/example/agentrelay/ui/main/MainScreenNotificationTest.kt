/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.filters.SdkSuppress
import com.example.agentrelay.notifications.SessionNotificationPermissionState
import com.example.agentrelay.theme.AgentRelayTheme
import org.junit.Rule
import org.junit.Test

class MainScreenNotificationTest {

    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @SdkSuppress(minSdkVersion = 34)
    @Test
    fun notificationPermissionPromptExplainsPrivacyAndSupportsRecovery() {
        val permissionState = mutableStateOf(SessionNotificationPermissionState.REQUESTABLE)
        var requests = 0
        var settingsOpens = 0
        composeTestRule.setContent {
            AgentRelayTheme {
                ConnectionSettingsScreen(
                    notificationPermissionState = permissionState.value,
                    onRequestNotificationPermission = { requests += 1 },
                    onOpenNotificationSettings = { settingsOpens += 1 },
                    backgroundTransportState = com.example.agentrelay.background.BackgroundTransportState.STOPPED,
                    onStartBackgroundTransport = {},
                    onStopBackgroundTransport = {},
                    onBack = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(NOTIFICATION_PERMISSION_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Allow notifications").performClick()
        composeTestRule.runOnIdle {
            check(requests == 1)
            permissionState.value = SessionNotificationPermissionState.SETTINGS_REQUIRED
        }
        composeTestRule.onNodeWithText("Open notification settings").performClick()
        composeTestRule.runOnIdle {
            check(settingsOpens == 1)
        }

        composeTestRule.enableAccessibilityChecks()
        composeTestRule.onRoot().tryPerformAccessibilityChecks()
    }
}
