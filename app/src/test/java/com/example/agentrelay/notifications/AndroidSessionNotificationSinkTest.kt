package com.example.agentrelay.notifications

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import com.example.agentrelay.MainActivity
import com.example.agentrelay.R
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager
import org.robolectric.shadows.ShadowPendingIntent

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidSessionNotificationSinkTest {
    private val context: Application
        get() = RuntimeEnvironment.getApplication()
    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    @Before
    fun resetNotificationState() {
        ShadowNotificationManager.reset()
        ShadowPendingIntent.reset()
    }

    @Test
    fun rendersFixedPrivateContentAndDigestOnlyNavigation() = runTest {
        val sink = AndroidSessionNotificationSink(
            context = context,
            manager = manager,
            authorization = SessionNotificationAuthorization { true },
        )
        val projected = notification(
            notificationCharacter = 'a',
            navigationCharacter = 'b',
            kind = SessionNotificationKind.ACTION_REQUIRED,
        )

        sink.show(projected)

        val shadowManager = shadowOf(manager)
        val posted = shadowManager.getNotification(projected.notificationKey, NOTIFICATION_ID)
        assertNotNull(posted)
        requireNotNull(posted)
        assertEquals("session_action_required", posted.channelId)
        assertEquals(
            context.getString(R.string.notification_action_title),
            posted.extras.getCharSequence(Notification.EXTRA_TITLE),
        )
        assertEquals(
            context.getString(R.string.notification_action_text),
            posted.extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        assertEquals(Notification.CATEGORY_STATUS, posted.category)
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertTrue(posted.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertTrue(posted.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(posted.flags and Notification.FLAG_LOCAL_ONLY != 0)
        assertEquals(projected.occurredAtEpochMillis, posted.`when`)

        val publicVersion = requireNotNull(posted.publicVersion)
        assertEquals(
            context.getString(R.string.app_name),
            publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE),
        )
        assertEquals(
            context.getString(R.string.notification_public_text),
            publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT),
        )

        val pendingIntent = requireNotNull(posted.contentIntent)
        val savedIntent = shadowOf(pendingIntent).savedIntent
        assertEquals(MainActivity::class.java.name, savedIntent.component?.className)
        assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP, savedIntent.flags)
        assertEquals("agent-relay", savedIntent.data?.scheme)
        assertEquals("session", savedIntent.data?.authority)
        assertEquals(listOf(projected.sessionNavigationKey), savedIntent.data?.pathSegments)
        assertEquals(
            setOf(SESSION_NAVIGATION_KEY_EXTRA),
            requireNotNull(savedIntent.extras).keySet(),
        )
        assertEquals(
            projected.sessionNavigationKey,
            savedIntent.getStringExtra(SESSION_NAVIGATION_KEY_EXTRA),
        )
        assertTrue(shadowOf(pendingIntent).isImmutable)
    }

    @Test
    fun createsPurposeSpecificChannelsAndPresentation() = runTest {
        val sink = AndroidSessionNotificationSink(
            context = context,
            manager = manager,
            authorization = SessionNotificationAuthorization { true },
        )
        val expectations = listOf(
            PresentationExpectation(
                kind = SessionNotificationKind.ACTION_REQUIRED,
                digestCharacter = '1',
                channelId = "session_action_required",
                titleResource = R.string.notification_action_title,
                category = Notification.CATEGORY_STATUS,
            ),
            PresentationExpectation(
                kind = SessionNotificationKind.FAILURE,
                digestCharacter = '2',
                channelId = "session_failure",
                titleResource = R.string.notification_failure_title,
                category = Notification.CATEGORY_ERROR,
            ),
            PresentationExpectation(
                kind = SessionNotificationKind.COMPLETION,
                digestCharacter = '3',
                channelId = "session_update",
                titleResource = R.string.notification_completion_title,
                category = Notification.CATEGORY_STATUS,
            ),
            PresentationExpectation(
                kind = SessionNotificationKind.ACTIVITY,
                digestCharacter = '4',
                channelId = "session_update",
                titleResource = R.string.notification_activity_title,
                category = Notification.CATEGORY_STATUS,
            ),
        )

        expectations.forEach { expectation ->
            val projected = notification(
                notificationCharacter = expectation.digestCharacter,
                navigationCharacter = 'f',
                kind = expectation.kind,
            )
            sink.show(projected)
            val posted = requireNotNull(
                shadowOf(manager).getNotification(projected.notificationKey, NOTIFICATION_ID),
            )
            assertEquals(expectation.channelId, posted.channelId)
            assertEquals(
                context.getString(expectation.titleResource),
                posted.extras.getCharSequence(Notification.EXTRA_TITLE),
            )
            assertEquals(expectation.category, posted.category)
        }

        assertEquals(NotificationManager.IMPORTANCE_HIGH, channelImportance("session_action_required"))
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channelImportance("session_failure"))
        assertEquals(NotificationManager.IMPORTANCE_LOW, channelImportance("session_update"))
    }

    @Test
    fun deniedDeliveryStaysObservableAndDoesNotPost() = runTest {
        val projected = notification('c', 'd', SessionNotificationKind.FAILURE)
        val sink = AndroidSessionNotificationSink(
            context = context,
            manager = manager,
            authorization = SessionNotificationAuthorization { false },
        )

        try {
            sink.show(projected)
            fail("Denied delivery must fail")
        } catch (_: SessionNotificationDeliveryUnavailableException) {
            // A reconciler keeps this digest pending until permission changes.
        }

        assertNull(shadowOf(manager).getNotification(projected.notificationKey, NOTIFICATION_ID))
    }

    @Test
    fun authorizationRequiresPermissionGlobalSettingAndChannel() {
        val sink = AndroidSessionNotificationSink(context, manager)
        sink.ensureChannels()
        val authorization = AndroidSessionNotificationAuthorization(context, manager)
        val shadowApplication = shadowOf(context)
        val shadowManager = shadowOf(manager)

        shadowApplication.denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(authorization.canPost("session_update"))

        shadowApplication.grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowManager.setNotificationsEnabled(true)
        assertTrue(authorization.canPost("session_update"))

        shadowManager.setNotificationsEnabled(false)
        assertFalse(authorization.canPost("session_update"))

        shadowManager.setNotificationsEnabled(true)
        manager.deleteNotificationChannel("session_update")
        assertFalse(authorization.canPost("session_update"))
    }

    @Test
    fun cancellationUsesTheStableDigestIdentity() = runTest {
        val sink = AndroidSessionNotificationSink(
            context = context,
            manager = manager,
            authorization = SessionNotificationAuthorization { true },
        )
        val projected = notification('e', 'f', SessionNotificationKind.ACTIVITY)
        sink.show(projected)

        sink.cancel(projected.notificationKey)

        assertNull(shadowOf(manager).getNotification(projected.notificationKey, NOTIFICATION_ID))
    }

    @Test
    fun navigationParserRequiresMatchingDigestOnlyIntent() {
        val navigationKey = "a".repeat(64)
        val valid = Intent()
            .setAction(context.packageName + SESSION_NOTIFICATION_OPEN_ACTION_SUFFIX)
            .setData(Uri.parse("agent-relay://session/$navigationKey"))
            .putExtra(SESSION_NAVIGATION_KEY_EXTRA, navigationKey)

        assertEquals(
            navigationKey,
            valid.sessionNotificationNavigationKey(context.packageName),
        )
        assertNull(
            Intent(valid)
                .setAction("unexpected")
                .sessionNotificationNavigationKey(context.packageName),
        )
        assertNull(
            Intent(valid)
                .setData(Uri.parse("agent-relay://session/${"b".repeat(64)}"))
                .sessionNotificationNavigationKey(context.packageName),
        )
        assertNull(
            Intent(valid)
                .putExtra(SESSION_NAVIGATION_KEY_EXTRA, "A".repeat(64))
                .sessionNotificationNavigationKey(context.packageName),
        )
        assertNull(
            Intent(valid)
                .putExtra("unexpected", "value")
                .sessionNotificationNavigationKey(context.packageName),
        )
        assertNull(
            Intent(valid)
                .setData(null)
                .sessionNotificationNavigationKey(context.packageName),
        )
    }

    private fun channelImportance(channelId: String): Int =
        requireNotNull(manager.getNotificationChannel(channelId)).importance

    private fun notification(
        notificationCharacter: Char,
        navigationCharacter: Char,
        kind: SessionNotificationKind,
    ): ProjectedSessionNotification = ProjectedSessionNotification(
        notificationKey = notificationCharacter.toString().repeat(64),
        sessionNavigationKey = navigationCharacter.toString().repeat(64),
        kind = kind,
        occurredAtEpochMillis = 1_789_000_000_000L,
    )

    private data class PresentationExpectation(
        val kind: SessionNotificationKind,
        val digestCharacter: Char,
        val channelId: String,
        val titleResource: Int,
        val category: String,
    )

    private companion object {
        const val NOTIFICATION_ID = 1
    }
}
