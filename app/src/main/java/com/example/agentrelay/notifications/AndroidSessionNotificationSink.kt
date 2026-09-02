package com.example.agentrelay.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.example.agentrelay.MainActivity
import com.example.agentrelay.R

internal fun interface SessionNotificationAuthorization {
    fun canPost(channelId: String): Boolean
}

internal class AndroidSessionNotificationAuthorization(
    private val context: Context,
    private val manager: NotificationManager,
) : SessionNotificationAuthorization {
    override fun canPost(channelId: String): Boolean {
        val runtimePermissionGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        if (!runtimePermissionGranted || !manager.areNotificationsEnabled()) {
            return false
        }
        val importance = manager.getNotificationChannel(channelId)?.importance
            ?: return false
        return importance != NotificationManager.IMPORTANCE_NONE
    }
}

internal class SessionNotificationDeliveryUnavailableException :
    IllegalStateException("Notification delivery is unavailable")

internal class AndroidSessionNotificationSink(
    context: Context,
    private val manager: NotificationManager =
        context.getSystemService(NotificationManager::class.java),
    private val authorization: SessionNotificationAuthorization =
        AndroidSessionNotificationAuthorization(context, manager),
) : SessionNotificationSink {
    private val appContext = context.applicationContext

    override suspend fun show(notification: ProjectedSessionNotification) {
        val presentation = notification.kind.presentation
        ensureChannels()
        if (!authorization.canPost(presentation.channel.id)) {
            throw SessionNotificationDeliveryUnavailableException()
        }

        manager.notify(
            notification.notificationKey,
            NOTIFICATION_ID,
            buildNotification(notification, presentation),
        )
    }

    override suspend fun cancel(notificationKey: String) {
        require(notificationKey.isSha256Digest()) {
            "Notification key must be a SHA-256 identifier"
        }
        manager.cancel(notificationKey, NOTIFICATION_ID)
    }

    internal fun ensureChannels() {
        NotificationChannelSpec.entries.forEach { channel ->
            manager.createNotificationChannel(
                NotificationChannel(
                    channel.id,
                    appContext.getString(channel.nameResource),
                    channel.importance,
                ).apply {
                    description = appContext.getString(channel.descriptionResource)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                    setShowBadge(true)
                },
            )
        }
    }

    private fun buildNotification(
        notification: ProjectedSessionNotification,
        presentation: SessionNotificationPresentation,
    ): Notification {
        val publicVersion = Notification.Builder(appContext, presentation.channel.id)
            .setSmallIcon(R.drawable.ic_notification_agent_relay)
            .setContentTitle(appContext.getString(R.string.app_name))
            .setContentText(appContext.getString(R.string.notification_public_text))
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()

        return Notification.Builder(appContext, presentation.channel.id)
            .setSmallIcon(R.drawable.ic_notification_agent_relay)
            .setContentTitle(appContext.getString(presentation.titleResource))
            .setContentText(appContext.getString(presentation.textResource))
            .setCategory(presentation.category)
            .setContentIntent(notification.contentIntent())
            .setWhen(notification.occurredAtEpochMillis)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setGroup(NOTIFICATION_GROUP)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
    }

    private fun ProjectedSessionNotification.contentIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java)
            .setAction(appContext.packageName + SESSION_NOTIFICATION_OPEN_ACTION_SUFFIX)
            .setData(
                Uri.Builder()
                    .scheme(SESSION_NOTIFICATION_URI_SCHEME)
                    .authority(SESSION_NOTIFICATION_URI_AUTHORITY)
                    .appendPath(sessionNavigationKey)
                    .build(),
            )
            .putExtra(SESSION_NAVIGATION_KEY_EXTRA, sessionNavigationKey)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            appContext,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private enum class NotificationChannelSpec(
        val id: String,
        val nameResource: Int,
        val descriptionResource: Int,
        val importance: Int,
    ) {
        ACTION_REQUIRED(
            id = "session_action_required",
            nameResource = R.string.notification_channel_action_name,
            descriptionResource = R.string.notification_channel_action_description,
            importance = NotificationManager.IMPORTANCE_HIGH,
        ),
        FAILURE(
            id = "session_failure",
            nameResource = R.string.notification_channel_failure_name,
            descriptionResource = R.string.notification_channel_failure_description,
            importance = NotificationManager.IMPORTANCE_DEFAULT,
        ),
        UPDATE(
            id = "session_update",
            nameResource = R.string.notification_channel_update_name,
            descriptionResource = R.string.notification_channel_update_description,
            importance = NotificationManager.IMPORTANCE_LOW,
        ),
    }

    private data class SessionNotificationPresentation(
        val channel: NotificationChannelSpec,
        val titleResource: Int,
        val textResource: Int,
        val category: String,
    )

    private val SessionNotificationKind.presentation: SessionNotificationPresentation
        get() = when (this) {
            SessionNotificationKind.ACTION_REQUIRED -> SessionNotificationPresentation(
                channel = NotificationChannelSpec.ACTION_REQUIRED,
                titleResource = R.string.notification_action_title,
                textResource = R.string.notification_action_text,
                category = Notification.CATEGORY_STATUS,
            )
            SessionNotificationKind.FAILURE -> SessionNotificationPresentation(
                channel = NotificationChannelSpec.FAILURE,
                titleResource = R.string.notification_failure_title,
                textResource = R.string.notification_failure_text,
                category = Notification.CATEGORY_ERROR,
            )
            SessionNotificationKind.COMPLETION -> SessionNotificationPresentation(
                channel = NotificationChannelSpec.UPDATE,
                titleResource = R.string.notification_completion_title,
                textResource = R.string.notification_completion_text,
                category = Notification.CATEGORY_STATUS,
            )
            SessionNotificationKind.ACTIVITY -> SessionNotificationPresentation(
                channel = NotificationChannelSpec.UPDATE,
                titleResource = R.string.notification_activity_title,
                textResource = R.string.notification_activity_text,
                category = Notification.CATEGORY_STATUS,
            )
        }

    private companion object {
        const val NOTIFICATION_ID = 1
        const val NOTIFICATION_GROUP = "session_activity"
    }
}

private fun String.isSha256Digest(): Boolean =
    length == 64 && all { character -> character in '0'..'9' || character in 'a'..'f' }
