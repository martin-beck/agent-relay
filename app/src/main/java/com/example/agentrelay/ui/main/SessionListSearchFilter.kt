/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.agentrelay.provider.api.AgentSessionState
import kotlinx.coroutines.delay

internal enum class SessionRecencyFilter {
    ANY,
    LAST_DAY,
}

internal data class SessionListSearchFilterState(
    val query: String = "",
    val agent: String? = null,
    val host: String? = null,
    val state: AgentSessionState? = null,
    val pinnedOnly: Boolean = false,
    val recency: SessionRecencyFilter = SessionRecencyFilter.ANY,
)

internal fun filterSessionList(
    sessions: List<SessionUiModel>,
    filters: SessionListSearchFilterState,
    nowEpochMillis: Long,
): List<SessionUiModel> {
    val query = filters.query.trim().lowercase()
    val recencyCutoff = nowEpochMillis - 24L * 60L * 60L * 1000L
    return sessions.filter { session ->
        val searchable = listOf(
            session.title.resolveForSearch(),
            session.preview,
            session.connectionLabel,
            session.connectionProviderName,
            session.agentProviderLabel,
            session.projectPath.orEmpty(),
        ).joinToString(" ").lowercase()
        query.isBlank() || searchable.contains(query)
    }.filter { session ->
        filters.agent == null || session.agentProviderLabel == filters.agent
    }.filter { session ->
        filters.host == null || session.connectionLabel == filters.host
    }.filter { session ->
        filters.state == null || session.agentState == filters.state
    }.filter { session ->
        !filters.pinnedOnly || session.isPinned
    }.filter { session ->
        filters.recency == SessionRecencyFilter.ANY ||
            (session.lastActivityAtEpochMillis ?: Long.MIN_VALUE) >= recencyCutoff
    }
}

private fun UiMessage.resolveForSearch(): String = when (this) {
    is UiMessage.Verbatim -> value
    is UiMessage.Localized -> resourceId.toString()
    is UiMessage.Plural -> resourceId.toString()
}

@Composable
internal fun SessionListSearchFilterControls(
    filters: SessionListSearchFilterState,
    resultCount: Int,
    onFiltersChanged: (SessionListSearchFilterState) -> Unit,
    modifier: Modifier = Modifier,
    availableAgents: List<String> = emptyList(),
    availableHosts: List<String> = emptyList(),
    availableStates: List<AgentSessionState> = emptyList(),
) {
    val hasFilters = filters != SessionListSearchFilterState()
    var query by remember { mutableStateOf(filters.query) }
    LaunchedEffect(filters.query) { query = filters.query }
    LaunchedEffect(query) {
        if (query != filters.query) {
            delay(250)
            onFiltersChanged(filters.copy(query = query))
        }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                stateDescription = "$resultCount sessions match"
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().testTag("session-search-field"),
            label = { Text("Search sessions") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = filters.pinnedOnly,
                onClick = { onFiltersChanged(filters.copy(pinnedOnly = !filters.pinnedOnly)) },
                label = { Text("Pinned") },
            )
            FilterChip(
                selected = filters.recency == SessionRecencyFilter.LAST_DAY,
                onClick = {
                    onFiltersChanged(
                        filters.copy(
                            recency = if (filters.recency == SessionRecencyFilter.LAST_DAY) {
                                SessionRecencyFilter.ANY
                            } else {
                                SessionRecencyFilter.LAST_DAY
                            },
                        ),
                    )
                },
                label = { Text("Recent") },
            )
        }
        availableAgents.forEach { agent ->
            FilterChip(
                selected = filters.agent == agent,
                onClick = {
                    onFiltersChanged(filters.copy(agent = if (filters.agent == agent) null else agent))
                },
                label = { Text("Agent: $agent") },
            )
        }
        availableHosts.forEach { host ->
            FilterChip(
                selected = filters.host == host,
                onClick = {
                    onFiltersChanged(filters.copy(host = if (filters.host == host) null else host))
                },
                label = { Text("Host: $host") },
            )
        }
        availableStates.forEach { state ->
            FilterChip(
                selected = filters.state == state,
                onClick = {
                    onFiltersChanged(filters.copy(state = if (filters.state == state) null else state))
                },
                label = { Text("State: ${state.name.lowercase()}") },
            )
        }
        if (hasFilters) {
            Button(
                onClick = { onFiltersChanged(SessionListSearchFilterState()) },
                modifier = Modifier.testTag("session-search-reset"),
            ) { Text("Reset filters") }
        }
        Text(
            text = if (resultCount == 0) "No sessions match these filters" else "$resultCount sessions",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("session-search-result-count"),
        )
    }
}
