/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
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

    @Test
    fun resetSettingsImmediatelyRestoresSystemLanguage() {
        val store = MemorySettingsStore(AppSettings(language = "de"))
        val languageChanges = mutableListOf<String>()
        composeRule.setContent {
            SettingsScreen(
                store = store,
                onBack = {},
                onLanguageChanged = languageChanges::add,
            )
        }

        composeRule.onNodeWithText("Reset settings").performClick()

        assertEquals(listOf(SYSTEM_LANGUAGE_TAG), languageChanges)
        assertEquals(SYSTEM_LANGUAGE_TAG, store.read().language)
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
