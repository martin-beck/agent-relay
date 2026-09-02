package com.example.agentrelay.background

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.example.agentrelay.AgentRelayApplication
import com.example.agentrelay.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = AgentRelayApplication::class)
class RemoteSessionForegroundServiceTest {
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun resetNotificationState() {
        ShadowNotificationManager.reset()
    }

    @After
    fun tearDown() {
        ShadowNotificationManager.reset()
    }

    @Test
    fun manifestDeclaresPrivateRemoteMessagingServiceAndPermissions() {
        val packageManager = context.packageManager
        val serviceInfo = packageManager.getServiceInfo(
            ComponentName(context, RemoteSessionForegroundService::class.java),
            PackageManager.GET_META_DATA,
        )
        val requestedPermissions = packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()

        assertFalse(serviceInfo.exported)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING,
            serviceInfo.foregroundServiceType,
        )
        assertTrue(Manifest.permission.FOREGROUND_SERVICE in requestedPermissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_REMOTE_MESSAGING in requestedPermissions)
    }

    @Test
    fun notificationChannelAndContentAreFixedPrivateAndUserStoppable() {
        val controller = Robolectric.buildService(RemoteSessionForegroundService::class.java)
        val service = controller.create().get()
        val manager = context.getSystemService(NotificationManager::class.java)

        val channel = manager.getNotificationChannel("background_connection")
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertEquals(Notification.VISIBILITY_PRIVATE, channel.lockscreenVisibility)
        assertFalse(channel.canShowBadge())

        val notification = service.foregroundNotification()
        assertEquals(
            context.getString(R.string.background_transport_notification_title),
            notification.extras.getCharSequence(Notification.EXTRA_TITLE),
        )
        assertEquals(
            context.getString(R.string.background_transport_notification_text),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        assertEquals(Notification.CATEGORY_SERVICE, notification.category)
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(notification.flags and Notification.FLAG_LOCAL_ONLY != 0)
        assertNotNull(notification.contentIntent)
        assertEquals(1, notification.actions.size)
        assertEquals(
            context.getString(R.string.background_transport_stop),
            notification.actions.single().title,
        )

        val publicVersion = notification.publicVersion
        assertNotNull(publicVersion)
        assertEquals(Notification.VISIBILITY_PUBLIC, publicVersion.visibility)
        assertEquals(
            context.getString(R.string.background_transport_public_text),
            publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT),
        )
        controller.destroy()
    }
}
