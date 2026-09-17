/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.notifications

import dev.agentrelay.session.api.SessionHubSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class SessionNotificationDispatchState {
    IDLE,
    READY,
    DELIVERY_BLOCKED,
}

internal class SessionNotificationRuntime(
    private val scope: CoroutineScope,
    sink: SessionNotificationSink,
    private val onFailure: () -> Unit,
    private val preferences: () -> SessionNotificationPreferences,
) {
    constructor(
        scope: CoroutineScope,
        sink: SessionNotificationSink,
        onFailure: () -> Unit,
    ) : this(scope, sink, onFailure, { SessionNotificationPreferences() })

    private val reconciler = SessionNotificationReconciler(sink)
    private val mutex = Mutex()
    private val mutableDispatchState = MutableStateFlow(SessionNotificationDispatchState.IDLE)

    val dispatchState: StateFlow<SessionNotificationDispatchState> =
        mutableDispatchState.asStateFlow()

    private var collectionJob: Job? = null
    private var latestSnapshot = SessionHubSnapshot()
    private var isForeground = true

    suspend fun attach(
        snapshots: StateFlow<SessionHubSnapshot>,
        initiallyForeground: Boolean,
    ) {
        mutex.withLock {
            check(collectionJob == null) { "Notification runtime is already attached" }
            isForeground = initiallyForeground
            latestSnapshot = snapshots.value
            applySuppressionLocked()
            collectionJob = scope.launch {
                snapshots.collect { snapshot ->
                    onSnapshot(snapshot)
                }
            }
        }
    }

    suspend fun enterForeground() {
        mutex.withLock {
            isForeground = true
            applySuppressionLocked()
        }
    }

    suspend fun enterBackground() {
        mutex.withLock {
            isForeground = false
            applyCurrentLocked()
        }
    }

    suspend fun retryCurrent() {
        mutex.withLock {
            applyCurrentLocked()
        }
    }

    private suspend fun onSnapshot(snapshot: SessionHubSnapshot) {
        mutex.withLock {
            latestSnapshot = snapshot
            applyCurrentLocked()
        }
    }

    private suspend fun applyCurrentLocked() {
        safelyApply {
            if (isForeground) {
                reconciler.suppress(latestSnapshot, preferences())
            } else {
                reconciler.reconcile(latestSnapshot, preferences())
            }
        }
    }

    private suspend fun applySuppressionLocked() {
        safelyApply {
            reconciler.suppress(latestSnapshot, preferences())
        }
    }

    private suspend fun safelyApply(
        operation: suspend () -> SessionNotificationReconciliation,
    ) {
        try {
            val result = operation()
            mutableDispatchState.value = if (result.failedNotificationKeys.isEmpty()) {
                SessionNotificationDispatchState.READY
            } else {
                SessionNotificationDispatchState.DELIVERY_BLOCKED
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            mutableDispatchState.value = SessionNotificationDispatchState.DELIVERY_BLOCKED
            onFailure()
        }
    }
}
