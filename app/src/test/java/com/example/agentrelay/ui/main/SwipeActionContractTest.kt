/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeActionContractTest {
    @Test
    fun belowThresholdCancelsAndReportsBoundedProgress() {
        assertEquals(
            SwipeDecision(HorizontalSwipeAction.NONE, 0.2f),
            resolveHorizontalSwipe(20f, 100f, LayoutDirection.Ltr),
        )
    }

    @Test
    fun thresholdMapsToEndInLtrAndReversesInRtl() {
        assertEquals(
            HorizontalSwipeAction.REVEAL_END,
            resolveHorizontalSwipe(-40f, 100f, LayoutDirection.Ltr).action,
        )
        assertEquals(
            HorizontalSwipeAction.REVEAL_END,
            resolveHorizontalSwipe(40f, 100f, LayoutDirection.Rtl).action,
        )
    }

    @Test
    fun invalidWidthFailsClosed() {
        assertEquals(
            SwipeDecision(HorizontalSwipeAction.NONE, 0f),
            resolveHorizontalSwipe(100f, 0f, LayoutDirection.Ltr),
        )
    }
}
