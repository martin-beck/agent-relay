/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection

@androidx.compose.runtime.Composable
internal fun SwipeActionSurface(
    modifier: Modifier = Modifier,
    accessibilityActionLabel: String? = null,
    onAction: ((HorizontalSwipeAction) -> Unit)? = null,
    content: @androidx.compose.runtime.Composable BoxScope.() -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    var offset by remember { mutableFloatStateOf(0f) }
    val gestureModifier = modifier
        .graphicsLayer { translationX = offset }
        .pointerInput(layoutDirection, onAction) {
            detectHorizontalDragGestures(
                onHorizontalDrag = { change, amount ->
                    offset = (offset + amount).coerceIn(-size.width.toFloat(), size.width.toFloat())
                },
                onDragEnd = {
                    val decision = resolveHorizontalSwipe(offset, size.width.toFloat(), layoutDirection)
                    offset = 0f
                    if (decision.action != HorizontalSwipeAction.NONE) onAction?.invoke(decision.action)
                },
                onDragCancel = { offset = 0f },
            )
        }
        .semantics {
            if (accessibilityActionLabel != null && onAction != null) {
                customActions = listOf(CustomAccessibilityAction(accessibilityActionLabel) {
                    onAction(HorizontalSwipeAction.REVEAL_END)
                    true
                })
            }
        }
    Box(modifier = gestureModifier, content = content)
}
