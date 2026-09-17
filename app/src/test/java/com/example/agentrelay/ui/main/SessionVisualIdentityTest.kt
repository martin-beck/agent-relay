/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionVisualIdentityTest {
    @Test
    fun identityColorIsStableAndReadable() {
        val first = sessionIdentitySwatch("Codex", "build-host", darkTheme = false)
        assertEquals(first, sessionIdentitySwatch("Codex", "build-host", darkTheme = false))
        assertTrue(first.contrastRatio >= 4.5)
    }

    @Test
    fun darkAndLightIdentityColorsRemainReadable() {
        listOf(false, true).forEach { darkTheme ->
            listOf("Codex", "Claude", "Aider").forEach { agent ->
                assertTrue(sessionIdentitySwatch(agent, "host", darkTheme).contrastRatio >= 4.5)
            }
        }
    }

    @Test
    fun paletteForegroundIsUsedForIdentitySwatch() {
        val swatch = sessionIdentitySwatch(
            "Codex",
            "host",
            SessionIdentityPalette(listOf(androidx.compose.ui.graphics.Color.Black), listOf(androidx.compose.ui.graphics.Color.Yellow)),
        )
        assertEquals(androidx.compose.ui.graphics.Color.Yellow, swatch.foreground)
    }

    @Test
    fun previewIsCollapsedBoundedAndControlSafe() {
        val preview = boundedSessionPreview(" first\nsecond\t\u0000" + "x".repeat(200), 10_000L, 10_001L)
        assertEquals(SessionPreviewState.AVAILABLE, preview.state)
        assertEquals(120, preview.text?.length)
        assertTrue(preview.text!!.none(Char::isISOControl))
        assertTrue(!preview.text.contains("  "))
    }

    @Test
    fun previewDistinguishesUnavailableAndStale() {
        assertEquals(SessionPreviewState.UNAVAILABLE, boundedSessionPreview("  \n", null, 100L).state)
        assertEquals(
            SessionPreviewState.STALE,
            boundedSessionPreview("Older result", 0L, 24L * 60L * 60L * 1000L).state,
        )
    }
}
