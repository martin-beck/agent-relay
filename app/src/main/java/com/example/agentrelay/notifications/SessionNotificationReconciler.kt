package com.example.agentrelay.notifications

import dev.agentrelay.session.api.SessionHubSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface SessionNotificationSink {
    suspend fun show(notification: ProjectedSessionNotification)

    suspend fun cancel(notificationKey: String)
}

internal data class SessionNotificationReconciliation(
    val shown: Int,
    val cancelled: Int,
    val failedNotificationKeys: Set<String>,
) {
    init {
        require(shown >= 0)
        require(cancelled >= 0)
        require(failedNotificationKeys.all(String::isSha256NotificationKey))
    }
}

internal class SessionNotificationReconciler(
    private val sink: SessionNotificationSink,
) {
    private val mutex = Mutex()
    private val applied = mutableMapOf<String, ProjectedSessionNotification>()

    suspend fun reconcile(snapshot: SessionHubSnapshot): SessionNotificationReconciliation =
        mutex.withLock {
            val desired = SessionNotificationProjection.project(snapshot)
                .associateBy(ProjectedSessionNotification::notificationKey)
            val failures = linkedSetOf<String>()
            var shown = 0
            var cancelled = 0

            (applied.keys - desired.keys).sorted().forEach { notificationKey ->
                if (runSinkOperation { sink.cancel(notificationKey) }) {
                    applied.remove(notificationKey)
                    cancelled += 1
                } else {
                    failures += notificationKey
                }
            }

            desired.values.forEach { notification ->
                if (applied[notification.notificationKey] == notification) {
                    return@forEach
                }
                if (runSinkOperation { sink.show(notification) }) {
                    applied[notification.notificationKey] = notification
                    shown += 1
                } else {
                    failures += notification.notificationKey
                }
            }

            SessionNotificationReconciliation(
                shown = shown,
                cancelled = cancelled,
                failedNotificationKeys = failures,
            )
        }

    suspend fun suppress(snapshot: SessionHubSnapshot): SessionNotificationReconciliation =
        mutex.withLock {
            val desired = SessionNotificationProjection.project(snapshot)
                .associateBy(ProjectedSessionNotification::notificationKey)
            val failures = linkedSetOf<String>()
            val nextApplied = desired.toMutableMap()
            var cancelled = 0

            (applied.keys + desired.keys).sorted().forEach { notificationKey ->
                if (runSinkOperation { sink.cancel(notificationKey) }) {
                    cancelled += 1
                } else {
                    failures += notificationKey
                    if (notificationKey !in desired) {
                        applied[notificationKey]?.let { notification ->
                            nextApplied[notificationKey] = notification
                        }
                    }
                }
            }

            applied.clear()
            applied.putAll(nextApplied)
            SessionNotificationReconciliation(
                shown = 0,
                cancelled = cancelled,
                failedNotificationKeys = failures,
            )
        }

    private suspend fun runSinkOperation(operation: suspend () -> Unit): Boolean =
        try {
            operation()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            false
        }
}

private fun String.isSha256NotificationKey(): Boolean =
    length == 64 && all { character -> character in '0'..'9' || character in 'a'..'f' }
