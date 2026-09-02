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
import dev.agentrelay.session.api.SessionNotificationPriority
import dev.agentrelay.session.api.SessionObservation
import dev.agentrelay.session.api.SessionPreferences
import dev.agentrelay.session.api.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionNotificationProjectionTest {
    @Test
    fun importantPolicyProjectsOnlyUnresolvedAttentionAndFailure() {
        val locator = locator("important")
        val snapshot = snapshot(
            session(locator, SessionNotificationPriority.IMPORTANT_ONLY),
            activity(locator, "output", SessionActivityType.NEW_OUTPUT, occurredAt = 1L),
            activity(locator, "failure", SessionActivityType.FAILURE, occurredAt = 2L),
            activity(
                locator,
                "approval",
                SessionActivityType.APPROVAL_REQUIRED,
                occurredAt = 3L,
                isRead = true,
            ),
            activity(
                locator,
                "resolved",
                SessionActivityType.QUESTION,
                occurredAt = 4L,
                isResolved = true,
            ),
            activity(locator, "reconnected", SessionActivityType.RECONNECTED, occurredAt = 5L),
        )

        val projected = SessionNotificationProjection.project(snapshot)

        assertEquals(
            listOf(SessionNotificationKind.ACTION_REQUIRED, SessionNotificationKind.FAILURE),
            projected.map(ProjectedSessionNotification::kind),
        )
        assertEquals(listOf(3L, 2L), projected.map(ProjectedSessionNotification::occurredAtEpochMillis))
    }

    @Test
    fun finalOutputPolicyKeepsActionsButSuppressesRoutineActivity() {
        val locator = locator("final")
        val snapshot = snapshot(
            session(locator, SessionNotificationPriority.FINAL_OUTPUT_ONLY),
            activity(locator, "output", SessionActivityType.NEW_OUTPUT, occurredAt = 1L),
            activity(locator, "failure", SessionActivityType.FAILURE, occurredAt = 2L),
            activity(locator, "complete", SessionActivityType.TURN_COMPLETED, occurredAt = 3L),
            activity(locator, "question", SessionActivityType.QUESTION, occurredAt = 4L),
        )

        val projected = SessionNotificationProjection.project(snapshot)

        assertEquals(
            listOf(SessionNotificationKind.ACTION_REQUIRED, SessionNotificationKind.COMPLETION),
            projected.map(ProjectedSessionNotification::kind),
        )
    }

    @Test
    fun allActivityStillSuppressesReadResolvedAndReconnectNoise() {
        val locator = locator("all")
        val snapshot = snapshot(
            session(locator, SessionNotificationPriority.ALL_ACTIVITY),
            activity(locator, "output", SessionActivityType.NEW_OUTPUT, occurredAt = 1L),
            activity(
                locator,
                "read",
                SessionActivityType.TURN_COMPLETED,
                occurredAt = 2L,
                isRead = true,
            ),
            activity(
                locator,
                "resolved",
                SessionActivityType.APPROVAL_REQUIRED,
                occurredAt = 3L,
                isResolved = true,
            ),
            activity(locator, "reconnected", SessionActivityType.RECONNECTED, occurredAt = 4L),
        )

        val projected = SessionNotificationProjection.project(snapshot)

        assertEquals(listOf(SessionNotificationKind.ACTIVITY), projected.map { it.kind })
    }

    @Test
    fun mutedSessionProducesNoNotification() {
        val muted = locator("muted")
        val snapshot = SessionHubSnapshot(
            sessions = listOf(session(muted, SessionNotificationPriority.MUTED)),
            activities = listOf(
                activity(muted, "muted", SessionActivityType.APPROVAL_REQUIRED, occurredAt = 1L),
            ),
        )

        assertTrue(SessionNotificationProjection.project(snapshot).isEmpty())
    }

    @Test
    fun projectionUsesOnlyBoundedDigestsAndCoarseKinds() {
        val locator = SessionLocator(
            connectionProviderId = ConnectionProviderId("private-provider"),
            connectionProfileId = ConnectionProfileId("private-host.example"),
            agentProviderId = AgentProviderId("private-agent"),
            agentSessionId = AgentSessionId("private-session"),
        )
        val sensitiveValues = listOf(
            "private-provider",
            "private-host.example",
            "private-agent",
            "private-session",
            "secret prompt and path",
        )
        val snapshot = snapshot(
            session(locator, SessionNotificationPriority.IMPORTANT_ONLY),
            activity(
                locator,
                "private-activity",
                SessionActivityType.FAILURE,
                occurredAt = 1L,
                summary = "secret prompt and path",
            ),
        )

        val projected = SessionNotificationProjection.project(snapshot).single()
        val serializedProjection = projected.toString()

        assertTrue(projected.notificationKey.matches(SHA_256))
        assertTrue(projected.sessionNavigationKey.matches(SHA_256))
        sensitiveValues.forEach { sensitive ->
            assertFalse(serializedProjection.contains(sensitive))
        }
    }

    @Test
    fun projectionIsBoundedAndPrioritizesActions() {
        val locator = locator("bounded")
        val snapshot = snapshot(
            session(locator, SessionNotificationPriority.ALL_ACTIVITY),
            *(0 until 70)
                .map { index ->
                    activity(
                        locator,
                        "activity-$index",
                        SessionActivityType.NEW_OUTPUT,
                        occurredAt = index.toLong(),
                    )
                }
                .plus(
                    activity(
                        locator,
                        "approval",
                        SessionActivityType.APPROVAL_REQUIRED,
                        occurredAt = 0L,
                    ),
                )
                .toTypedArray(),
        )

        val projected = SessionNotificationProjection.project(snapshot)

        assertEquals(64, projected.size)
        assertEquals(SessionNotificationKind.ACTION_REQUIRED, projected.first().kind)
        assertEquals(69L, projected[1].occurredAtEpochMillis)
    }

    private fun snapshot(
        session: SessionRecord,
        vararg activities: SessionActivity,
    ): SessionHubSnapshot = SessionHubSnapshot(
        sessions = listOf(session),
        activities = activities.toList(),
    )

    private fun session(
        locator: SessionLocator,
        priority: SessionNotificationPriority,
    ): SessionRecord = SessionRecord(
        observation = SessionObservation(
            locator = locator,
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
        preferences = SessionPreferences(notificationPriority = priority),
    )

    private fun activity(
        locator: SessionLocator,
        id: String,
        type: SessionActivityType,
        occurredAt: Long,
        isRead: Boolean = false,
        isResolved: Boolean = false,
        summary: String = "Summary",
    ): SessionActivity = SessionActivity(
        id = id,
        locator = locator,
        type = type,
        summary = summary,
        eventAnchorId = null,
        occurredAtEpochMillis = occurredAt,
        isRead = isRead,
        isResolved = isResolved,
    )

    private fun locator(suffix: String) = SessionLocator(
        connectionProviderId = ConnectionProviderId("provider-$suffix"),
        connectionProfileId = ConnectionProfileId("profile-$suffix"),
        agentProviderId = AgentProviderId("agent-$suffix"),
        agentSessionId = AgentSessionId("session-$suffix"),
    )

    private companion object {
        val SHA_256 = Regex("[0-9a-f]{64}")
    }
}
