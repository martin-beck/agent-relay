/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.visual

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class ViewportClass { COMPACT, MEDIUM, EXPANDED }

@Serializable
enum class TextScale { NORMAL, LARGE, EXTRA_LARGE }

@Serializable
data class VisualDensityBudget(
    val maxContentGroups: Int,
    val maxScrollsToPrimaryAction: Int,
    val maxClippedElements: Int = 0,
    val maxOverlappingElements: Int = 0,
    val maxTruncatedLabels: Int = 0,
    val minTouchTargetDp: Int = 48,
    val minBodyTextSp: Int = 14,
) {
    init {
        require(maxContentGroups >= 1)
        require(maxScrollsToPrimaryAction >= 0)
        require(maxClippedElements >= 0 && maxOverlappingElements >= 0)
        require(maxTruncatedLabels >= 0 && minTouchTargetDp >= 48 && minBodyTextSp >= 12)
    }

    fun accepts(measure: VisualDensityMeasure): Boolean =
        measure.primaryActionVisible &&
            measure.contentGroups <= maxContentGroups &&
            measure.scrollsToPrimaryAction <= maxScrollsToPrimaryAction &&
            measure.clippedElements <= maxClippedElements &&
            measure.overlappingElements <= maxOverlappingElements &&
            measure.truncatedLabels <= maxTruncatedLabels &&
            measure.minTouchTargetDp >= minTouchTargetDp &&
            measure.bodyTextSp >= minBodyTextSp
}

@Serializable
data class VisualDensityMeasure(
    val contentGroups: Int,
    val scrollsToPrimaryAction: Int,
    val clippedElements: Int = 0,
    val overlappingElements: Int = 0,
    val truncatedLabels: Int = 0,
    val minTouchTargetDp: Int = 48,
    val bodyTextSp: Int = 14,
    val primaryActionVisible: Boolean = true,
) {
    init {
        require(contentGroups >= 0 && scrollsToPrimaryAction >= 0)
        require(clippedElements >= 0 && overlappingElements >= 0 && truncatedLabels >= 0)
        require(minTouchTargetDp > 0 && bodyTextSp > 0)
    }
}

@Serializable
data class VisualLayoutContract(
    val id: String,
    val purpose: String,
    val viewport: ViewportClass,
    val supportedTextScales: Set<TextScale>,
    val hierarchy: List<String>,
    val primaryAction: String,
    val budget: VisualDensityBudget,
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9-]{2,63}")))
        require(purpose.isNotBlank() && purpose.length <= 160)
        require(supportedTextScales.isNotEmpty())
        require(hierarchy.isNotEmpty() && hierarchy.all { it.isNotBlank() && it.length <= 80 })
        require(primaryAction.isNotBlank() && primaryAction.length <= 80)
    }

    fun accepts(measure: VisualDensityMeasure): Boolean = budget.accepts(measure)
}

@Serializable
data class VisualDensityCatalog(val schemaVersion: Int, val layouts: List<VisualLayoutContract>) {
    init {
        require(schemaVersion == 1)
        require(layouts.isNotEmpty())
        require(layouts.map(VisualLayoutContract::id).toSet().size == layouts.size)
    }

    fun layout(id: String): VisualLayoutContract = layouts.first { it.id == id }
    fun canonicalJson(): String = JSON.encodeToString(this)

    companion object {
        private val JSON = Json {
            encodeDefaults = true
            prettyPrint = true
            explicitNulls = false
        }

        val current = VisualDensityCatalog(
            schemaVersion = 1,
            layouts = listOf(
                VisualLayoutContract(
                    id = "session-hub-compact",
                    purpose = "Choose a connection or session on a narrow viewport.",
                    viewport = ViewportClass.COMPACT,
                    supportedTextScales = setOf(TextScale.NORMAL, TextScale.LARGE, TextScale.EXTRA_LARGE),
                    hierarchy = listOf("screen-title", "attention-items", "connections", "sessions"),
                    primaryAction = "open-selected-session",
                    budget = VisualDensityBudget(6, 2, minBodyTextSp = 14),
                ),
                VisualLayoutContract(
                    id = "session-hub-expanded",
                    purpose = "Scan connections and sessions beside contextual details.",
                    viewport = ViewportClass.EXPANDED,
                    supportedTextScales = setOf(TextScale.NORMAL, TextScale.LARGE, TextScale.EXTRA_LARGE),
                    hierarchy = listOf("screen-title", "navigation-pane", "detail-pane"),
                    primaryAction = "open-selected-session",
                    budget = VisualDensityBudget(8, 1, minBodyTextSp = 14),
                ),
                VisualLayoutContract(
                    id = "connection-form-large-text",
                    purpose = "Edit one connection without losing validation context.",
                    viewport = ViewportClass.COMPACT,
                    supportedTextScales = setOf(TextScale.LARGE, TextScale.EXTRA_LARGE),
                    hierarchy = listOf("form-title", "identity-fields", "authentication", "save-action"),
                    primaryAction = "save-connection",
                    budget = VisualDensityBudget(5, 3, minBodyTextSp = 16),
                ),
            ),
        )
    }
}
