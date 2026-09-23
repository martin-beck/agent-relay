/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun settingsRouteComposesWithoutSaveableStateCrash() {
        composeRule.setContent {
            SettingsScreen(store = MemorySettingsStore(), onBack = {})
        }

        composeRule.onNodeWithText("Settings").assertIsDisplayed()
    }
}

private class MemorySettingsStore(
    private var value: AppSettings = AppSettings(),
) : SettingsStore {
    override fun read(): AppSettings = value

    override fun write(settings: AppSettings) {
        value = settings
    }

    override fun reset() {
        value = AppSettings()
    }
}
