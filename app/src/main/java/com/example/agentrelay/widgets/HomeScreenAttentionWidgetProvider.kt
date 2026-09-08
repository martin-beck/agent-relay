package com.example.agentrelay.widgets

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.agentrelay.AgentRelayApplication
import com.example.agentrelay.R
import dev.agentrelay.session.api.AttentionWidgetAction
import dev.agentrelay.session.api.AttentionWidgetContent
import dev.agentrelay.session.api.AttentionWidgetEntry
import dev.agentrelay.session.api.AttentionWidgetSize

/** Adaptive home-screen projection for ranked attention items. */
class HomeScreenAttentionWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val application = context.applicationContext as? AgentRelayApplication
        if (application == null) {
            val views = HomeScreenAttentionWidgetRenderer.empty(context)
            ids.forEach { manager.updateAppWidget(it, views) }
            return
        }
        val pendingResult = goAsync()
        application.attentionWidgets.updateHome(manager, ids) { pendingResult.finish() }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            val component = android.content.ComponentName(context, javaClass)
            onUpdate(context, manager, manager.getAppWidgetIds(component))
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.example.agentrelay.action.REFRESH_HOME_WIDGET"
    }
}

object HomeScreenAttentionWidgetRenderer {
    fun empty(context: Context): RemoteViews = render(context, null, AttentionWidgetSize.COMPACT)

    fun render(
        context: Context,
        content: AttentionWidgetContent?,
        size: AttentionWidgetSize,
        actionIssuer: AttentionWidgetActionRequestIssuer? = null,
        phase: AttentionWidgetRenderPhase = AttentionWidgetRenderPhase.READY,
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_home_screen_attention)
        val state = state(context, content, size, phase)
        views.setTextViewText(R.id.home_widget_status, state.status)
        views.setTextViewText(R.id.home_widget_title, state.title)
        listOf(R.id.home_widget_item_one, R.id.home_widget_item_two, R.id.home_widget_item_three)
            .zip(state.items)
            .forEach { (id, item) -> views.setTextViewText(id, item) }
        listOf(R.id.home_widget_item_one, R.id.home_widget_item_two, R.id.home_widget_item_three)
            .drop(state.items.size)
            .forEach { views.setViewVisibility(it, android.view.View.GONE) }
        configureActions(context, views, state, content, actionIssuer)
        return views
    }

    private fun configureActions(
        context: Context,
        views: RemoteViews,
        state: HomeScreenWidgetState,
        content: AttentionWidgetContent?,
        actionIssuer: AttentionWidgetActionRequestIssuer?,
    ) {
        val entry = state.primaryEntry
        val factory = actionIssuer?.let { AttentionWidgetPendingIntentFactory(context, it) }
        val hasButtonAction = factory != null && state.actions.any { it != AttentionWidgetAction.OPEN_DETAILS }
        views.setViewVisibility(
            R.id.home_widget_actions,
            if (hasButtonAction) android.view.View.VISIBLE else android.view.View.GONE,
        )
        configureAction(
            context,
            views,
            R.id.home_widget_acknowledge,
            R.string.home_widget_action_acknowledge,
            entry,
            content,
            AttentionWidgetAction.ACKNOWLEDGE,
            AttentionWidgetAction.ACKNOWLEDGE in state.actions,
            factory,
        )
        configureAction(
            context,
            views,
            R.id.home_widget_defer,
            R.string.home_widget_action_defer,
            entry,
            content,
            AttentionWidgetAction.DEFER,
            AttentionWidgetAction.DEFER in state.actions,
            factory,
        )
        configureAction(
            context,
            views,
            R.id.home_widget_mute,
            R.string.home_widget_action_mute_confirm,
            entry,
            content,
            AttentionWidgetAction.MUTE,
            AttentionWidgetAction.MUTE in state.actions,
            factory,
        )
        if (entry != null && content != null && factory != null && AttentionWidgetAction.OPEN_DETAILS in state.actions) {
            views.setOnClickPendingIntent(
                R.id.home_widget_title,
                factory.create(entry.id, content.revision, AttentionWidgetAction.OPEN_DETAILS),
            )
            views.setContentDescription(
                R.id.home_widget_title,
                context.getString(R.string.home_widget_action_open_details_for, state.title),
            )
        }
    }

    private fun configureAction(
        context: Context,
        views: RemoteViews,
        viewId: Int,
        labelId: Int,
        entry: AttentionWidgetEntry?,
        content: AttentionWidgetContent?,
        action: AttentionWidgetAction,
        permittedAtSize: Boolean,
        factory: AttentionWidgetPendingIntentFactory?,
    ) {
        val visible = entry != null && content != null && factory != null && permittedAtSize
        views.setViewVisibility(viewId, if (visible) android.view.View.VISIBLE else android.view.View.GONE)
        if (visible) {
            views.setOnClickPendingIntent(
                viewId,
                checkNotNull(factory).create(checkNotNull(entry).id, checkNotNull(content).revision, action),
            )
            views.setContentDescription(viewId, context.getString(labelId))
        }
    }

    fun state(
        context: Context,
        content: AttentionWidgetContent?,
        size: AttentionWidgetSize,
        phase: AttentionWidgetRenderPhase = AttentionWidgetRenderPhase.READY,
    ): HomeScreenWidgetState {
        return state(
            content,
            size,
            context.getString(R.string.home_widget_no_attention),
            context.getString(R.string.home_widget_status_quiet),
            context.getString(R.string.home_widget_status_attention),
            context.getString(R.string.home_widget_status_refresh),
            context.getString(R.string.home_widget_loading),
            context.getString(R.string.home_widget_error),
            phase,
        )
    }

    internal fun state(
        content: AttentionWidgetContent?,
        size: AttentionWidgetSize,
        noAttention: String,
        quiet: String,
        attention: String,
        refresh: String,
        loading: String = "Checking attention…",
        error: String = "Attention unavailable",
        phase: AttentionWidgetRenderPhase = AttentionWidgetRenderPhase.READY,
    ): HomeScreenWidgetState {
        val entries = content?.entries.orEmpty()
        val limit = when (size) {
            AttentionWidgetSize.COMPACT -> 1
            AttentionWidgetSize.MEDIUM -> 2
            AttentionWidgetSize.EXPANDED -> 3
        }
        val ready = phase == AttentionWidgetRenderPhase.READY
        val primaryEntry = entries.firstOrNull().takeIf { ready }
        return HomeScreenWidgetState(
            status = when (phase) {
                AttentionWidgetRenderPhase.LOADING -> quiet
                AttentionWidgetRenderPhase.ERROR -> refresh
                AttentionWidgetRenderPhase.READY -> when {
                    entries.isEmpty() -> quiet
                    content?.stale == true -> refresh
                    else -> attention
                }
            },
            title = when (phase) {
                AttentionWidgetRenderPhase.LOADING -> loading
                AttentionWidgetRenderPhase.ERROR -> error
                AttentionWidgetRenderPhase.READY -> primaryEntry?.title ?: noAttention
            },
            items = if (ready) entries.take(limit).map { it.title } else emptyList(),
            primaryEntry = primaryEntry,
            actions = primaryEntry?.permittedActions().orEmpty().filterTo(linkedSetOf()) { action ->
                when (size) {
                    AttentionWidgetSize.COMPACT -> action == AttentionWidgetAction.OPEN_DETAILS
                    AttentionWidgetSize.MEDIUM -> action != AttentionWidgetAction.MUTE
                    AttentionWidgetSize.EXPANDED -> true
                }
            },
        )
    }
}

/** Explicit non-content states prevent stale attention and controls leaking during refresh failures. */
enum class AttentionWidgetRenderPhase { READY, LOADING, ERROR }

data class HomeScreenWidgetState(
    val status: String,
    val title: String,
    val items: List<String>,
    val primaryEntry: AttentionWidgetEntry?,
    val actions: Set<AttentionWidgetAction>,
)

private fun AttentionWidgetEntry.permittedActions(): Set<AttentionWidgetAction> =
    AttentionWidgetAction.entries.filterTo(linkedSetOf(), ::permits)
