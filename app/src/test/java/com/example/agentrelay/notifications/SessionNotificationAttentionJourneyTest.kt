package com.example.agentrelay.notifications

import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.session.api.SessionActivity
import dev.agentrelay.session.api.SessionActivitySummary
import dev.agentrelay.session.api.SessionActivityType
import dev.agentrelay.session.api.SessionHubSnapshot
import dev.agentrelay.session.api.SessionLocator
import dev.agentrelay.session.api.SessionObservation
import dev.agentrelay.session.api.SessionRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Semantic contract for the notification-attention workflow without Android notification I/O. */
class SessionNotificationAttentionJourneyTest {
    @Test
    fun backgroundAttentionIsDeduplicatedAndCurrentResolutionClearsIt() = runTest {
        val sink = RecordingSink()
        val reconciler = SessionNotificationReconciler(sink)
        val pending = snapshot(action("approval", isResolved = false))

        val first = reconciler.reconcile(pending)
        val repeated = reconciler.reconcile(pending)
        val resolved = reconciler.reconcile(snapshot(action("approval", isResolved = true)))

        assertEquals(1, first.shown)
        assertEquals(0, repeated.shown)
        assertEquals(1, resolved.cancelled)
        assertEquals(1, sink.shown.size)
        assertEquals(listOf(sink.shown.single().notificationKey), sink.cancelled)
    }

    @Test
    fun withdrawnRequestAndDisconnectedSessionNeverExposeStaleNotification() = runTest {
        val sink = RecordingSink()
        val reconciler = SessionNotificationReconciler(sink)
        val pending = snapshot(action("withdrawn", isResolved = false))

        reconciler.reconcile(pending)
        val withdrawn = reconciler.reconcile(snapshot(action("withdrawn", isResolved = true)))
        val disconnected = reconciler.reconcile(SessionHubSnapshot())

        assertEquals(1, withdrawn.cancelled)
        assertEquals(0, disconnected.cancelled)
        assertTrue(sink.cancelled.isNotEmpty())
        assertFalse(sink.shown.any { notification -> notification.kind == SessionNotificationKind.ACTIVITY })
    }

    @Test
    fun projectedNotificationContainsOnlyStableDigestsForCurrentSessionNavigation() {
        val projected = SessionNotificationProjection.project(
            snapshot(
                action(
                    id = "private-request",
                    isResolved = false,
                    summary = "private command and host",
                ),
            ),
        ).single()

        assertTrue(projected.notificationKey.matches(SHA_256))
        assertTrue(projected.sessionNavigationKey.matches(SHA_256))
        assertFalse(projected.toString().contains("private command"))
        assertFalse(projected.toString().contains("host"))
    }

    private class RecordingSink : SessionNotificationSink {
        val shown = mutableListOf<ProjectedSessionNotification>()
        val cancelled = mutableListOf<String>()

        override suspend fun show(notification: ProjectedSessionNotification) {
            shown += notification
        }

        override suspend fun cancel(notificationKey: String) {
            cancelled += notificationKey
        }
    }

    private fun snapshot(activity: SessionActivity? = null): SessionHubSnapshot =
        SessionHubSnapshot(
            sessions = listOf(session()),
            activities = listOfNotNull(activity),
        )

    private fun action(
        id: String,
        isResolved: Boolean,
        summary: String = "Approval requested",
    ) = SessionActivity(
        id = id,
        locator = LOCATOR,
        type = SessionActivityType.APPROVAL_REQUIRED,
        summary = SessionActivitySummary.Verbatim(summary),
        eventAnchorId = id,
        occurredAtEpochMillis = 1L,
        isResolved = isResolved,
    )

    private fun session() = SessionRecord(
        observation = SessionObservation(
            locator = LOCATOR,
            connectionLabel = "Connection",
            connectionTarget = "Target",
            projectPath = null,
            agentProviderLabel = "Agent",
            title = null,
            preview = "",
            agentState = AgentSessionState.WAITING_FOR_APPROVAL,
            createdAtEpochMillis = 0L,
            updatedAtEpochMillis = 1L,
        ),
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
