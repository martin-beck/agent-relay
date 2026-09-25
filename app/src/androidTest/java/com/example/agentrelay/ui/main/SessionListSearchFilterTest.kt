/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SessionListSearchFilterTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun searchAndResetControlsExposeSemanticStates() {
        composeTestRule.setContent {
            var filters by remember { mutableStateOf(SessionListSearchFilterState()) }
            SessionListSearchFilterControls(
                filters = filters,
                resultCount = if (filters.query.isBlank()) 3 else 0,
                onFiltersChanged = { filters = it },
            )
        }

        composeTestRule.onNodeWithTag("session-search-field").assertIsDisplayed()
        composeTestRule.onNodeWithText("3 sessions").assertIsDisplayed()
        composeTestRule.onNodeWithTag("session-search-field").performTextReplacement("missing")
        composeTestRule.waitUntil(timeoutMillis = 2_000) {
            composeTestRule.onAllNodesWithText("No sessions match these filters").fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText("No sessions match these filters").assertIsDisplayed()
        composeTestRule.onNodeWithTag("session-search-reset").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("3 sessions").assertIsDisplayed()
    }
}
