/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.agentrelay.theme.AgentRelayTheme
import dev.agentrelay.provider.api.AgentSessionState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResponsiveShellInteractionTest {

    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun horizontalSwipeExposesVisibleLocalizedActionAffordance() {
        composeTestRule.setContent {
            AgentRelayTheme {
                SwipeActionSurface(
                    modifier = Modifier.requiredSize(240.dp, 72.dp),
                    accessibilityActionLabel = "Pin session",
                    onAction = {},
                ) {
                    Box { Text("Session") }
                }
            }
        }

        composeTestRule.onNodeWithText("Pin session").assertDoesNotExist()
        composeTestRule.onNodeWithText("Session").performTouchInput {
            down(center)
            moveTo(center.copy(x = center.x - 120f))
        }
        composeTestRule.onNodeWithText("Pin session").assertIsDisplayed()
        composeTestRule.onNodeWithText("Session").performTouchInput { up() }
    }

    @Test
    fun sessionCardSwipeUsesPinActionWhenNoCustomHandlerIsProvided() {
        var pinRequests = 0
        composeTestRule.setContent {
            AgentRelayTheme {
                SessionCard(
                    session = SessionUiModel(
                        stableKey = "a".repeat(64),
                        title = UiMessage.Verbatim("Session"),
                        preview = "Preview",
                        connectionLabel = "Host",
                        connectionProviderName = "SSH",
                        agentProviderLabel = "Codex",
                        projectPath = null,
                        agentState = AgentSessionState.IDLE,
                        unreadCount = 0,
                        requiresActionCount = 0,
                        lastActivityAtEpochMillis = null,
                        isPinned = false,
                    ),
                    selected = false,
                    onClick = {},
                    onTogglePinned = { pinRequests++ },
                )
            }
        }

        composeTestRule.onNodeWithText("Session").performTouchInput { swipeLeft() }
        check(pinRequests == 1)
    }
}
