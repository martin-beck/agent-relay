/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */
package com.example.agentrelay.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import com.example.agentrelay.notifications.SessionNotificationLevel
import com.example.agentrelay.notifications.SessionNotificationPreferences
import com.example.agentrelay.notifications.SessionNotificationTopic

class SettingsRepositoryTest {
    @Test fun migrationMapsLegacyNotificationPreferenceAndRemovesLegacyKey() {
        val store = MemorySettingsStore(AppSettings(notifications = false))
        assertEquals(false, store.read().notifications)
    }

    @Test fun writeThenReadPreservesSettings() {
        val store = MemorySettingsStore()
        val expected = AppSettings(
            energySavingMode = false,
            backgroundConnections = true,
            language = "de",
            highContrast = true,
            notificationPreferences = SessionNotificationPreferences(
                topics = setOf(SessionNotificationTopic.COMPLETION),
                level = SessionNotificationLevel.ALL_ACTIVITY,
            ),
        )
        store.write(expected)
        assertEquals(expected, store.read())
    }

    @Test fun resetRestoresSafeDefaults() {
        val store = MemorySettingsStore()
        store.write(AppSettings(backgroundConnections = true, notifications = false))
        store.reset()
        assertEquals(AppSettings(), store.read())
    }

    @Test fun languagePickerIncludesSystemAndEveryBundledLanguage() {
        assertEquals(
            listOf("system", "ar", "bn", "de", "en", "es", "fr", "hi", "id", "it", "ja", "pt-BR", "ru", "zh-CN", "zh-TW"),
            supportedAppLanguages.map(SupportedAppLanguage::tag),
        )
    }

    @Test fun selectingAndResettingLanguagePersistsTheSystemChoice() {
        val store = MemorySettingsStore()

        store.write(store.read().copy(language = "ja"))
        assertEquals("ja", store.read().language)

        store.write(store.read().copy(language = "system"))
        assertEquals("system", store.read().language)
    }
}

private class MemorySettingsStore(private var value: AppSettings = AppSettings()) : SettingsStore {
    override fun read() = value
    override fun write(settings: AppSettings) {
        value = settings
    }
    override fun reset() {
        value = AppSettings()
    }
}
