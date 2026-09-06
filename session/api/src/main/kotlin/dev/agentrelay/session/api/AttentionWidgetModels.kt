package dev.agentrelay.session.api

/** The supported amount of glanceable space available to an attention widget. */
enum class AttentionWidgetSize { COMPACT, MEDIUM, EXPANDED }

/** A widget surface controls privacy defaults as well as its layout. */
enum class AttentionWidgetSurface { HOME_SCREEN, LOCK_SCREEN }

/** Safe, already-redacted information that may leave the attention store. */
data class AttentionWidgetItem(
    val id: String,
    val title: String,
    val summary: String?,
    val urgency: AttentionUrgency,
    val state: AttentionState,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long?,
    val canOpen: Boolean,
    val canAcknowledge: Boolean,
) {
    init {
        require(id.isNotBlank() && id.length <= MAX_WIDGET_TEXT_CHARS) { "Widget item id is invalid" }
        require(title.isNotBlank() && title.length <= MAX_WIDGET_TEXT_CHARS) { "Widget title is invalid" }
        summary?.let {
            require(it.isNotBlank() && it.length <= MAX_WIDGET_SUMMARY_CHARS) { "Widget summary is invalid" }
        }
        require(listOf(title, summary).filterNotNull().none(::containsProtectedMarker)) {
            "Widget content contains protected content"
        }
        require(createdAtEpochMillis >= 0) { "Widget creation time is invalid" }
        require(expiresAtEpochMillis == null || expiresAtEpochMillis >= createdAtEpochMillis) {
            "Widget expiry is invalid"
        }
        require(state in ACTIVE_WIDGET_STATES || !canAcknowledge) {
            "Closed widget items cannot be acknowledged"
        }
        require(state in ACTIVE_WIDGET_STATES || !canOpen) { "Closed widget items cannot be opened" }
    }

    fun isVisibleAt(nowEpochMillis: Long): Boolean =
        nowEpochMillis >= createdAtEpochMillis &&
            (expiresAtEpochMillis == null || nowEpochMillis < expiresAtEpochMillis) &&
            state in ACTIVE_WIDGET_STATES
}

/** The complete bounded projection consumed by home- and lock-screen providers. */
data class AttentionWidgetSnapshot(
    val revision: Long,
    val generatedAtEpochMillis: Long,
    val items: List<AttentionWidgetItem>,
) {
    init {
        require(revision >= 0) { "Widget revision is invalid" }
        require(generatedAtEpochMillis >= 0) { "Widget generation time is invalid" }
        require(items.size <= MAX_WIDGET_ITEMS) { "Widget item count is too large" }
        require(items.zipWithNext().all { (left, right) -> left.id != right.id }) {
            "Widget item ids must be unique"
        }
    }

    fun contentFor(
        size: AttentionWidgetSize,
        surface: AttentionWidgetSurface,
        nowEpochMillis: Long = generatedAtEpochMillis,
    ): AttentionWidgetContent {
        require(nowEpochMillis >= generatedAtEpochMillis) { "Widget time cannot precede generation" }
        val visible = AttentionWidgetRanking.rank(items, nowEpochMillis)
        val limit = when (size) {
            AttentionWidgetSize.COMPACT -> 1
            AttentionWidgetSize.MEDIUM -> 3
            AttentionWidgetSize.EXPANDED -> 5
        }
        val entries = visible.take(limit).map { item ->
            AttentionWidgetEntry(
                id = item.id,
                title = item.title,
                summary = if (surface == AttentionWidgetSurface.HOME_SCREEN && size == AttentionWidgetSize.EXPANDED) {
                    item.summary
                } else {
                    null
                },
                urgency = item.urgency,
                ageMillis = if (size == AttentionWidgetSize.COMPACT) null else nowEpochMillis - item.createdAtEpochMillis,
                canOpen = surface == AttentionWidgetSurface.HOME_SCREEN && item.canOpen,
                canAcknowledge = surface == AttentionWidgetSurface.HOME_SCREEN && item.canAcknowledge,
            )
        }
        return AttentionWidgetContent(
            revision = revision,
            size = size,
            surface = surface,
            entries = entries,
            hasMore = visible.size > limit,
            stale = visible.isEmpty() && items.any { !it.isVisibleAt(nowEpochMillis) },
        )
    }
}

data class AttentionWidgetEntry(
    val id: String,
    val title: String,
    val summary: String?,
    val urgency: AttentionUrgency,
    val ageMillis: Long?,
    val canOpen: Boolean,
    val canAcknowledge: Boolean,
)

data class AttentionWidgetContent(
    val revision: Long,
    val size: AttentionWidgetSize,
    val surface: AttentionWidgetSurface,
    val entries: List<AttentionWidgetEntry>,
    val hasMore: Boolean,
    val stale: Boolean,
)

/** Deterministic ranking keeps the most urgent and oldest unresolved work visible first. */
object AttentionWidgetRanking {
    fun rank(items: Iterable<AttentionWidgetItem>, nowEpochMillis: Long): List<AttentionWidgetItem> =
        items.filter { it.isVisibleAt(nowEpochMillis) }
            .sortedWith(
                compareByDescending<AttentionWidgetItem> { it.urgency.ordinal }
                    .thenByDescending { stateRank(it.state) }
                    .thenBy { it.createdAtEpochMillis }
                    .thenBy { it.id },
            )

    private fun stateRank(state: AttentionState): Int = when (state) {
        AttentionState.ESCALATED -> 2
        AttentionState.OPEN -> 1
        AttentionState.SNOOZED -> 0
        else -> -1
    }
}

private val ACTIVE_WIDGET_STATES = setOf(AttentionState.OPEN, AttentionState.SNOOZED, AttentionState.ESCALATED)
private const val MAX_WIDGET_ITEMS = 32
private const val MAX_WIDGET_TEXT_CHARS = 120
private const val MAX_WIDGET_SUMMARY_CHARS = 240

private fun containsProtectedMarker(value: String): Boolean =
    listOf("secret", "token", "password", "credential").any { marker ->
        value.contains(marker, ignoreCase = true)
    }
