package dev.agentrelay.session.api

/** Why a widget asks for a new projection. */
enum class AttentionWidgetRefreshReason {
    ATTENTION_CHANGED,
    WIDGET_BOUND,
    PERIODIC,
    CLOCK_CHANGED,
    PROCESS_RESTART,
}

/** The scheduler never refreshes more often than this policy permits. */
data class AttentionWidgetRefreshPolicy(
    val minimumIntervalMillis: Long = 15 * 60 * 1_000,
    val pollingIntervalMillis: Long = 30 * 60 * 1_000,
) {
    init {
        require(minimumIntervalMillis > 0) { "Widget minimum interval must be positive" }
        require(pollingIntervalMillis >= minimumIntervalMillis) {
            "Widget polling interval cannot be shorter than the minimum interval"
        }
    }
}

enum class AttentionWidgetRefreshDecision { EMIT, COALESCED, THROTTLED, OFFLINE }

/** Persist this state with the widget runtime to survive process death without an update storm. */
data class AttentionWidgetRefreshState(
    val lastPublishedRevision: Long = -1,
    val lastPublishedAtEpochMillis: Long = -1,
    val pendingRevision: Long? = null,
) {
    init {
        require(lastPublishedRevision >= -1) { "Widget revision state is invalid" }
        require(lastPublishedAtEpochMillis >= -1) { "Widget timestamp state is invalid" }
        require(pendingRevision == null || pendingRevision >= 0) { "Widget pending revision is invalid" }
    }
}

/** In-memory authoritative projection boundary; callers persist the snapshot in their own store. */
class AttentionWidgetSnapshotStore(initial: AttentionWidgetSnapshot) {
    private var current = initial
    private val listeners = linkedSetOf<(AttentionWidgetSnapshot) -> Unit>()

    @Synchronized
    fun current(): AttentionWidgetSnapshot = current

    /** Publishes only newer revisions and notifies listeners once per accepted revision. */
    @Synchronized
    fun publish(snapshot: AttentionWidgetSnapshot): Boolean {
        if (snapshot.revision <= current.revision) return false
        current = snapshot
        listeners.toList().forEach { it(snapshot) }
        return true
    }

    @Synchronized
    fun observe(listener: (AttentionWidgetSnapshot) -> Unit): () -> Unit {
        listeners += listener
        return { synchronized(this) { listeners -= listener } }
    }
}

/** Coalesces event bursts and bounds periodic fallback refreshes for one widget instance. */
class AttentionWidgetRefreshScheduler(
    private val policy: AttentionWidgetRefreshPolicy = AttentionWidgetRefreshPolicy(),
    initialState: AttentionWidgetRefreshState = AttentionWidgetRefreshState(),
) {
    private var state = initialState
    private var offline = false

    @Synchronized
    fun setOffline(value: Boolean) {
        offline = value
    }

    @Synchronized
    fun state(): AttentionWidgetRefreshState = state

    @Synchronized
    fun request(
        revision: Long,
        nowEpochMillis: Long,
        reason: AttentionWidgetRefreshReason,
    ): AttentionWidgetRefreshDecision {
        require(revision >= 0) { "Widget revision must be non-negative" }
        require(nowEpochMillis >= 0) { "Widget time must be non-negative" }
        val isNewRevision = revision > state.lastPublishedRevision
        if (!isNewRevision && reason != AttentionWidgetRefreshReason.PROCESS_RESTART) {
            return AttentionWidgetRefreshDecision.COALESCED
        }
        state = state.copy(pendingRevision = maxOf(revision, state.pendingRevision ?: revision))
        if (offline) return AttentionWidgetRefreshDecision.OFFLINE
        val elapsed = nowEpochMillis - state.lastPublishedAtEpochMillis
        if (state.lastPublishedAtEpochMillis >= 0 && elapsed < policy.minimumIntervalMillis) {
            return AttentionWidgetRefreshDecision.THROTTLED
        }
        state = state.copy(
            lastPublishedRevision = state.pendingRevision ?: revision,
            lastPublishedAtEpochMillis = nowEpochMillis,
            pendingRevision = null,
        )
        return AttentionWidgetRefreshDecision.EMIT
    }

    @Synchronized
    fun poll(revision: Long, nowEpochMillis: Long): AttentionWidgetRefreshDecision =
        request(revision, nowEpochMillis, AttentionWidgetRefreshReason.PERIODIC)
}

/** Keeps separate widget instances from consuming each other's throttle or pending state. */
class AttentionWidgetRefreshCoordinator(
    private val policy: AttentionWidgetRefreshPolicy = AttentionWidgetRefreshPolicy(),
) {
    private val schedulers = mutableMapOf<Int, AttentionWidgetRefreshScheduler>()

    @Synchronized
    fun request(
        appWidgetId: Int,
        revision: Long,
        nowEpochMillis: Long,
        reason: AttentionWidgetRefreshReason,
    ): AttentionWidgetRefreshDecision = scheduler(appWidgetId).request(revision, nowEpochMillis, reason)

    @Synchronized
    fun state(appWidgetId: Int): AttentionWidgetRefreshState = scheduler(appWidgetId).state()

    @Synchronized
    fun remove(appWidgetId: Int) {
        schedulers.remove(appWidgetId)
    }

    private fun scheduler(appWidgetId: Int): AttentionWidgetRefreshScheduler {
        require(appWidgetId >= 0) { "Widget id must be non-negative" }
        return schedulers.getOrPut(appWidgetId) { AttentionWidgetRefreshScheduler(policy) }
    }
}
