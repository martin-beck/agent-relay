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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SessionNotificationReconcilerTest {
    @Test
    fun repeatedSnapshotDoesNotRepeatSuccessfulSinkOperations() = runTest {
        val sink = RecordingSink()
        val reconciler = SessionNotificationReconciler(sink)
        val snapshot = snapshot(activity("approval", SessionActivityType.APPROVAL_REQUIRED))

        assertEquals(
            SessionNotificationReconciliation(shown = 1, cancelled = 0, failedNotificationKeys = emptySet()),
            reconciler.reconcile(snapshot),
        )
        assertEquals(
            SessionNotificationReconciliation(shown = 0, cancelled = 0, failedNotificationKeys = emptySet()),
            reconciler.reconcile(snapshot),
        )
        assertEquals(1, sink.shown.size)
        assertTrue(sink.cancelled.isEmpty())
    }

    @Test
    fun activityRemovalCancelsTheStableNotificationKey() = runTest {
        val sink = RecordingSink()
        val reconciler = SessionNotificationReconciler(sink)
        reconciler.reconcile(snapshot(activity("approval", SessionActivityType.APPROVAL_REQUIRED)))
        val notificationKey = sink.shown.single().notificationKey

        val result = reconciler.reconcile(snapshot())

        assertEquals(
            SessionNotificationReconciliation(shown = 0, cancelled = 1, failedNotificationKeys = emptySet()),
            result,
        )
        assertEquals(listOf(notificationKey), sink.cancelled)
    }

    @Test
    fun failedOperationsRemainRetryableWithoutExposingSinkDetails() = runTest {
        val sink = RecordingSink(failShowAttempts = 1, failCancelAttempts = 1)
        val reconciler = SessionNotificationReconciler(sink)
        val active = snapshot(activity("approval", SessionActivityType.APPROVAL_REQUIRED))

        val first = reconciler.reconcile(active)
        val notificationKey = first.failedNotificationKeys.single()
        assertEquals(0, first.shown)
        assertTrue(first.failedNotificationKeys.single().matches(SHA_256))
        assertEquals(1, reconciler.reconcile(active).shown)

        val failedCancel = reconciler.reconcile(snapshot())
        assertEquals(setOf(notificationKey), failedCancel.failedNotificationKeys)
        assertEquals(1, reconciler.reconcile(snapshot()).cancelled)
    }

    @Test
    fun suppressionCancelsVisibleNotificationAndKeepsCurrentSnapshotAsBaseline() = runTest {
        val sink = RecordingSink()
        val reconciler = SessionNotificationReconciler(sink)
        val active = snapshot(activity("approval", SessionActivityType.APPROVAL_REQUIRED))
        reconciler.reconcile(active)
        val notificationKey = sink.shown.single().notificationKey

        val suppressed = reconciler.suppress(active)

        assertEquals(
            SessionNotificationReconciliation(
                shown = 0,
                cancelled = 1,
                failedNotificationKeys = emptySet(),
            ),
            suppressed,
        )
        assertEquals(listOf(notificationKey), sink.cancelled)
        assertEquals(0, reconciler.reconcile(active).shown)
    }

    @Test
    fun suppressionBaselineAllowsOnlyNewActivityAfterBackgrounding() = runTest {
        val sink = RecordingSink()
        val reconciler = SessionNotificationReconciler(sink)
        val original = activity("approval", SessionActivityType.APPROVAL_REQUIRED)
        reconciler.suppress(snapshot(original))

        val result = reconciler.reconcile(
            snapshot(
                original,
                activity("failure", SessionActivityType.FAILURE),
            ),
        )

        assertEquals(1, result.shown)
        assertEquals(1, sink.shown.size)
        assertEquals(SessionNotificationKind.FAILURE, sink.shown.single().kind)
    }

    @Test
    fun failedSuppressionCancellationIsRetried() = runTest {
        val sink = RecordingSink(failCancelAttempts = 1)
        val reconciler = SessionNotificationReconciler(sink)
        val active = snapshot(activity("approval", SessionActivityType.APPROVAL_REQUIRED))

        assertEquals(1, reconciler.suppress(active).failedNotificationKeys.size)
        assertEquals(1, reconciler.suppress(active).cancelled)
    }

    @Test
    fun structuredCancellationIsNotConvertedIntoARecoverableFailure() = runTest {
        val reconciler = SessionNotificationReconciler(
            object : SessionNotificationSink {
                override suspend fun show(notification: ProjectedSessionNotification) {
                    throw CancellationException("cancelled")
                }

                override suspend fun cancel(notificationKey: String) = Unit
            },
        )

        try {
            reconciler.reconcile(snapshot(activity("approval", SessionActivityType.APPROVAL_REQUIRED)))
            fail("Expected structured cancellation")
        } catch (_: CancellationException) {
            Unit
        }
    }

    private class RecordingSink(
        private var failShowAttempts: Int = 0,
        private var failCancelAttempts: Int = 0,
    ) : SessionNotificationSink {
        val shown = mutableListOf<ProjectedSessionNotification>()
        val cancelled = mutableListOf<String>()

        override suspend fun show(notification: ProjectedSessionNotification) {
            if (failShowAttempts > 0) {
                failShowAttempts -= 1
                error("sensitive sink failure")
            }
            shown += notification
        }

        override suspend fun cancel(notificationKey: String) {
            if (failCancelAttempts > 0) {
                failCancelAttempts -= 1
                error("sensitive sink failure")
            }
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
    ): SessionActivity = SessionActivity(
        id = id,
        locator = LOCATOR,
        type = type,
        summary = "Summary",
        eventAnchorId = null,
        occurredAtEpochMillis = 1L,
    )

    private companion object {
        val LOCATOR = SessionLocator(
            connectionProviderId = ConnectionProviderId("provider"),
            connectionProfileId = ConnectionProfileId("profile"),
            agentProviderId = AgentProviderId("agent"),
            agentSessionId = AgentSessionId("session"),
        )
        val SHA_256 = Regex("[0-9a-f]{64}")
    }
}
