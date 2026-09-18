/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.settings

import android.content.Context
import androidx.core.content.edit
import com.example.agentrelay.notifications.SessionNotificationLevel
import com.example.agentrelay.notifications.SessionNotificationPreferences
import com.example.agentrelay.notifications.SessionNotificationTopic

internal data class AppSettings(
    val energySavingMode: Boolean = true,
    val backgroundConnections: Boolean = false,
    val notifications: Boolean = true,
    val dynamicColor: Boolean = true,
    val highContrast: Boolean = false,
    val reduceMotion: Boolean = false,
    val language: String = "system",
    val offlineSpeech: Boolean = true,
    val notificationPreferences: SessionNotificationPreferences = SessionNotificationPreferences(),
)

internal interface SettingsStore {
    fun read(): AppSettings
    fun write(settings: AppSettings)
    fun reset()
}

internal class AndroidSettingsStore(context: Context) : SettingsStore {
    private val preferences = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    override fun read(): AppSettings {
        migrate()
        return AppSettings(
            energySavingMode = preferences.getBoolean(KEY_ENERGY_SAVING, true),
            backgroundConnections = preferences.getBoolean(KEY_BACKGROUND, false),
            notifications = preferences.getBoolean(KEY_NOTIFICATIONS, true),
            dynamicColor = preferences.getBoolean(KEY_DYNAMIC_COLOR, true),
            highContrast = preferences.getBoolean(KEY_HIGH_CONTRAST, false),
            reduceMotion = preferences.getBoolean(KEY_REDUCE_MOTION, false),
            language = preferences.getString(KEY_LANGUAGE, "system") ?: "system",
            offlineSpeech = preferences.getBoolean(KEY_OFFLINE_SPEECH, true),
            notificationPreferences = SessionNotificationPreferences(
                enabled = preferences.getBoolean(KEY_NOTIFICATIONS, true),
                topics = preferences.getStringSet(KEY_NOTIFICATION_TOPICS, null)
                    ?.mapNotNull { value -> value.toNotificationTopic() }
                    ?.toSet()
                    ?: SessionNotificationPreferences.DEFAULT_TOPICS,
                level = preferences.getString(KEY_NOTIFICATION_LEVEL, null)
                    ?.let { value -> runCatching { SessionNotificationLevel.valueOf(value) }.getOrNull() }
                    ?: SessionNotificationLevel.HIGH_LEVEL,
            ),
        )
    }

    override fun write(settings: AppSettings) {
        preferences.edit {
            putInt(KEY_VERSION, CURRENT_VERSION)
            putBoolean(KEY_ENERGY_SAVING, settings.energySavingMode)
            putBoolean(KEY_BACKGROUND, settings.backgroundConnections)
            putBoolean(KEY_NOTIFICATIONS, settings.notifications)
            putBoolean(KEY_DYNAMIC_COLOR, settings.dynamicColor)
            putBoolean(KEY_HIGH_CONTRAST, settings.highContrast)
            putBoolean(KEY_REDUCE_MOTION, settings.reduceMotion)
            putString(KEY_LANGUAGE, settings.language)
            putBoolean(KEY_OFFLINE_SPEECH, settings.offlineSpeech)
            putStringSet(KEY_NOTIFICATION_TOPICS, settings.notificationPreferences.topics.map { it.name }.toSet())
            putString(KEY_NOTIFICATION_LEVEL, settings.notificationPreferences.level.name)
        }
    }

    override fun reset() {
        preferences.edit { clear() }
    }

    private fun migrate() {
        if (preferences.getInt(KEY_VERSION, 0) >= CURRENT_VERSION) return
        val legacyNotifications = preferences.getBoolean("notifications_enabled", true)
        preferences.edit {
            putInt(KEY_VERSION, CURRENT_VERSION)
            putBoolean(KEY_NOTIFICATIONS, legacyNotifications)
            remove("notifications_enabled")
        }
    }

    private companion object {
        const val NAME = "agent_relay_settings_v1"
        const val CURRENT_VERSION = 2
        const val KEY_VERSION = "schema_version"
        const val KEY_ENERGY_SAVING = "energy_saving_mode"
        const val KEY_BACKGROUND = "background_connections"
        const val KEY_NOTIFICATIONS = "notifications"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_HIGH_CONTRAST = "high_contrast"
        const val KEY_REDUCE_MOTION = "reduce_motion"
        const val KEY_LANGUAGE = "language"
        const val KEY_OFFLINE_SPEECH = "offline_speech"
        const val KEY_NOTIFICATION_TOPICS = "notification_topics"
        const val KEY_NOTIFICATION_LEVEL = "notification_level"
    }
}

private fun String.toNotificationTopic(): SessionNotificationTopic? =
    runCatching { SessionNotificationTopic.valueOf(this) }.getOrNull()
