/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.background

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class BackgroundTransportState {
    STOPPED,
    STARTING,
    ACTIVE,
    STOPPING,
    START_FAILED,
}

internal fun interface BackgroundTransportStarter {
    fun dispatch(action: BackgroundTransportAction)
}

internal enum class BackgroundTransportAction {
    START,
    STOP,
}

internal class BackgroundTransportController(
    private val starter: BackgroundTransportStarter,
) {
    private val mutableState = MutableStateFlow(BackgroundTransportState.STOPPED)

    val state: StateFlow<BackgroundTransportState> = mutableState.asStateFlow()

    @Synchronized
    fun start() {
        when (mutableState.value) {
            BackgroundTransportState.STOPPED,
            BackgroundTransportState.START_FAILED,
            -> Unit
            BackgroundTransportState.STARTING,
            BackgroundTransportState.ACTIVE,
            BackgroundTransportState.STOPPING,
            -> return
        }
        mutableState.value = BackgroundTransportState.STARTING
        try {
            starter.dispatch(BackgroundTransportAction.START)
        } catch (_: RuntimeException) {
            mutableState.value = BackgroundTransportState.START_FAILED
        }
    }

    @Synchronized
    fun stop() {
        if (mutableState.value == BackgroundTransportState.STOPPED ||
            mutableState.value == BackgroundTransportState.STOPPING
        ) {
            return
        }
        val previousState = mutableState.value
        mutableState.value = BackgroundTransportState.STOPPING
        try {
            starter.dispatch(BackgroundTransportAction.STOP)
        } catch (_: RuntimeException) {
            mutableState.value = previousState
        }
    }

    @Synchronized
    fun serviceActivated() {
        if (mutableState.value == BackgroundTransportState.STARTING) {
            mutableState.value = BackgroundTransportState.ACTIVE
        }
    }

    @Synchronized
    fun serviceRestarting() {
        if (mutableState.value == BackgroundTransportState.STOPPED ||
            mutableState.value == BackgroundTransportState.START_FAILED
        ) {
            mutableState.value = BackgroundTransportState.STARTING
        }
    }

    @Synchronized
    fun serviceStopped() {
        mutableState.value = BackgroundTransportState.STOPPED
    }

    @Synchronized
    fun serviceFailed() {
        if (mutableState.value == BackgroundTransportState.STARTING ||
            mutableState.value == BackgroundTransportState.ACTIVE
        ) {
            mutableState.value = BackgroundTransportState.START_FAILED
        }
    }
}

internal class AndroidBackgroundTransportStarter(
    context: Context,
) : BackgroundTransportStarter {
    private val appContext = context.applicationContext

    override fun dispatch(action: BackgroundTransportAction) {
        val intent = Intent(appContext, RemoteSessionForegroundService::class.java)
            .setAction(action.intentAction(appContext.packageName))
        when (action) {
            BackgroundTransportAction.START ->
                ContextCompat.startForegroundService(appContext, intent)
            BackgroundTransportAction.STOP ->
                appContext.startService(intent)
        }
    }
}

internal fun BackgroundTransportAction.intentAction(packageName: String): String = when (this) {
    BackgroundTransportAction.START -> "$packageName.background.START"
    BackgroundTransportAction.STOP -> "$packageName.background.STOP"
}

internal fun String?.backgroundTransportAction(packageName: String): BackgroundTransportAction? =
    BackgroundTransportAction.entries.firstOrNull { action ->
        this == action.intentAction(packageName)
    }
