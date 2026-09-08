package com.example.agentrelay.widgets

import android.app.Application
import android.appwidget.AppWidgetManager
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.session.api.AttentionWidgetAction
import dev.agentrelay.session.api.AttentionWidgetSize
import dev.agentrelay.session.api.SessionHubSnapshot
import dev.agentrelay.session.api.SessionLocator
import dev.agentrelay.session.api.SessionObservation
import dev.agentrelay.session.api.SessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AttentionWidgetRuntimeTest {
    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearPersistence() {
        context.getSharedPreferences("attention_widget_runtime_v1", 0).edit().clear().commit()
    }

    @Test
    fun projectionIncludesOnlyAttentionAndRedactsProtectedTitles() {
        val persistence = AndroidAttentionWidgetPersistence(context)
        val projector = SessionAttentionWidgetProjector(persistence, "Agent Relay")
        val quiet = session("quiet", "Quiet work", unread = 0)
        val unread = session("unread", "Review result", unread = 2)
        val protected = session("protected", "Rotate password token", unread = 1)

        val result = projector.project(
            SessionHubSnapshot(sessions = listOf(quiet, unread, protected)),
            nowEpochMillis = 5_000,
        )

        assertEquals(listOf("Review result", "Agent Relay"), result.snapshot.items.map { it.title })
        assertTrue(result.snapshot.items.all { it.summary == null && it.canOpen })
        assertTrue(result.snapshot.items.all { !it.canAcknowledge && !it.canDefer && !it.canMute })
        assertEquals(2, result.locators.size)
        assertFalse(result.locators.values.contains(quiet.locator))
    }

    @Test
    fun unchangedProjectionKeepsRevisionButMaterialChangeAdvancesIt() {
        val persistence = AndroidAttentionWidgetPersistence(context)
        val projector = SessionAttentionWidgetProjector(persistence, "Agent Relay")
        val initial = SessionHubSnapshot(sessions = listOf(session("one", "Review result", unread = 1)))

        val first = projector.project(initial, nowEpochMillis = 5_000).snapshot
        val unchanged = projector.project(initial, nowEpochMillis = 6_000).snapshot
        val changed = projector.project(
            SessionHubSnapshot(sessions = listOf(session("one", "Review updated result", unread = 1))),
            nowEpochMillis = 6_000,
        ).snapshot

        assertEquals(first.revision, unchanged.revision)
        assertNotEquals(first.revision, changed.revision)
    }

    @Test
    fun widgetWidthsMapToBoundedLayouts() {
        fun options(width: Int) = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
        }

        assertEquals(AttentionWidgetSize.COMPACT, widgetSize(options(180)))
        assertEquals(AttentionWidgetSize.MEDIUM, widgetSize(options(240)))
        assertEquals(AttentionWidgetSize.EXPANDED, widgetSize(options(320)))
    }

    @Test
    fun hmacRequestsRejectPayloadOrTagChanges() {
        val authenticator = WidgetHmacAuthenticator(ByteArray(32) { it.toByte() })
        val request = authenticator.issue(
            itemId = "attention-item",
            snapshotRevision = 4,
            authorityGeneration = 2,
            action = AttentionWidgetAction.OPEN_DETAILS,
            nowEpochMillis = 10_000,
        )

        assertTrue(authenticator.authenticate(request))
        assertFalse(authenticator.authenticate(request.copy(snapshotRevision = 5)))
        assertFalse(authenticator.authenticate(request.copy(authenticationTag = "A".repeat(43))))
    }

    @Test
    fun persistedSecretAndRevisionSurviveRuntimeRecreation() {
        val first = AndroidAttentionWidgetPersistence(context)
        val firstSecret = first.secret()
        val firstRevision = first.revisionFor(emptyList())

        val recreated = AndroidAttentionWidgetPersistence(context)

        assertTrue(firstSecret.contentEquals(recreated.secret()))
        assertEquals(firstRevision, recreated.revisionFor(emptyList()))
    }

    @Test
    fun projectionIsBoundedBeforeConstructingPublicSnapshot() {
        val projector = SessionAttentionWidgetProjector(
            AndroidAttentionWidgetPersistence(context),
            "Agent Relay",
        )
        val sessions = (0 until 40).map { index ->
            session("item-$index", "Review result $index", unread = 1)
        }

        val projection = projector.project(SessionHubSnapshot(sessions = sessions), nowEpochMillis = 5_000)

        assertEquals(32, projection.snapshot.items.size)
        assertEquals(32, projection.locators.size)
    }

    private fun session(
        suffix: String,
        title: String,
        unread: Int,
    ): SessionRecord = SessionRecord(
        observation = SessionObservation(
            locator = locator(suffix),
            connectionLabel = "Connection",
            connectionTarget = "Target",
            projectPath = null,
            agentProviderLabel = "Agent",
            title = title,
            preview = "",
            agentState = AgentSessionState.IDLE,
            createdAtEpochMillis = 1_000,
            updatedAtEpochMillis = 2_000,
        ),
        unreadCount = unread,
    )

    private fun locator(suffix: String) = SessionLocator(
        connectionProviderId = ConnectionProviderId("provider-$suffix"),
        connectionProfileId = ConnectionProfileId("profile-$suffix"),
        agentProviderId = AgentProviderId("agent-$suffix"),
        agentSessionId = AgentSessionId("session-$suffix"),
    )
}
