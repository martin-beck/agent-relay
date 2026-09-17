/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.agentrelay.theme.AgentRelayTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickNavigationFooterTest {

    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun exposesAndActivatesEveryDestination() {
        val activated = mutableStateListOf<QuickNavigationDestinationId>()
        composeTestRule.setContent {
            AgentRelayTheme {
                MainScreenContent(
                    state = MainScreenUiState.Ready(testHub()),
                    actions = ActionRecorder().actions(),
                    modifier = Modifier.requiredSize(width = 420.dp, height = 720.dp),
                    onQuickNavigation = { activated += it },
                )
            }
        }

        QuickNavigationDestinationId.entries.forEach { destination ->
            composeTestRule
                .onNodeWithTag(QUICK_NAVIGATION_DESTINATION_PREFIX + destination.name.lowercase())
                .assertIsDisplayed()
                .performClick()
        }

        check(activated == QuickNavigationDestinationId.entries)
        composeTestRule
            .onNodeWithTag(QUICK_NAVIGATION_DESTINATION_PREFIX + "sessions")
            .assertIsSelected()
        composeTestRule
            .onNodeWithTag(QUICK_NAVIGATION_DESTINATION_PREFIX + "help")
            .assertIsNotSelected()
    }

    @Test
    fun supportsConfigurableDisabledDestination() {
        val activated = mutableStateListOf<QuickNavigationDestinationId>()
        val destination = QuickNavigationDestination(
            id = QuickNavigationDestinationId.HELP,
            label = "Help centre",
            enabled = false,
            onClick = { activated += QuickNavigationDestinationId.HELP },
        )
        composeTestRule.setContent {
            AgentRelayTheme {
                MainScreenContent(
                    state = MainScreenUiState.Ready(testHub()),
                    actions = ActionRecorder().actions(),
                    quickNavigationDestinations = listOf(destination),
                )
            }
        }

        composeTestRule
            .onNodeWithTag(QUICK_NAVIGATION_DESTINATION_PREFIX + "help")
            .assertIsNotEnabled()
            .performClick()
        check(activated.isEmpty())
    }
}
