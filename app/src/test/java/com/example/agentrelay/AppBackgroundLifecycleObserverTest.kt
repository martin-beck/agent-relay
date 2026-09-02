package com.example.agentrelay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppBackgroundLifecycleObserverTest {
    @Test
    fun slowBackgroundTransitionCompletesBeforeNewerForegroundResume() = runTest {
        val events = mutableListOf<String>()
        val enteredBackground = CompletableDeferred<Unit>()
        val releaseBackground = CompletableDeferred<Unit>()
        val observer = AppBackgroundLifecycleObserver(
            scope = this,
            enterForeground = { events += "foreground" },
            enterBackground = {
                events += "background-start"
                enteredBackground.complete(Unit)
                releaseBackground.await()
                events += "background-end"
            },
        )

        assertEquals(AppBackgroundLifecycleState.FOREGROUND, observer.state.value)
        observer.moveToBackground()
        runCurrent()
        enteredBackground.await()
        observer.moveToForeground()
        runCurrent()

        assertEquals(AppBackgroundLifecycleState.FOREGROUND, observer.state.value)
        assertEquals(listOf("background-start"), events)

        releaseBackground.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            listOf("background-start", "background-end", "foreground"),
            events,
        )
        assertEquals(AppBackgroundLifecycleState.FOREGROUND, observer.state.value)
    }

    @Test
    fun obsoleteAndDuplicateTransitionsAreCoalesced() = runTest {
        val events = mutableListOf<String>()
        val observer = AppBackgroundLifecycleObserver(
            scope = this,
            enterForeground = { events += "foreground" },
            enterBackground = { events += "background" },
        )

        observer.moveToBackground()
        observer.moveToForeground()
        advanceUntilIdle()

        assertEquals(emptyList<String>(), events)

        observer.moveToBackground()
        observer.moveToBackground()
        advanceUntilIdle()
        assertEquals(AppBackgroundLifecycleState.BACKGROUND, observer.state.value)
        observer.moveToForeground()
        observer.moveToForeground()
        advanceUntilIdle()

        assertEquals(listOf("background", "foreground"), events)
        assertEquals(AppBackgroundLifecycleState.FOREGROUND, observer.state.value)
    }

    @Test
    fun failedTransitionIsIsolatedAndCanBeRetried() = runTest {
        var attempts = 0
        var failures = 0
        val failureStates = mutableListOf<AppBackgroundLifecycleState>()
        val observer = AppBackgroundLifecycleObserver(
            scope = this,
            enterForeground = {},
            enterBackground = {
                attempts += 1
                error("sensitive provider detail")
            },
            onFailure = {
                failures += 1
                failureStates += it
            },
        )

        observer.moveToBackground()
        advanceUntilIdle()
        assertEquals(
            AppBackgroundLifecycleState.BACKGROUND_TRANSITION_FAILED,
            observer.state.value,
        )
        observer.moveToBackground()
        advanceUntilIdle()

        assertEquals(2, attempts)
        assertEquals(2, failures)
        assertEquals(
            listOf(
                AppBackgroundLifecycleState.BACKGROUND_TRANSITION_FAILED,
                AppBackgroundLifecycleState.BACKGROUND_TRANSITION_FAILED,
            ),
            failureStates,
        )
    }
}
