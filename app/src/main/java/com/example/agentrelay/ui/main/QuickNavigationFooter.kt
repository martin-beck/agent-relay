/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class QuickNavigationDestinationId {
    SESSIONS,
    PINNED,
    NEW_SESSION,
    SETTINGS,
    NOTIFICATIONS,
    ATTENTION,
    CONNECTIONS,
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

    val density = LocalDensity.current
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { hideFromAccessibility() }
            .imePadding()
            .navigationBarsPadding(),
        tonalElevation = 3.dp,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val destinationsPerRow = if (maxWidth < 400.dp && destinations.size > 4) 4 else destinations.size
            val rows = destinations.chunked(destinationsPerRow.coerceAtLeast(1))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp * density.fontScale.coerceAtLeast(1f) * rows.size)
                    .selectableGroup()
                    .semantics { hideFromAccessibility() }
                    .padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                rows.forEach { rowDestinations ->
                    Row(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        rowDestinations.forEach { destination ->
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .selectable(
                                        selected = destination.selected,
                                        enabled = destination.enabled,
                                        role = Role.Tab,
                                        onClick = destination.onClick,
                                    )
                                    .testTag(QUICK_NAVIGATION_DESTINATION_PREFIX + destination.id.name.lowercase())
                                    .semantics {
                                        selected = destination.selected
                                        contentDescription = destination.label
                                    },
                                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                Text(
                                    text = destination.label.take(1),
                                    modifier = Modifier.clearAndSetSemantics {},
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Text(
                                    text = destination.label,
                                    modifier = Modifier.clearAndSetSemantics {
                                        testTag = QUICK_NAVIGATION_LABEL_PREFIX + destination.id.name.lowercase()
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 8.sp / density.fontScale.coerceAtLeast(1f),
                                        lineHeight = 10.sp / density.fontScale.coerceAtLeast(1f),
                                    ),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
