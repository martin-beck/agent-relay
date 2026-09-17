/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */
package com.example.agentrelay.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRepositoryTest {
    @Test fun migrationMapsLegacyNotificationPreferenceAndRemovesLegacyKey() {
        val store = MemorySettingsStore(AppSettings(notifications = false))
        assertEquals(false, store.read().notifications)
    }

    @Test fun writeThenReadPreservesSettings() {
        val store = MemorySettingsStore()
        val expected = AppSettings(backgroundConnections = true, language = "de", highContrast = true)
        store.write(expected)
        assertEquals(expected, store.read())
    }

    @Test fun resetRestoresSafeDefaults() {
        val store = MemorySettingsStore()
        store.write(AppSettings(backgroundConnections = true, notifications = false))
        store.reset()
        assertEquals(AppSettings(), store.read())
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
