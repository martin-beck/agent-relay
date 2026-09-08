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
import com.example.agentrelay.theme.AgentRelayTheme
import dev.agentrelay.companion.api.CompanionDeviceId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class WearInstallOfferCardTest {
    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @SdkSuppress(minSdkVersion = 34)
    @Test
    fun offerNamesDeviceAndExposesAccessibleConsentChoices() {
        var installs = 0
        var declines = 0
        composeTestRule.setContent {
            AgentRelayTheme {
                WearInstallOfferCard(
                    state = WearInstallOfferUiState.Offer(DEVICE_ID, "Pixel Watch 4"),
                    onInstall = { installs += 1 },
                    onDecline = { declines += 1 },
                    onCancel = {},
                    onRetry = {},
                )
            }
        }

        composeTestRule.onNodeWithTag(WEAR_INSTALL_OFFER_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Set up Pixel Watch 4").assertIsDisplayed()
        composeTestRule.onNodeWithText("Install on watch").performClick()
        composeTestRule.onNodeWithText("Not now").performClick()
        composeTestRule.runOnIdle {
            assertEquals(1, installs)
            assertEquals(1, declines)
        }
        composeTestRule.enableAccessibilityChecks()
        composeTestRule.onRoot().tryPerformAccessibilityChecks()
    }

    @Test
    fun progressCanBeCancelledAndRecoveryCanBeRetriedOrDismissed() {
        val state = mutableStateOf<WearInstallOfferUiState>(
            WearInstallOfferUiState.Installing(DEVICE_ID, "Test watch"),
        )
        var cancels = 0
        var retries = 0
        var declines = 0
        composeTestRule.setContent {
            AgentRelayTheme {
                WearInstallOfferCard(
                    state = state.value,
                    onInstall = {},
                    onDecline = { declines += 1 },
                    onCancel = { cancels += 1 },
                    onRetry = { retries += 1 },
                )
            }
        }

        composeTestRule.onNodeWithText("Cancel installation").performClick()
        composeTestRule.runOnIdle {
            assertEquals(1, cancels)
            state.value = WearInstallOfferUiState.Recovery(
                DEVICE_ID,
                "Test watch",
                WearInstallRecoveryReason.CONNECTION_FAILED,
            )
        }
        composeTestRule.onNodeWithText("The watch disconnected during setup. Reconnect it and try again.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Try again").performClick()
        composeTestRule.onNodeWithText("Not now").performClick()
        composeTestRule.runOnIdle {
            assertEquals(1, retries)
            assertEquals(1, declines)
            state.value = WearInstallOfferUiState.Hidden
        }
        composeTestRule.onNodeWithTag(WEAR_INSTALL_OFFER_TEST_TAG).assertDoesNotExist()
    }

    private companion object {
        val DEVICE_ID = CompanionDeviceId("cd_v1_testwatch")
    }
}
