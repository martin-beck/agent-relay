package com.example.agentrelay.notifications

import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.session.api.SessionActivity
import dev.agentrelay.session.api.SessionActivityType
import dev.agentrelay.session.api.SessionHubSnapshot
import dev.agentrelay.session.api.SessionLocator
import dev.agentrelay.session.api.SessionObservation
import dev.agentrelay.session.api.SessionRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionNotificationRuntimeTest {
    @Test
    fun foregroundSnapshotBecomesBaselineAndOnlyNewBackgroundActivityPosts() = runTest {
        val original = activity("approval", SessionActivityType.APPROVAL_REQUIRED, 1L)
        val snapshots = MutableStateFlow(snapshot(original))
        val sink = RecordingSink()
        val runtime = SessionNotificationRuntime(backgroundScope, sink) {
            error("Unexpected notification runtime failure")
        }

        runtime.attach(snapshots, initiallyForeground = true)
        runCurrent()
        runtime.enterBackground()
        snapshots.value = snapshot(
            original,
            activity("failure", SessionActivityType.FAILURE, 2L),
        )
        runCurrent()

        assertEquals(1, sink.shown.size)
        assertEquals(SessionNotificationKind.FAILURE, sink.shown.single().kind)
        assertEquals(SessionNotificationDispatchState.READY, runtime.dispatchState.value)
    }

    @Test
    fun enteringForegroundCancelsAndPreventsUnchangedRedispatch() = runTest {
        val snapshots = MutableStateFlow(SessionHubSnapshot())
        val sink = RecordingSink()
        val runtime = SessionNotificationRuntime(backgroundScope, sink) {
            error("Unexpected notification runtime failure")
        }
        runtime.attach(snapshots, initiallyForeground = false)
        runCurrent()
        snapshots.value = snapshot(activity("approval", SessionActivityType.APPROVAL_REQUIRED, 1L))
        runCurrent()
        val notificationKey = sink.shown.single().notificationKey

        runtime.enterForeground()
        runtime.enterBackground()

        assertTrue(notificationKey in sink.cancelled)
        assertEquals(1, sink.shown.size)
    }

    @Test
    fun blockedDeliveryRemainsRetryable() = runTest {
        val snapshots = MutableStateFlow(SessionHubSnapshot())
        val sink = RecordingSink(failShowAttempts = 1)
        val runtime = SessionNotificationRuntime(backgroundScope, sink) {
            error("Unexpected notification runtime failure")
        }
        runtime.attach(snapshots, initiallyForeground = false)
        runCurrent()

        snapshots.value = snapshot(activity("failure", SessionActivityType.FAILURE, 1L))
        runCurrent()
        assertEquals(
            SessionNotificationDispatchState.DELIVERY_BLOCKED,
            runtime.dispatchState.value,
        )

        runtime.retryCurrent()

        assertEquals(SessionNotificationDispatchState.READY, runtime.dispatchState.value)
        assertEquals(1, sink.shown.size)
    }

    private class RecordingSink(
        private var failShowAttempts: Int = 0,
    ) : SessionNotificationSink {
        val shown = mutableListOf<ProjectedSessionNotification>()
        val cancelled = mutableListOf<String>()

        override suspend fun show(notification: ProjectedSessionNotification) {
            if (failShowAttempts > 0) {
                failShowAttempts -= 1
                error("Sink unavailable")
            }
            shown += notification
        }

        override suspend fun cancel(notificationKey: String) {
            cancelled += notificationKey
        }
    }

    private fun snapshot(vararg activities: SessionActivity): SessionHubSnapshot =
        SessionHubSnapshot(
            sessions = listOf(session()),
            activities = activities.toList(),
        )

    private fun session(): SessionRecord = SessionRecord(
        observation = SessionObservation(
            locator = LOCATOR,
            connectionLabel = "Connection",
            connectionTarget = "Target",
            projectPath = null,
            agentProviderLabel = "Agent",
            title = null,
            preview = "",
            agentState = AgentSessionState.IDLE,
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 0L,
        ),
    )

    private fun activity(
        id: String,
        type: SessionActivityType,
        occurredAtEpochMillis: Long,
    ): SessionActivity = SessionActivity(
        id = id,
        locator = LOCATOR,
        type = type,
        summary = "Summary",
        eventAnchorId = null,
        occurredAtEpochMillis = occurredAtEpochMillis,
    )

    private companion object {
        val LOCATOR = SessionLocator(
            connectionProviderId = ConnectionProviderId("provider"),
            connectionProfileId = ConnectionProfileId("profile"),
            agentProviderId = AgentProviderId("agent"),
            agentSessionId = AgentSessionId("session"),
        )
    }
}
