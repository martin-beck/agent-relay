package com.example.agentrelay.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared layout, interaction, and motion values for attention-first surfaces. */
@Immutable
data class AgentRelayThemeTokens(
    val spacing: AgentRelaySpacing = AgentRelaySpacing(),
    val motion: AgentRelayMotion = AgentRelayMotion(),
    val minimumTouchTarget: Dp = 48.dp,
)

@Immutable
data class AgentRelaySpacing(
    val none: Dp = 0.dp,
    val compact: Dp = 8.dp,
    val standard: Dp = 16.dp,
    val spacious: Dp = 24.dp,
    val section: Dp = 32.dp,
)

@Immutable
data class AgentRelayMotion(
    val shortMillis: Int = 150,
    val standardMillis: Int = 300,
)

val LocalAgentRelayThemeTokens =
    staticCompositionLocalOf { AgentRelayThemeTokens() }
