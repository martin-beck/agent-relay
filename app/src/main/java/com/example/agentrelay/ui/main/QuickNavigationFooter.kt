/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal enum class QuickNavigationDestinationId {
    SESSIONS,
    ATTENTION,
    CONNECTIONS,
    SETTINGS,
    HELP,
}

internal data class QuickNavigationDestination(
    val id: QuickNavigationDestinationId,
    val label: String,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
internal fun QuickNavigationFooter(
    destinations: List<QuickNavigationDestination>,
    modifier: Modifier = Modifier,
) {
    if (destinations.isEmpty()) return

    NavigationBar(
        modifier = modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding(),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .selectableGroup()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            destinations.forEach { destination ->
                NavigationBarItem(
                    selected = destination.selected,
                    onClick = destination.onClick,
                    enabled = destination.enabled,
                    modifier = Modifier
                        .testTag(QUICK_NAVIGATION_DESTINATION_PREFIX + destination.id.name.lowercase())
                        .semantics {
                            role = Role.Tab
                            selected = destination.selected
                        },
                    icon = {
                        Text(
                            text = destination.label.take(1),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    },
                    label = {
                        Text(text = destination.label)
                    },
                )
            }
        }
    }
}
