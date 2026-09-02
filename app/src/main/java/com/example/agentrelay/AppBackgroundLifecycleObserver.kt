package com.example.agentrelay

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class AppBackgroundLifecycleState {
    FOREGROUND,
    BACKGROUND,
    FOREGROUND_TRANSITION_FAILED,
    BACKGROUND_TRANSITION_FAILED,
}

internal class AppBackgroundLifecycleObserver(
    private val scope: CoroutineScope,
    private val enterForeground: suspend () -> Unit,
    private val enterBackground: suspend () -> Unit,
    private val onFailure: (AppBackgroundLifecycleState) -> Unit = {},
) : DefaultLifecycleObserver {
    private val transitionMutex = Mutex()
    private val mutableState = MutableStateFlow(AppBackgroundLifecycleState.FOREGROUND)

    internal val state: StateFlow<AppBackgroundLifecycleState> = mutableState.asStateFlow()

    @Volatile
    private var requestedForeground = true
    private var appliedForeground = true

    override fun onStart(owner: LifecycleOwner) {
        moveToForeground()
    }

    override fun onStop(owner: LifecycleOwner) {
        moveToBackground()
    }

    internal fun moveToForeground() {
        request(foreground = true)
    }

    internal fun moveToBackground() {
        request(foreground = false)
    }

    private fun request(foreground: Boolean) {
        requestedForeground = foreground
        scope.launch {
            transitionMutex.withLock {
                if (requestedForeground != foreground || appliedForeground == foreground) {
                    return@withLock
                }
                try {
                    if (foreground) {
                        enterForeground()
                    } else {
                        enterBackground()
                    }
                    appliedForeground = foreground
                    mutableState.value = if (foreground) {
                        AppBackgroundLifecycleState.FOREGROUND
                    } else {
                        AppBackgroundLifecycleState.BACKGROUND
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    val failureState = if (foreground) {
                        AppBackgroundLifecycleState.FOREGROUND_TRANSITION_FAILED
                    } else {
                        AppBackgroundLifecycleState.BACKGROUND_TRANSITION_FAILED
                    }
                    mutableState.value = failureState
                    onFailure(failureState)
                }
            }
        }
    }
}
