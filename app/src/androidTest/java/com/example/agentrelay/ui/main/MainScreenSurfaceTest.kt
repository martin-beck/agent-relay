/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.example.agentrelay.theme.AgentRelayTheme
import org.junit.Rule
import org.junit.Test

class MainScreenSurfaceTest {

    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun compactHub_exposesConnectionsIdentityReviewAndSessionActions() {
        val recorder = ActionRecorder()
        setContent(MainScreenUiState.Ready(testHub()), recorder, MainScreenSurface.NEW_SESSION)

        composeTestRule.onNodeWithText("Connections").assertExists()
        val hubList = composeTestRule.onNodeWithTag(SESSION_HUB_SCROLL_LIST_TEST_TAG)
        hubList.performScrollToNode(hasText("Connect"))
        composeTestRule.onNodeWithText("Connect").performClick()
        hubList.performScrollToNode(hasText("Replace identity"))
        composeTestRule.onNodeWithText("Replace identity").performClick()

        setContent(MainScreenUiState.Ready(testHub()), recorder)
        val sessionList = composeTestRule.onNodeWithTag(SESSION_HUB_SCROLL_LIST_TEST_TAG)
        sessionList.performScrollToNode(hasText("Investigate flaky build"))
        composeTestRule.onNodeWithText("Investigate flaky build").performClick()

        check(recorder.connectedKey == "local-key")
        check(recorder.trustedKey == "ssh-key")
        check(recorder.replaceIdentity)
        check(recorder.selectedKey == "session-key")
        check(recorder.openedKey == "session-key")
    }

    @Test
    fun profileManagementIsDiscoverableFromProviderAndExistingConnection() {
        val recorder = ActionRecorder()
        setContent(MainScreenUiState.Ready(testHub()), recorder, MainScreenSurface.NEW_SESSION)

        composeTestRule
            .onNodeWithTag(SESSION_HUB_SCROLL_LIST_TEST_TAG)
            .performScrollToNode(hasText("Add Secure Shell profile"))
        composeTestRule
            .onNodeWithText("Add Secure Shell profile")
            .performClick()
        composeTestRule.onNodeWithText("Edit profile").performClick()

        check(recorder.addedProvider == "ssh.secure-shell")
        check(recorder.editedConnection == "ssh-key")
    }

    @Test
    fun readyAgentEndpointExposesSessionLauncher() {
        val recorder = ActionRecorder()
        setContent(MainScreenUiState.Ready(actionHub()), recorder, MainScreenSurface.NEW_SESSION)

        val hubList = composeTestRule.onNodeWithTag(SESSION_HUB_SCROLL_LIST_TEST_TAG)
        hubList.performScrollToNode(hasText("Start Codex on Trusted server"))
        composeTestRule.onNodeWithText("Start Codex on Trusted server").performClick()

        check(recorder.openedSessionCreator == "launcher-key")
    }

    @Test
    fun emptyHub_explainsHowToProceed() {
        val recorder = ActionRecorder()
        val emptyHub = testHub().copy(
            connections = emptyList(),
            sessions = emptyList(),
            selectedSession = null,
            selectedSessionKey = null,
        )
        setContent(MainScreenUiState.Ready(emptyHub), recorder, MainScreenSurface.NEW_SESSION)

        composeTestRule
            .onNodeWithText("No connection profiles are available. Refresh to try again.")
            .assertExists()
        composeTestRule
            .onNodeWithText(
                "No sessions have been discovered yet. Connect a profile to check its agent providers.",
            )
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun setContent(
        state: MainScreenUiState,
        recorder: ActionRecorder,
        surface: MainScreenSurface = MainScreenSurface.EXISTING_SESSIONS,
    ) {
        composeTestRule.setContent {
            AgentRelayTheme {
                MainScreenContent(
                    state = state,
                    actions = recorder.actions(),
                    surface = surface,
                )
            }
        }
    }
}
