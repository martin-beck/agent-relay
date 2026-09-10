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
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.example.agentrelay.theme.AgentRelayTheme
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.session.api.SessionActionState
import org.junit.Rule
import org.junit.Test

class SessionRecoveryUiTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun recoveryKeepsUncertainEffectVisibleUntilANewRequestIsReidentified() {
        val recorder = ActionRecorder()
        val safeHub = actionHub()
        val delivering = safeHub.attentionActions.single().copy(
            state = SessionActionState.DELIVERING,
            completedDecision = AgentApprovalDecision.SUBMIT,
            additionalConfirmationGiven = true,
            isBusy = true,
        )
        val uncertainHub = safeHub.copy(
            attentionActions = listOf(delivering),
            selectedSession = checkNotNull(safeHub.selectedSession).copy(actions = listOf(delivering)),
        )
        var hub by mutableStateOf<SessionHubUiModel>(uncertainHub)
        composeTestRule.setContent {
            AgentRelayTheme {
                MainScreenContent(
                    state = MainScreenUiState.Ready(hub),
                    actions = recorder.actions(),
                    modifier = Modifier.requiredSize(width = 1_000.dp, height = 900.dp),
                )
            }
        }

        val uncertainty =
            "Response delivery is awaiting provider confirmation. Do not retry this request; " +
                "wait for a newly identified provider request or verify its state independently."
        composeTestRule.onNodeWithText(uncertainty).assertIsDisplayed()
        composeTestRule.runOnIdle { hub = safeHub }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText(uncertainty).assertDoesNotExist()
        composeTestRule.onNodeWithText("Submit answers").assertExists()
        check(recorder.actionResponse == null)
    }
}
