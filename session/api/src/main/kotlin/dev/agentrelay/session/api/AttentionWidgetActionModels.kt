package dev.agentrelay.session.api

/** Widget controls never imply approval of an agent-requested privileged action. */
enum class AttentionWidgetAction { OPEN_DETAILS, ACKNOWLEDGE, DEFER, MUTE }

/**
 * An immutable, authenticated capability carried by an app-owned PendingIntent.
 *
 * Authentication covers [authenticationPayload]. The target and revision are deliberately opaque:
 * callers must reload authoritative state and resolve them inside the app before applying an action.
 */
data class AttentionWidgetActionRequest(
    val requestId: String,
    val itemId: String,
    val snapshotRevision: Long,
    val authorityGeneration: Long,
    val action: AttentionWidgetAction,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val authenticationTag: String,
) {
    init {
        require(requestId.matches(REQUEST_ID_PATTERN)) { "Widget action request id is invalid" }
        require(itemId.isNotBlank() && itemId.length <= MAX_ITEM_ID_CHARS && !itemId.contains('\u0000')) {
            "Widget action item id is invalid"
        }
        require(snapshotRevision >= 0) { "Widget snapshot revision is invalid" }
        require(authorityGeneration > 0) { "Widget authority generation must be positive" }
        require(issuedAtEpochMillis >= 0) { "Widget action issue time is invalid" }
        require(expiresAtEpochMillis > issuedAtEpochMillis) { "Widget action expiry is invalid" }
        require(expiresAtEpochMillis - issuedAtEpochMillis <= MAX_ACTION_LIFETIME_MILLIS) {
            "Widget action lifetime is too long"
        }
        require(authenticationTag.matches(AUTHENTICATION_TAG_PATTERN)) {
            "Widget action authentication tag is invalid"
        }
    }

    fun isFreshAt(nowEpochMillis: Long): Boolean =
        nowEpochMillis in issuedAtEpochMillis until expiresAtEpochMillis

    /** Stable length-prefixed bytes avoid ambiguous concatenation in signing implementations. */
    fun authenticationPayload(): ByteArray = listOf(
        requestId,
        itemId,
        snapshotRevision.toString(),
        authorityGeneration.toString(),
        action.name,
        issuedAtEpochMillis.toString(),
        expiresAtEpochMillis.toString(),
    )
        .joinToString(separator = "") { value -> "${value.length}:$value" }
        .encodeToByteArray()
}

/** Persist this bounded state to retain revocation and replay protection across process death. */
data class AttentionWidgetActionReceipt(
    val requestId: String,
    val expiresAtEpochMillis: Long,
) {
    init {
        require(requestId.matches(REQUEST_ID_PATTERN)) { "Widget action receipt id is invalid" }
        require(expiresAtEpochMillis > 0) { "Widget action receipt expiry is invalid" }
    }
}

/** Persist this bounded state to retain revocation and replay protection across process death. */
data class AttentionWidgetActionAuthoritySnapshot(
    val generation: Long,
    val revoked: Boolean,
    val consumedRequests: List<AttentionWidgetActionReceipt> = emptyList(),
) {
    init {
        require(generation > 0) { "Widget authority generation must be positive" }
        require(consumedRequests.size <= MAX_REPLAY_ENTRIES) { "Widget replay ledger is too large" }
        require(consumedRequests.distinctBy(AttentionWidgetActionReceipt::requestId).size == consumedRequests.size) {
            "Widget replay ledger contains duplicates"
        }
        require(!revoked || consumedRequests.isEmpty()) {
            "Revoked widget authority cannot retain live request ids"
        }
    }
}

enum class AttentionWidgetActionExecutionResult {
    APPLIED,
    STALE_STATE,
    REVOKED,
    NOT_ALLOWED,
    UNAVAILABLE,
}

enum class AttentionWidgetActionOutcome {
    EXECUTED,
    CONFIRMATION_REQUIRED,
    REJECT_UNAUTHORIZED,
    REJECT_REVOKED,
    REJECT_EXPIRED,
    REJECT_DUPLICATE,
    REJECT_STALE,
    REJECT_NOT_ALLOWED,
    REJECT_CAPACITY,
    REJECT_PERSISTENCE,
    EXECUTION_UNCERTAIN,
}

fun interface AttentionWidgetActionAuthenticator {
    /** Implementations must compare authentication tags in constant time. */
    fun authenticate(request: AttentionWidgetActionRequest): Boolean
}

fun interface AttentionWidgetActionStateSource {
    /** Reloads the current authoritative projection for every attempted action. */
    fun currentSnapshot(): AttentionWidgetSnapshot
}

fun interface AttentionWidgetActionExecutor {
    /**
     * Revalidates [AttentionWidgetActionRequest.snapshotRevision] atomically with the mutation.
     * A thrown failure is treated as uncertain because an external effect may already have happened.
     */
    fun execute(request: AttentionWidgetActionRequest): AttentionWidgetActionExecutionResult
}

fun interface AttentionWidgetActionAuthorityStore {
    /** Atomically durably replaces the authority snapshot; false means no durable change. */
    fun commit(snapshot: AttentionWidgetActionAuthoritySnapshot): Boolean
}

/**
 * Serialized admission boundary for app-widget actions.
 *
 * Invariants: authentication precedes disclosure, authority generations revoke every old intent,
 * a request id executes at most once, current state is reloaded before admission, and mute always
 * crosses an in-app confirmation boundary. The executor performs the final compare-and-set.
 */
class AttentionWidgetActionProcessor(
    initialAuthority: AttentionWidgetActionAuthoritySnapshot,
    private val authenticator: AttentionWidgetActionAuthenticator,
    private val stateSource: AttentionWidgetActionStateSource,
    private val executor: AttentionWidgetActionExecutor,
    private val authorityStore: AttentionWidgetActionAuthorityStore,
) {
    private val lock = Any()
    private var generation = initialAuthority.generation
    private var revoked = initialAuthority.revoked
    private val consumedRequests = LinkedHashMap(
        initialAuthority.consumedRequests.associate { it.requestId to it.expiresAtEpochMillis },
    )

    fun process(request: AttentionWidgetActionRequest, nowEpochMillis: Long): AttentionWidgetActionOutcome =
        synchronized(lock) {
            if (!authenticator.authenticate(request)) {
                return@synchronized AttentionWidgetActionOutcome.REJECT_UNAUTHORIZED
            }
            if (revoked || request.authorityGeneration != generation) {
                return@synchronized AttentionWidgetActionOutcome.REJECT_REVOKED
            }
            if (!request.isFreshAt(nowEpochMillis)) {
                return@synchronized AttentionWidgetActionOutcome.REJECT_EXPIRED
            }
            consumedRequests.entries.removeAll { (_, expiry) -> expiry <= nowEpochMillis }
            if (request.requestId in consumedRequests) {
                return@synchronized AttentionWidgetActionOutcome.REJECT_DUPLICATE
            }

            val current = stateSource.currentSnapshot()
            if (current.revision != request.snapshotRevision) {
                return@synchronized AttentionWidgetActionOutcome.REJECT_STALE
            }
            val entry = current
                .contentFor(AttentionWidgetSize.EXPANDED, AttentionWidgetSurface.HOME_SCREEN, nowEpochMillis)
                .entries
                .firstOrNull { it.id == request.itemId }
                ?: return@synchronized AttentionWidgetActionOutcome.REJECT_STALE
            if (!entry.permits(request.action)) {
                return@synchronized AttentionWidgetActionOutcome.REJECT_NOT_ALLOWED
            }
            if (consumedRequests.size >= MAX_REPLAY_ENTRIES) {
                return@synchronized AttentionWidgetActionOutcome.REJECT_CAPACITY
            }

            val consumed = authoritySnapshot(
                consumedRequests + (request.requestId to request.expiresAtEpochMillis),
            )
            if (!authorityStore.commit(consumed)) {
                return@synchronized AttentionWidgetActionOutcome.REJECT_PERSISTENCE
            }
            recordConsumed(request)
            if (request.action == AttentionWidgetAction.MUTE) {
                return@synchronized AttentionWidgetActionOutcome.CONFIRMATION_REQUIRED
            }
            try {
                when (executor.execute(request)) {
                    AttentionWidgetActionExecutionResult.APPLIED -> AttentionWidgetActionOutcome.EXECUTED
                    AttentionWidgetActionExecutionResult.STALE_STATE -> AttentionWidgetActionOutcome.REJECT_STALE
                    AttentionWidgetActionExecutionResult.REVOKED -> AttentionWidgetActionOutcome.REJECT_REVOKED
                    AttentionWidgetActionExecutionResult.NOT_ALLOWED -> AttentionWidgetActionOutcome.REJECT_NOT_ALLOWED
                    AttentionWidgetActionExecutionResult.UNAVAILABLE -> AttentionWidgetActionOutcome.EXECUTION_UNCERTAIN
                }
            } catch (_: Exception) {
                AttentionWidgetActionOutcome.EXECUTION_UNCERTAIN
            }
        }

    fun revoke(): AttentionWidgetActionAuthoritySnapshot = synchronized(lock) {
        val revokedAuthority = AttentionWidgetActionAuthoritySnapshot(
            generation = generation + 1,
            revoked = true,
        )
        check(authorityStore.commit(revokedAuthority)) { "Widget authority revocation was not persisted" }
        generation = revokedAuthority.generation
        revoked = true
        consumedRequests.clear()
        revokedAuthority
    }

    fun activate(nextGeneration: Long): AttentionWidgetActionAuthoritySnapshot = synchronized(lock) {
        require(nextGeneration > generation) { "Widget authority generation must advance" }
        val activeAuthority = AttentionWidgetActionAuthoritySnapshot(
            generation = nextGeneration,
            revoked = false,
        )
        check(authorityStore.commit(activeAuthority)) { "Widget authority activation was not persisted" }
        generation = nextGeneration
        revoked = false
        consumedRequests.clear()
        activeAuthority
    }

    fun snapshot(): AttentionWidgetActionAuthoritySnapshot = synchronized(lock) { authoritySnapshot() }

    private fun recordConsumed(request: AttentionWidgetActionRequest) {
        consumedRequests[request.requestId] = request.expiresAtEpochMillis
    }

    private fun authoritySnapshot(
        receipts: Map<String, Long> = consumedRequests,
    ) = AttentionWidgetActionAuthoritySnapshot(
        generation = generation,
        revoked = revoked,
        consumedRequests = receipts.map { (requestId, expiresAtEpochMillis) ->
            AttentionWidgetActionReceipt(requestId, expiresAtEpochMillis)
        },
    )
}

private val REQUEST_ID_PATTERN = Regex("widget_action_v1_[A-Za-z0-9_-]{12,80}")
private val AUTHENTICATION_TAG_PATTERN = Regex("[A-Za-z0-9_-]{16,128}")
private const val MAX_ITEM_ID_CHARS = 120
private const val MAX_ACTION_LIFETIME_MILLIS = 60 * 60 * 1_000L
private const val MAX_REPLAY_ENTRIES = 128
