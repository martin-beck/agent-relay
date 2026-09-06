package com.example.agentrelay.widgets

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import dev.agentrelay.session.api.AttentionWidgetAction
import dev.agentrelay.session.api.AttentionWidgetActionRequest

/** Mints a short-lived request from the current authoritative widget projection. */
fun interface AttentionWidgetActionRequestIssuer {
    fun issue(itemId: String, snapshotRevision: Long, action: AttentionWidgetAction): AttentionWidgetActionRequest
}

internal class AttentionWidgetPendingIntentFactory(
    private val context: Context,
    private val issuer: AttentionWidgetActionRequestIssuer,
) {
    fun create(itemId: String, snapshotRevision: Long, action: AttentionWidgetAction): PendingIntent {
        val request = issuer.issue(itemId, snapshotRevision, action)
        check(request.itemId == itemId && request.snapshotRevision == snapshotRevision && request.action == action) {
            "Widget action issuer returned a mismatched request"
        }
        return PendingIntent.getBroadcast(
            context,
            request.requestId.hashCode(),
            request.toIntent(context),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

internal fun AttentionWidgetActionRequest.toIntent(context: Context): Intent =
    Intent(context, AttentionWidgetActionReceiver::class.java)
        .setAction(ACTION_WIDGET_CONTROL)
        .setPackage(context.packageName)
        .setData("agent-relay-widget://control/$requestId".toUri())
        .putExtra(EXTRA_REQUEST_ID, requestId)
        .putExtra(EXTRA_ITEM_ID, itemId)
        .putExtra(EXTRA_SNAPSHOT_REVISION, snapshotRevision)
        .putExtra(EXTRA_AUTHORITY_GENERATION, authorityGeneration)
        .putExtra(EXTRA_ACTION, action.name)
        .putExtra(EXTRA_ISSUED_AT, issuedAtEpochMillis)
        .putExtra(EXTRA_EXPIRES_AT, expiresAtEpochMillis)
        .putExtra(EXTRA_AUTHENTICATION_TAG, authenticationTag)

internal fun Intent.toAttentionWidgetActionRequest(packageName: String): AttentionWidgetActionRequest? {
    if (action != ACTION_WIDGET_CONTROL || `package` != packageName) return null
    val requestId = getStringExtra(EXTRA_REQUEST_ID) ?: return null
    if (data != "agent-relay-widget://control/$requestId".toUri()) return null
    val parsedAction = getStringExtra(EXTRA_ACTION)?.let { value ->
        AttentionWidgetAction.entries.firstOrNull { it.name == value }
    } ?: return null
    return runCatching {
        AttentionWidgetActionRequest(
            requestId = requestId,
            itemId = getStringExtra(EXTRA_ITEM_ID) ?: return null,
            snapshotRevision = getLongExtra(EXTRA_SNAPSHOT_REVISION, INVALID_LONG),
            authorityGeneration = getLongExtra(EXTRA_AUTHORITY_GENERATION, INVALID_LONG),
            action = parsedAction,
            issuedAtEpochMillis = getLongExtra(EXTRA_ISSUED_AT, INVALID_LONG),
            expiresAtEpochMillis = getLongExtra(EXTRA_EXPIRES_AT, INVALID_LONG),
            authenticationTag = getStringExtra(EXTRA_AUTHENTICATION_TAG) ?: return null,
        )
    }.getOrNull()
}

/**
 * Non-exported Android boundary. The state-refresh runtime installs the authoritative processor;
 * absent or process-dead runtimes fail closed instead of trusting a stale PendingIntent.
 */
class AttentionWidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val request = intent.toAttentionWidgetActionRequest(context.packageName) ?: return
        AttentionWidgetActionRuntimeHolder.runtime?.process(request)
    }
}

internal fun interface AttentionWidgetActionRuntime {
    fun process(request: AttentionWidgetActionRequest)
}

internal object AttentionWidgetActionRuntimeHolder {
    @Volatile
    var runtime: AttentionWidgetActionRuntime? = null
}

private const val ACTION_WIDGET_CONTROL = "com.example.agentrelay.action.WIDGET_CONTROL"
private const val EXTRA_REQUEST_ID = "com.example.agentrelay.widget.REQUEST_ID"
private const val EXTRA_ITEM_ID = "com.example.agentrelay.widget.ITEM_ID"
private const val EXTRA_SNAPSHOT_REVISION = "com.example.agentrelay.widget.SNAPSHOT_REVISION"
private const val EXTRA_AUTHORITY_GENERATION = "com.example.agentrelay.widget.AUTHORITY_GENERATION"
private const val EXTRA_ACTION = "com.example.agentrelay.widget.ACTION"
private const val EXTRA_ISSUED_AT = "com.example.agentrelay.widget.ISSUED_AT"
private const val EXTRA_EXPIRES_AT = "com.example.agentrelay.widget.EXPIRES_AT"
private const val EXTRA_AUTHENTICATION_TAG = "com.example.agentrelay.widget.AUTHENTICATION_TAG"
private const val INVALID_LONG = -1L
