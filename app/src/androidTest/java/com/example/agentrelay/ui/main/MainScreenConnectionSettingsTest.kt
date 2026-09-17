/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.agentrelay.theme.AgentRelayTheme
import org.junit.Rule
import org.junit.Test

class MainScreenConnectionSettingsTest {

    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun mainScreenUsesCompactConnectionSettingsEntryPoint() {
        var opened = 0
        composeTestRule.setContent {
            AgentRelayTheme {
                MainScreenContent(
                    state = MainScreenUiState.Ready(testHub()),
                    actions = ActionRecorder().actions(),
                    onOpenConnectionSettings = { opened += 1 },
                )
            }
        }

        composeTestRule.onNodeWithTag(BACKGROUND_TRANSPORT_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithText("Background connection").performClick()
        check(opened == 1)
    }
}
