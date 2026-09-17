/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.compose.ui.graphics.Color
import java.security.MessageDigest
import kotlin.math.pow

internal enum class SessionPreviewState { AVAILABLE, UNAVAILABLE, STALE }

internal data class SessionPreview(val text: String?, val state: SessionPreviewState)

private const val PREVIEW_MAX_CHARS = 120
private const val PREVIEW_STALE_AFTER_MILLIS = 24L * 60L * 60L * 1000L

internal fun boundedSessionPreview(raw: String, lastActivityAtEpochMillis: Long?, nowEpochMillis: Long): SessionPreview {
    val normalized = raw
        .map { character -> if (character.isISOControl()) ' ' else character }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(PREVIEW_MAX_CHARS)
        .trimEnd()
    if (normalized.isEmpty()) return SessionPreview(null, SessionPreviewState.UNAVAILABLE)
    val stale = lastActivityAtEpochMillis != null &&
        nowEpochMillis >= lastActivityAtEpochMillis &&
        nowEpochMillis - lastActivityAtEpochMillis >= PREVIEW_STALE_AFTER_MILLIS
    return SessionPreview(normalized, if (stale) SessionPreviewState.STALE else SessionPreviewState.AVAILABLE)
}

internal data class SessionIdentitySwatch(
    val background: Color,
    val foreground: Color,
    val contrastRatio: Double,
)

private val LIGHT_IDENTITY_COLORS = listOf(
    Color(0xFF006874),
    Color(0xFF6750A4),
    Color(0xFF8C4A00),
    Color(0xFF006E1C),
    Color(0xFF984061),
    Color(0xFF405F90),
)
private val DARK_IDENTITY_COLORS = listOf(
    Color(0xFF4FD8E8),
    Color(0xFFD0BCFF),
    Color(0xFFFFB77D),
    Color(0xFF71DF83),
    Color(0xFFFFB0C8),
    Color(0xFFA9C7FF),
)

internal fun sessionIdentitySwatch(agent: String, host: String, darkTheme: Boolean): SessionIdentitySwatch {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest("$agent\u0000$host".toByteArray(Charsets.UTF_8))
    val index = (digest[0].toInt() and 0xFF) % LIGHT_IDENTITY_COLORS.size
    val background = (if (darkTheme) DARK_IDENTITY_COLORS else LIGHT_IDENTITY_COLORS)[index]
    val foreground = if (contrastRatio(background, Color.White) >= contrastRatio(background, Color.Black)) {
        Color.White
    } else {
        Color.Black
    }
    return SessionIdentitySwatch(background, foreground, contrastRatio(background, foreground))
}

internal fun contrastRatio(first: Color, second: Color): Double {
    fun channel(value: Float): Double {
        val normalized = value.toDouble()
        return if (normalized <= 0.03928) normalized / 12.92 else ((normalized + 0.055) / 1.055).pow(2.4)
    }
    fun luminance(color: Color): Double =
        0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    val lighter = maxOf(luminance(first), luminance(second))
    val darker = minOf(luminance(first), luminance(second))
    return (lighter + 0.05) / (darker + 0.05)
}
