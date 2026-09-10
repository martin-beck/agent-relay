/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.core.content.edit
import com.example.agentrelay.MainActivity
import com.example.agentrelay.R
import com.example.agentrelay.data.SessionHubRuntime
import com.example.agentrelay.notifications.SESSION_NAVIGATION_KEY_EXTRA
import com.example.agentrelay.notifications.SESSION_NOTIFICATION_OPEN_ACTION_SUFFIX
import com.example.agentrelay.notifications.SESSION_NOTIFICATION_URI_AUTHORITY
import com.example.agentrelay.notifications.SESSION_NOTIFICATION_URI_SCHEME
import dev.agentrelay.session.api.AttentionState
import dev.agentrelay.session.api.AttentionUrgency
import dev.agentrelay.session.api.AttentionWidgetAction
import dev.agentrelay.session.api.AttentionWidgetActionAuthenticator
import dev.agentrelay.session.api.AttentionWidgetActionAuthoritySnapshot
import dev.agentrelay.session.api.AttentionWidgetActionAuthorityStore
import dev.agentrelay.session.api.AttentionWidgetActionExecutionResult
import dev.agentrelay.session.api.AttentionWidgetActionExecutor
import dev.agentrelay.session.api.AttentionWidgetActionProcessor
import dev.agentrelay.session.api.AttentionWidgetActionReceipt
import dev.agentrelay.session.api.AttentionWidgetActionRequest
import dev.agentrelay.session.api.AttentionWidgetActionStateSource
import dev.agentrelay.session.api.AttentionWidgetItem
import dev.agentrelay.session.api.AttentionWidgetSize
import dev.agentrelay.session.api.AttentionWidgetSnapshot
import dev.agentrelay.session.api.AttentionWidgetSurface
import dev.agentrelay.session.api.SessionActivityType
import dev.agentrelay.session.api.SessionActionState
import dev.agentrelay.session.api.SessionHubSnapshot
import dev.agentrelay.session.api.SessionLocator
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** App-owned bridge from the durable session hub to Android widget instances. */
internal class AndroidAttentionWidgetRuntime(
    private val context: Context,
    private val scope: CoroutineScope,
    private val runtimeFactory: suspend () -> SessionHubRuntime,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val persistence = AndroidAttentionWidgetPersistence(context)
    private val projector = SessionAttentionWidgetProjector(
        persistence = persistence,
        fallbackTitle = context.getString(R.string.app_name),
    )
    private val attached = AtomicBoolean()

    @Volatile
    private var current = SessionAttentionWidgetProjection(
        snapshot = AttentionWidgetSnapshot(0, 0, emptyList()),
        locators = emptyMap(),
    )

    private val authenticator = WidgetHmacAuthenticator(persistence.secret())
    private val processor = AttentionWidgetActionProcessor(
        initialAuthority = persistence.authority(),
        authenticator = AttentionWidgetActionAuthenticator(authenticator::authenticate),
        stateSource = AttentionWidgetActionStateSource { current.snapshot },
        executor = AttentionWidgetActionExecutor(::execute),
        authorityStore = AttentionWidgetActionAuthorityStore(persistence::saveAuthority),
    )

    fun updateHome(manager: AppWidgetManager, ids: IntArray, finished: () -> Unit) {
        scope.launch {
            try {
                attach()
                ids.forEach { id -> manager.updateAppWidget(id, homeViews(manager, id)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ids.forEach { id -> manager.updateAppWidget(id, homeErrorViews(manager, id)) }
            } finally {
                finished()
            }
        }
    }

    fun updateLock(manager: AppWidgetManager, ids: IntArray, finished: () -> Unit) {
        scope.launch {
            try {
                attach()
                ids.forEach { id -> manager.updateAppWidget(id, lockViews()) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ids.forEach { id -> manager.updateAppWidget(id, lockErrorViews()) }
            } finally {
                finished()
            }
        }
    }

    fun process(request: AttentionWidgetActionRequest) {
        processor.process(request, clock())
    }

    private suspend fun attach() {
        val runtime = runtimeFactory()
        publish(runtime.sessionSnapshot.value)
        if (attached.compareAndSet(false, true)) {
            scope.launch {
                runtime.sessionSnapshot.collect { snapshot ->
                    if (publish(snapshot)) updateAllInstalledWidgets()
                }
            }
        }
    }

    @Synchronized
    private fun publish(sessionSnapshot: SessionHubSnapshot): Boolean {
        val projection = projector.project(sessionSnapshot, clock())
        val changed = projection.snapshot.revision != current.snapshot.revision
        current = projection
        return changed
    }

    private fun updateAllInstalledWidgets() {
        val manager = AppWidgetManager.getInstance(context)
        val homeIds = manager.getAppWidgetIds(ComponentName(context, HomeScreenAttentionWidgetProvider::class.java))
        homeIds.forEach { id -> manager.updateAppWidget(id, homeViews(manager, id)) }
        val lockIds = manager.getAppWidgetIds(ComponentName(context, LockScreenAttentionWidgetProvider::class.java))
        lockIds.forEach { id -> manager.updateAppWidget(id, lockViews()) }
    }

    private fun homeViews(manager: AppWidgetManager, id: Int): android.widget.RemoteViews {
        val projection = current
        val size = widgetSize(manager.getAppWidgetOptions(id))
        return HomeScreenAttentionWidgetRenderer.render(
            context = context,
            content = projection.snapshot.contentFor(
                size = size,
                surface = AttentionWidgetSurface.HOME_SCREEN,
                nowEpochMillis = maxOf(clock(), projection.snapshot.generatedAtEpochMillis),
            ),
            size = size,
            actionIssuer = ::issue,
        )
    }

    private fun lockViews(): android.widget.RemoteViews {
        val projection = current
        return LockScreenAttentionWidgetRenderer.render(
            context = context,
            content = projection.snapshot.contentFor(
                size = AttentionWidgetSize.COMPACT,
                surface = AttentionWidgetSurface.LOCK_SCREEN,
                nowEpochMillis = maxOf(clock(), projection.snapshot.generatedAtEpochMillis),
            ),
        )
    }

    private fun homeErrorViews(manager: AppWidgetManager, id: Int): android.widget.RemoteViews {
        val size = widgetSize(manager.getAppWidgetOptions(id))
        return HomeScreenAttentionWidgetRenderer.render(
            context = context,
            content = null,
            size = size,
            phase = AttentionWidgetRenderPhase.ERROR,
        )
    }

    private fun lockErrorViews(): android.widget.RemoteViews =
        LockScreenAttentionWidgetRenderer.render(
            context = context,
            content = null,
            phase = AttentionWidgetRenderPhase.ERROR,
        )

    private fun issue(
        itemId: String,
        snapshotRevision: Long,
        action: AttentionWidgetAction,
    ): AttentionWidgetActionRequest = authenticator.issue(
        itemId = itemId,
        snapshotRevision = snapshotRevision,
        authorityGeneration = processor.snapshot().generation,
        action = action,
        nowEpochMillis = clock(),
    )

    private fun execute(request: AttentionWidgetActionRequest): AttentionWidgetActionExecutionResult {
        if (request.action != AttentionWidgetAction.OPEN_DETAILS) {
            return AttentionWidgetActionExecutionResult.NOT_ALLOWED
        }
        val locator = current.locators[request.itemId] ?: return AttentionWidgetActionExecutionResult.STALE_STATE
        val navigationKey = stableDigest(locator.stableKey)
        val intent = Intent(context, MainActivity::class.java)
            .setAction(context.packageName + SESSION_NOTIFICATION_OPEN_ACTION_SUFFIX)
            .setData(
                android.net.Uri.Builder()
                    .scheme(SESSION_NOTIFICATION_URI_SCHEME)
                    .authority(SESSION_NOTIFICATION_URI_AUTHORITY)
                    .appendPath(navigationKey)
                    .build(),
            )
            .putExtra(SESSION_NAVIGATION_KEY_EXTRA, navigationKey)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        context.startActivity(intent)
        return AttentionWidgetActionExecutionResult.APPLIED
    }
}

internal data class SessionAttentionWidgetProjection(
    val snapshot: AttentionWidgetSnapshot,
    val locators: Map<String, SessionLocator>,
)

internal class SessionAttentionWidgetProjector(
    private val persistence: AndroidAttentionWidgetPersistence,
    private val fallbackTitle: String,
) {
    fun project(snapshot: SessionHubSnapshot, nowEpochMillis: Long): SessionAttentionWidgetProjection {
        val pendingLocators = snapshot.actionRequests
            .filter { it.state == SessionActionState.PENDING }
            .mapTo(mutableSetOf()) { it.locator }
        val failedLocators = snapshot.activities
            .filter { it.type == SessionActivityType.FAILURE && !it.isRead }
            .mapTo(mutableSetOf()) { it.locator }
        val records = snapshot.sessions
            .filter { record ->
                record.locator in pendingLocators || record.locator in failedLocators || record.unreadCount > 0
            }
            .take(MAX_PROJECTED_WIDGET_ITEMS)
        val locators = linkedMapOf<String, SessionLocator>()
        val items = records.map { record ->
            val id = stableDigest(record.locator.stableKey)
            locators[id] = record.locator
            AttentionWidgetItem(
                id = id,
                title = safeWidgetTitle(record.observation.title, fallbackTitle),
                summary = null,
                urgency = when (record.locator) {
                    in pendingLocators -> AttentionUrgency.CRITICAL
                    in failedLocators -> AttentionUrgency.HIGH
                    else -> AttentionUrgency.NORMAL
                },
                state = AttentionState.OPEN,
                createdAtEpochMillis = minOf(
                    record.lastActivityAtEpochMillis ?: nowEpochMillis,
                    nowEpochMillis,
                ),
                expiresAtEpochMillis = null,
                canOpen = true,
                canAcknowledge = false,
                canDefer = false,
                canMute = false,
            )
        }
        return SessionAttentionWidgetProjection(
            snapshot = AttentionWidgetSnapshot(
                revision = persistence.revisionFor(items),
                generatedAtEpochMillis = nowEpochMillis,
                items = items,
            ),
            locators = locators,
        )
    }
}

internal fun widgetSize(options: android.os.Bundle): AttentionWidgetSize {
    val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
    return when {
        width >= EXPANDED_WIDGET_WIDTH_DP -> AttentionWidgetSize.EXPANDED
        width >= MEDIUM_WIDGET_WIDTH_DP -> AttentionWidgetSize.MEDIUM
        else -> AttentionWidgetSize.COMPACT
    }
}

internal class AndroidAttentionWidgetPersistence(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun secret(): ByteArray {
        val stored = preferences.getString(SECRET, null)
        if (stored != null) return Base64.decode(stored, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val generated = ByteArray(32).also(SecureRandom()::nextBytes)
        val encoded = Base64.encodeToString(generated, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        preferences.edit(commit = true) { putString(SECRET, encoded) }
        check(preferences.getString(SECRET, null) == encoded) { "Widget action secret could not be persisted" }
        return generated
    }

    @Synchronized
    fun revisionFor(items: List<AttentionWidgetItem>): Long {
        val digest = stableDigest(
            items.joinToString(separator = "|") { item ->
                listOf(item.id, item.title, item.urgency.name, item.createdAtEpochMillis.toString())
                    .joinToString(separator = ":")
            },
        )
        if (preferences.getString(CONTENT_DIGEST, null) == digest) {
            return preferences.getLong(REVISION, 0)
        }
        val previous = preferences.getLong(REVISION, 0)
        check(previous < Long.MAX_VALUE) { "Widget snapshot revision is exhausted" }
        val revision = previous + 1
        preferences.edit(commit = true) {
            putString(CONTENT_DIGEST, digest)
            putLong(REVISION, revision)
        }
        check(
            preferences.getString(CONTENT_DIGEST, null) == digest &&
                preferences.getLong(REVISION, 0) == revision,
        ) { "Widget snapshot revision could not be persisted" }
        return revision
    }

    fun authority(): AttentionWidgetActionAuthoritySnapshot {
        val generation = preferences.getLong(AUTHORITY_GENERATION, 1).coerceAtLeast(1)
        val receipts = preferences.getStringSet(AUTHORITY_RECEIPTS, emptySet()).orEmpty()
            .mapNotNull { encoded ->
                val separator = encoded.lastIndexOf(':')
                if (separator <= 0) return@mapNotNull null
                val expiry = encoded.substring(separator + 1).toLongOrNull() ?: return@mapNotNull null
                runCatching { AttentionWidgetActionReceipt(encoded.substring(0, separator), expiry) }.getOrNull()
            }
            .take(MAX_PERSISTED_RECEIPTS)
        return AttentionWidgetActionAuthoritySnapshot(
            generation = generation,
            revoked = preferences.getBoolean(AUTHORITY_REVOKED, false),
            consumedRequests = receipts,
        )
    }

    @Synchronized
    fun saveAuthority(snapshot: AttentionWidgetActionAuthoritySnapshot): Boolean {
        val encodedReceipts = snapshot.consumedRequests.mapTo(linkedSetOf()) {
            "${it.requestId}:${it.expiresAtEpochMillis}"
        }
        preferences.edit(commit = true) {
            putLong(AUTHORITY_GENERATION, snapshot.generation)
            putBoolean(AUTHORITY_REVOKED, snapshot.revoked)
            putStringSet(
                AUTHORITY_RECEIPTS,
                encodedReceipts,
            )
        }
        return preferences.getLong(AUTHORITY_GENERATION, 0) == snapshot.generation &&
            preferences.getBoolean(AUTHORITY_REVOKED, !snapshot.revoked) == snapshot.revoked &&
            preferences.getStringSet(AUTHORITY_RECEIPTS, null) == encodedReceipts
    }
}

internal class WidgetHmacAuthenticator(private val secret: ByteArray) {
    fun issue(
        itemId: String,
        snapshotRevision: Long,
        authorityGeneration: Long,
        action: AttentionWidgetAction,
        nowEpochMillis: Long,
    ): AttentionWidgetActionRequest {
        val request = AttentionWidgetActionRequest(
            requestId = "widget_action_v1_${randomToken(18)}",
            itemId = itemId,
            snapshotRevision = snapshotRevision,
            authorityGeneration = authorityGeneration,
            action = action,
            issuedAtEpochMillis = nowEpochMillis,
            expiresAtEpochMillis = nowEpochMillis + ACTION_LIFETIME_MILLIS,
            authenticationTag = "0".repeat(43),
        )
        return request.copy(authenticationTag = tag(request))
    }

    fun authenticate(request: AttentionWidgetActionRequest): Boolean =
        MessageDigest.isEqual(
            tag(request).encodeToByteArray(),
            request.authenticationTag.encodeToByteArray(),
        )

    private fun tag(request: AttentionWidgetActionRequest): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(secret, HMAC_ALGORITHM))
        return Base64.encodeToString(
            mac.doFinal(request.authenticationPayload()),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    private fun randomToken(bytes: Int): String = Base64.encodeToString(
        ByteArray(bytes).also(SecureRandom()::nextBytes),
        Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
    )
}

private fun safeWidgetTitle(title: String?, fallback: String): String {
    val trimmed = title?.trim().orEmpty()
    if (trimmed.isEmpty() || PROTECTED_MARKERS.any { marker -> trimmed.contains(marker, ignoreCase = true) }) {
        return fallback
    }
    return trimmed.take(MAX_WIDGET_TITLE_CHARS)
}

private fun stableDigest(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString(separator = "") { "%02x".format(it) }

private const val PREFERENCES = "attention_widget_runtime_v1"
private const val SECRET = "hmac_secret"
private const val REVISION = "snapshot_revision"
private const val CONTENT_DIGEST = "snapshot_content_digest"
private const val AUTHORITY_GENERATION = "authority_generation"
private const val AUTHORITY_REVOKED = "authority_revoked"
private const val AUTHORITY_RECEIPTS = "authority_receipts"
private const val HMAC_ALGORITHM = "HmacSHA256"
private const val ACTION_LIFETIME_MILLIS = 15 * 60 * 1_000L
private const val MAX_PERSISTED_RECEIPTS = 128
private const val MEDIUM_WIDGET_WIDTH_DP = 240
private const val EXPANDED_WIDGET_WIDTH_DP = 320
private const val MAX_PROJECTED_WIDGET_ITEMS = 32
private const val MAX_WIDGET_TITLE_CHARS = 120
private val PROTECTED_MARKERS = listOf("secret", "token", "password", "credential")
