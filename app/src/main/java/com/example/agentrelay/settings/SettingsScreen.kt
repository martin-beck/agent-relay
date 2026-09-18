/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.agentrelay.notifications.SessionNotificationLevel
import com.example.agentrelay.notifications.SessionNotificationTopic

internal enum class SettingKind { TOGGLE, CHOICE }

internal data class SettingDefinition(
    val section: String,
    val title: String,
    val description: String,
    val kind: SettingKind,
    val value: (AppSettings) -> String,
    val update: (AppSettings, String) -> AppSettings,
)

@Suppress("MaxLineLength")
internal val settingDefinitions = listOf(
    SettingDefinition("Performance and battery", "Energy-saving mode", "Use quiet synchronization, short-lived background transport leases, and lifecycle cleanup by default.", SettingKind.TOGGLE, { it.energySavingMode.toString() }) { s, v -> s.copy(energySavingMode = v.toBoolean()) },
    SettingDefinition("Connection", "Background connections", "Keep explicitly enabled connections active when the app is backgrounded.", SettingKind.TOGGLE, { it.backgroundConnections.toString() }) { s, v -> s.copy(backgroundConnections = v.toBoolean()) },
    SettingDefinition("Sessions", "Restore session drafts", "Drafts are retained in the encrypted session repository until delivery succeeds.", SettingKind.TOGGLE, { "true" }) { s, _ -> s },
    SettingDefinition("Synchronization", "Sync on Wi-Fi only", "Limit future synchronization adapters to unmetered networks.", SettingKind.TOGGLE, { "false" }) { s, _ -> s },
    SettingDefinition("Appearance and accessibility", "Dynamic color", "Use the device color palette when available.", SettingKind.TOGGLE, { it.dynamicColor.toString() }) { s, v -> s.copy(dynamicColor = v.toBoolean()) },
    SettingDefinition("Appearance and accessibility", "High contrast", "Increase contrast for controls and status text.", SettingKind.TOGGLE, { it.highContrast.toString() }) { s, v -> s.copy(highContrast = v.toBoolean()) },
    SettingDefinition("Appearance and accessibility", "Reduce motion", "Prefer minimal transition animation.", SettingKind.TOGGLE, { it.reduceMotion.toString() }) { s, v -> s.copy(reduceMotion = v.toBoolean()) },
    SettingDefinition("Notifications", "Session notifications", "Allow privacy-safe background session notifications.", SettingKind.TOGGLE, { it.notifications.toString() }) { s, v -> s.copy(notifications = v.toBoolean()) },
    SettingDefinition("Notifications", "Agent feedback", "Notify about failures and new agent output. Failures remain visible as safety notifications.", SettingKind.TOGGLE, { it.notificationPreferences.topics.contains(SessionNotificationTopic.AGENT_FEEDBACK).toString() }) { s, v -> s.withNotificationTopic(SessionNotificationTopic.AGENT_FEEDBACK, v.toBoolean()) },
    SettingDefinition("Notifications", "Decisions", "Notify when an approval or question needs your response.", SettingKind.TOGGLE, { it.notificationPreferences.topics.contains(SessionNotificationTopic.USER_DECISIONS).toString() }) { s, v -> s.withNotificationTopic(SessionNotificationTopic.USER_DECISIONS, v.toBoolean()) },
    SettingDefinition("Notifications", "Completions", "Notify when an agent turn completes.", SettingKind.TOGGLE, { it.notificationPreferences.topics.contains(SessionNotificationTopic.COMPLETION).toString() }) { s, v -> s.withNotificationTopic(SessionNotificationTopic.COMPLETION, v.toBoolean()) },
    SettingDefinition("Notifications", "Blocked tasks", "Notify about work that cannot continue.", SettingKind.TOGGLE, { it.notificationPreferences.topics.contains(SessionNotificationTopic.BLOCKED_TASKS).toString() }) { s, v -> s.withNotificationTopic(SessionNotificationTopic.BLOCKED_TASKS, v.toBoolean()) },
    SettingDefinition("Notifications", "Detailed activity", "Include routine agent output when selected; disconnect and reconnect remain silent.", SettingKind.TOGGLE, { (it.notificationPreferences.level == SessionNotificationLevel.ALL_ACTIVITY).toString() }) { s, v -> s.copy(notificationPreferences = s.notificationPreferences.copy(level = if (v.toBoolean()) SessionNotificationLevel.ALL_ACTIVITY else SessionNotificationLevel.HIGH_LEVEL)) },
    SettingDefinition("Privacy", "Protected data", "Credentials, prompts, transcripts, and host details are never stored in this settings surface.", SettingKind.CHOICE, { "App-private storage" }) { s, _ -> s },
    SettingDefinition("Language", "Interface language", "Follow the device language until a supported override is selected.", SettingKind.CHOICE, { it.language }) { s, v -> s.copy(language = v) },
    SettingDefinition("Speech", "Offline speech input", "Allow the separately permission-gated on-device speech feature.", SettingKind.TOGGLE, { it.offlineSpeech.toString() }) { s, v -> s.copy(offlineSpeech = v.toBoolean()) },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(store: SettingsStore, onBack: () -> Unit) {
    var settings by rememberSaveable { mutableStateOf(store.read()) }
    var query by rememberSaveable { mutableStateOf("") }
    val visible = settingDefinitions.filter { definition ->
        query.isBlank() || listOf(definition.section, definition.title, definition.description)
            .any { it.contains(query, ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = onBack) { Text("Back") }
            Button(onClick = {
                store.reset()
                settings = AppSettings()
            }) { Text("Reset settings") }
        }
        Text("Settings", modifier = Modifier.semantics { heading() })
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search settings") },
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            visible.groupBy { it.section }.forEach { (section, definitions) ->
                item(key = "heading:$section") { Text(section, modifier = Modifier.semantics { heading() }) }
                items(definitions, key = { it.title }) { definition ->
                    val value = definition.value(settings)
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(definition.title)
                                Text(definition.description)
                            }
                            if (definition.kind == SettingKind.TOGGLE) {
                                Switch(checked = value.toBoolean(), onCheckedChange = { checked ->
                                    settings = definition.update(settings, checked.toString())
                                    store.write(settings)
                                })
                            } else {
                                Text(value)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun AppSettings.withNotificationTopic(
    topic: SessionNotificationTopic,
    enabled: Boolean,
): AppSettings {
    val topics = notificationPreferences.topics.toMutableSet()
    if (enabled) topics += topic else topics -= topic
    return copy(notificationPreferences = notificationPreferences.copy(topics = topics))
}
