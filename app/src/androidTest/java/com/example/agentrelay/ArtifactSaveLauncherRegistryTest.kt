/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay

import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Guards the Activity-result launcher boundary used by navigation-owned artifact export. */
@RunWith(AndroidJUnit4::class)
class ArtifactSaveLauncherRegistryTest {

    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun launcherRemainsRegisteredWhenLocalizedContextIsProvided() {
        composeTestRule.setContent {
            LocalizedAppContent(language = "de") {
                rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("*/*"),
                ) { }
                Text("launcher ready")
            }
        }

        composeTestRule.onNodeWithText("launcher ready").assertIsDisplayed()
    }
}
