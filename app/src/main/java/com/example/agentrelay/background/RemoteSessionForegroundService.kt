package com.example.agentrelay.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.example.agentrelay.AgentRelayApplication
import com.example.agentrelay.MainActivity
import com.example.agentrelay.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal class RemoteSessionForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var startFailed = false
    private val relayApplication: AgentRelayApplication
        get() = application as AgentRelayApplication

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            relayApplication.backgroundTransport.serviceRestarting()
            return startMonitoring(recoverAfterProcessDeath = true)
        }
        return when (intent.action.backgroundTransportAction(packageName)) {
            BackgroundTransportAction.STOP -> {
                stopMonitoring()
                START_NOT_STICKY
            }
            BackgroundTransportAction.START -> {
                startMonitoring(recoverAfterProcessDeath = false)
            }
            null -> {
                startFailed = true
                Log.e(LOG_TAG, "Background connection service received an invalid action")
                relayApplication.backgroundTransport.serviceFailed()
                stopSelf()
                START_NOT_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (!startFailed) {
            relayApplication.backgroundTransport.serviceStopped()
        }
        relayApplication.releaseBackgroundTransportAfterServiceDestruction()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startMonitoring(recoverAfterProcessDeath: Boolean): Int {
        startFailed = false
        try {
            ServiceCompat.startForeground(
                this,
                FOREGROUND_NOTIFICATION_ID,
                foregroundNotification(),
                foregroundServiceType(),
            )
        } catch (_: RuntimeException) {
            startFailed = true
            Log.e(LOG_TAG, "Background connection service could not enter the foreground")
            relayApplication.backgroundTransport.serviceFailed()
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            try {
                relayApplication.graph.enableBackgroundTransport(recoverAfterProcessDeath)
                relayApplication.backgroundTransport.serviceActivated()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                startFailed = true
                Log.e(LOG_TAG, "Background connection service could not start")
                relayApplication.backgroundTransport.serviceFailed()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun stopMonitoring() {
        serviceScope.launch {
            try {
                relayApplication.graph.disableBackgroundTransportIfInitialized()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                Log.e(LOG_TAG, "Background connection service could not stop cleanly")
            } finally {
                relayApplication.backgroundTransport.serviceStopped()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    internal fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                getString(R.string.background_transport_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.background_transport_channel_description)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                setShowBadge(false)
            },
        )
    }

    internal fun foregroundNotification(): Notification {
        val publicVersion = Notification.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_agent_relay)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.background_transport_public_text))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()

        return Notification.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_agent_relay)
            .setContentTitle(getString(R.string.background_transport_notification_title))
            .setContentText(getString(R.string.background_transport_notification_text))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openAppIntent())
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.background_transport_stop),
                    stopServiceIntent(),
                ).build(),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setLocalOnly(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        OPEN_APP_REQUEST_CODE,
        Intent(this, MainActivity::class.java)
            .setAction(packageName + OPEN_APP_ACTION_SUFFIX)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopServiceIntent(): PendingIntent = PendingIntent.getService(
        this,
        STOP_SERVICE_REQUEST_CODE,
        Intent(this, RemoteSessionForegroundService::class.java)
            .setAction(BackgroundTransportAction.STOP.intentAction(packageName)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            0
        }

    private companion object {
        const val FOREGROUND_CHANNEL_ID = "background_connection"
        const val FOREGROUND_NOTIFICATION_ID = 2
        const val OPEN_APP_REQUEST_CODE = 2
        const val STOP_SERVICE_REQUEST_CODE = 3
        const val OPEN_APP_ACTION_SUFFIX = ".background.OPEN"
        const val LOG_TAG = "AgentRelay"
    }
}
