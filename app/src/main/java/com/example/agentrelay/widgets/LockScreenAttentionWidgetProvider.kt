package com.example.agentrelay.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.agentrelay.AgentRelayApplication
import com.example.agentrelay.R
import dev.agentrelay.session.api.AttentionWidgetContent

/** A privacy-first widget host for lock-screen capable Android launchers. */
class LockScreenAttentionWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val application = context.applicationContext as? AgentRelayApplication
        if (application == null) {
            val fallback = LockScreenAttentionWidgetRenderer.empty(context)
            ids.forEach { manager.updateAppWidget(it, fallback) }
            return
        }
        val pendingResult = goAsync()
        application.attentionWidgets.updateLock(manager, ids) { pendingResult.finish() }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            onUpdate(context, manager, manager.getAppWidgetIds(componentName(context)))
        }
    }

    private fun componentName(context: Context) =
        android.content.ComponentName(context, LockScreenAttentionWidgetProvider::class.java)

    companion object {
        const val ACTION_REFRESH = "com.example.agentrelay.action.REFRESH_LOCK_WIDGET"
    }
}

/** Converts already-redacted session API content into lock-screen-safe RemoteViews. */
object LockScreenAttentionWidgetRenderer {
    fun empty(context: Context): RemoteViews = render(context, null)

    fun render(
        context: Context,
        content: AttentionWidgetContent?,
        phase: AttentionWidgetRenderPhase = AttentionWidgetRenderPhase.READY,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_lock_screen_attention)
        val state = state(context, content, phase)
        views.setTextViewText(R.id.lock_widget_title, state.title)
        views.setTextViewText(R.id.lock_widget_status, state.status)
        // Lock-screen projections intentionally omit summaries, ages, and all action intents.
        views.setTextViewText(R.id.lock_widget_summary, "")
        views.setViewVisibility(R.id.lock_widget_summary, android.view.View.GONE)
        return views
    }

    fun state(
        context: Context,
        content: AttentionWidgetContent?,
        phase: AttentionWidgetRenderPhase = AttentionWidgetRenderPhase.READY,
    ): LockScreenWidgetState {
        return state(
            content,
            context.getString(R.string.lock_widget_no_attention),
            context.getString(R.string.lock_widget_status_quiet),
            context.getString(R.string.lock_widget_status_attention),
            context.getString(R.string.lock_widget_status_refresh),
            context.getString(R.string.lock_widget_loading),
            context.getString(R.string.lock_widget_error),
            phase,
        )
    }

    internal fun state(
        content: AttentionWidgetContent?,
        noAttention: String,
        quiet: String,
        attention: String,
        refresh: String,
        loading: String = "Checking attention…",
        error: String = "Attention unavailable",
        phase: AttentionWidgetRenderPhase = AttentionWidgetRenderPhase.READY,
    ): LockScreenWidgetState {
        val entry = content?.entries?.firstOrNull().takeIf { phase == AttentionWidgetRenderPhase.READY }
        return LockScreenWidgetState(
            title = when (phase) {
                AttentionWidgetRenderPhase.LOADING -> loading
                AttentionWidgetRenderPhase.ERROR -> error
                AttentionWidgetRenderPhase.READY -> entry?.title ?: noAttention
            },
            status = when {
                phase == AttentionWidgetRenderPhase.LOADING -> quiet
                phase == AttentionWidgetRenderPhase.ERROR -> refresh
                entry == null -> quiet
                content?.stale == true -> refresh
                else -> attention
            },
        )
    }
}

data class LockScreenWidgetState(val title: String, val status: String)
