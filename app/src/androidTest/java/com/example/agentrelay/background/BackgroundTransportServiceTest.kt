/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.background

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agentrelay.AgentRelayApplication
import com.example.agentrelay.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundTransportServiceTest {
    @Test
    fun explicitStartSurvivesUiBackgroundAndNotificationActionStopsIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application =
            ApplicationProvider.getApplicationContext<AgentRelayApplication>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                application.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        val manager = application.getSystemService(NotificationManager::class.java)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity {
                    application.backgroundTransport.start()
                }
                awaitState(application, BackgroundTransportState.ACTIVE)

                val activeNotification = requireNotNull(
                    manager.activeNotifications
                        .singleOrNull { notification -> notification.id == FOREGROUND_NOTIFICATION_ID },
                )
                assertEquals(1, activeNotification.notification.actions.size)

                scenario.moveToState(Lifecycle.State.CREATED)
                awaitState(application, BackgroundTransportState.ACTIVE)

                activeNotification.notification.actions.single().actionIntent.send()
                awaitState(application, BackgroundTransportState.STOPPED)
                awaitNotificationRemoved(manager)
            } finally {
                if (application.backgroundTransport.state.value !=
                    BackgroundTransportState.STOPPED
                ) {
                    scenario.onActivity {
                        application.backgroundTransport.stop()
                    }
                    awaitState(application, BackgroundTransportState.STOPPED)
                }
            }
        }
    }

    private fun awaitState(
        application: AgentRelayApplication,
        expected: BackgroundTransportState,
    ) {
        awaitCondition {
            application.backgroundTransport.state.value == expected
        }
    }

    private fun awaitNotificationRemoved(manager: NotificationManager) {
        awaitCondition {
            manager.activeNotifications.none { notification ->
                notification.id == FOREGROUND_NOTIFICATION_ID
            }
        }
    }

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MILLIS
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) {
                "Timed out waiting for a background transport state transition"
            }
            SystemClock.sleep(POLL_INTERVAL_MILLIS)
        }
    }

    private companion object {
        const val FOREGROUND_NOTIFICATION_ID = 2
        const val TIMEOUT_MILLIS = 10_000L
        const val POLL_INTERVAL_MILLIS = 25L
    }
}
