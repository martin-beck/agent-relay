/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.compose.ui.unit.LayoutDirection

internal enum class HorizontalSwipeAction {
    NONE,
    REVEAL_START,
    REVEAL_END,
}

internal data class SwipeDecision(
    val action: HorizontalSwipeAction,
    val progress: Float,
)

/** Pure gesture contract shared by cards and menus; UI code can cancel without dispatching. */
internal fun resolveHorizontalSwipe(
    distancePx: Float,
    widthPx: Float,
    layoutDirection: LayoutDirection,
): SwipeDecision {
    if (widthPx <= 0f) return SwipeDecision(HorizontalSwipeAction.NONE, 0f)
    val progress = (kotlin.math.abs(distancePx) / widthPx).coerceIn(0f, 1f)
    if (progress < 0.35f) return SwipeDecision(HorizontalSwipeAction.NONE, progress)
    val towardEnd = if (layoutDirection == LayoutDirection.Ltr) distancePx < 0f else distancePx > 0f
    return SwipeDecision(
        action = if (towardEnd) HorizontalSwipeAction.REVEAL_END else HorizontalSwipeAction.REVEAL_START,
        progress = progress,
    )
}
