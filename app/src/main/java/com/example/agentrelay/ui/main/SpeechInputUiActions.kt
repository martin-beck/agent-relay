package com.example.agentrelay.ui.main

import androidx.compose.runtime.Immutable

@Immutable
internal data class SpeechInputUiActions(
    val selectModel: (String) -> Unit = {},
    val installModel: () -> Unit = {},
    val cancelModelInstall: () -> Unit = {},
    val requestStart: (String) -> Unit = {},
    val stop: (String) -> Unit = {},
    val cancel: (String) -> Unit = {},
    val useTranscript: (String) -> Unit = {},
    val dismiss: (String) -> Unit = {},
)
