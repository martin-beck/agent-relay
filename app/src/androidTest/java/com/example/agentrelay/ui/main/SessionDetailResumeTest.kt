/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.example.agentrelay.R
import com.example.agentrelay.theme.AgentRelayTheme
import org.junit.Rule
import org.junit.Test

class SessionDetailResumeTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun resumeActionIsReachableAndFailuresRemainVisible() {
        val recorder = ActionRecorder()
        var dismissed = false
        val hub = testHub().let { current ->
            val detail = checkNotNull(current.selectedSession)
            current.copy(
                operationError = UiMessage.Verbatim("The stored session could not be resumed."),
                selectedSession = detail.copy(
                    composer = detail.composer.copy(
                        canResume = true,
                        canInterrupt = false,
                    ),
                ),
            )
        }

        composeTestRule.setContent {
            AgentRelayTheme {
                SessionDetailRoute(
                    state = MainScreenUiState.Ready(hub),
                    onBack = {},
                    onDraftChanged = { _, _, _, _ -> },
                    onSubmitDraft = {},
                    onResumeSession = recorder.actions().resumeSession,
                    onInterruptSession = {},
                    onRespondToAction = { _, _, _, _, _ -> },
                    onRefreshArtifacts = {},
                    onSaveArtifact = { _, _, _ -> },
                    onCancelArtifact = {},
                    onDismissError = { dismissed = true },
                    modifier = Modifier.requiredSize(width = 480.dp, height = 900.dp),
                )
            }
        }

        composeTestRule
            .onNodeWithText("The stored session could not be resumed.")
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(composeTestRule.resourceText(R.string.session_composer_resume))
            .performClick()
        composeTestRule
            .onNodeWithText(composeTestRule.resourceText(R.string.action_dismiss))
            .performClick()

        check(recorder.resumedSession == "session-key")
        check(dismissed)
    }
}
