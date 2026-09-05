package dev.agentrelay.connection.api

/** Opaque resumable authority; it contains identity and grant references, never transport metadata. */
data class ContinuityToken(
    val sessionId: String,
    val daemonIdentity: StableEndpointIdentity,
    val grantId: PairingGrantId,
    val tokenDigest: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val generation: Long,
) {
    init {
        require(sessionId.matches(SESSION_ID_PATTERN)) { "Continuity session id is invalid" }
        requireCanonicalContinuityDigest(tokenDigest, "Continuity token digest")
        require(issuedAtMillis >= 0)
        require(expiresAtMillis > issuedAtMillis)
        require(expiresAtMillis - issuedAtMillis <= MAX_CONTINUITY_LIFETIME_MILLIS)
        require(generation >= 1)
    }

    fun activeAt(nowMillis: Long): Boolean = nowMillis in issuedAtMillis until expiresAtMillis
}

enum class ContinuityState { DISCONNECTED, CONNECTED, FAILING_OVER, SUSPENDED }

data class ContinuitySnapshot(
    val state: ContinuityState,
    val transport: DaemonTransportKind?,
    val generation: Long,
    val reconnectAttempts: Int,
) {
    init {
        require(generation >= 1)
        require(reconnectAttempts >= 0)
        require(state == ContinuityState.CONNECTED == (transport != null))
    }
}

/** Bounded reconnect controller; a new transport never changes the authenticated session authority. */
class ContinuityController(
    private val token: ContinuityToken,
    private val maxReconnectAttempts: Int = 3,
) {
    private var snapshot = ContinuitySnapshot(ContinuityState.DISCONNECTED, null, token.generation, 0)

    init {
        require(maxReconnectAttempts in 1..MAX_RECONNECT_ATTEMPTS)
    }

    @Synchronized
    fun connect(
        presentedToken: ContinuityToken,
        transport: DaemonTransportKind,
        nowMillis: Long,
    ): ContinuitySnapshot {
        require(snapshot.state == ContinuityState.DISCONNECTED) { "Continuity session is already active" }
        validateToken(presentedToken, nowMillis)
        snapshot = ContinuitySnapshot(ContinuityState.CONNECTED, transport, token.generation, 0)
        return snapshot
    }

    @Synchronized
    fun disconnect(nowMillis: Long): ContinuitySnapshot {
        require(snapshot.state == ContinuityState.CONNECTED) { "Continuity session is not connected" }
        require(token.activeAt(nowMillis)) { "Continuity token has expired" }
        snapshot = ContinuitySnapshot(ContinuityState.FAILING_OVER, null, token.generation, 0)
        return snapshot
    }

    @Synchronized
    fun reconnect(
        presentedToken: ContinuityToken,
        transport: DaemonTransportKind,
        nowMillis: Long,
    ): ContinuitySnapshot {
        require(snapshot.state == ContinuityState.FAILING_OVER) { "Continuity session is not failing over" }
        validateToken(presentedToken, nowMillis)
        require(snapshot.reconnectAttempts < maxReconnectAttempts) { "Reconnect attempt limit reached" }
        snapshot = ContinuitySnapshot(
            ContinuityState.CONNECTED,
            transport,
            token.generation,
            snapshot.reconnectAttempts + 1,
        )
        return snapshot
    }

    @Synchronized
    fun suspend(nowMillis: Long): ContinuitySnapshot {
        require(snapshot.state == ContinuityState.CONNECTED) { "Continuity session is not connected" }
        require(token.activeAt(nowMillis)) { "Continuity token has expired" }
        snapshot = ContinuitySnapshot(ContinuityState.SUSPENDED, null, token.generation, snapshot.reconnectAttempts)
        return snapshot
    }

    fun snapshot(): ContinuitySnapshot = snapshot

    private fun validateToken(presentedToken: ContinuityToken, nowMillis: Long) {
        require(presentedToken == token) { "Continuity token differs" }
        require(presentedToken.activeAt(nowMillis)) { "Continuity token has expired" }
    }
}

@JvmInline
value class CommandId(val value: String) {
    init {
        require(value.matches(COMMAND_ID_PATTERN)) { "Command id is invalid" }
    }
}

enum class CommandDisposition { NEW, DUPLICATE_IN_FLIGHT, DUPLICATE_COMPLETED, UNKNOWN_DELIVERY }

data class CommandDecision(val id: CommandId, val disposition: CommandDisposition)

/** Replay-safe command ledger: partial delivery is explicitly unknown and is never auto-replayed. */
class CommandLedger {
    private val commands = linkedMapOf<CommandId, CommandDisposition>()

    @Synchronized
    fun begin(id: CommandId): CommandDecision {
        return when (commands[id]) {
            null -> {
                commands[id] = CommandDisposition.DUPLICATE_IN_FLIGHT
                CommandDecision(id, CommandDisposition.NEW)
            }
            CommandDisposition.DUPLICATE_IN_FLIGHT -> CommandDecision(id, CommandDisposition.DUPLICATE_IN_FLIGHT)
            CommandDisposition.DUPLICATE_COMPLETED -> CommandDecision(id, CommandDisposition.DUPLICATE_COMPLETED)
            CommandDisposition.UNKNOWN_DELIVERY -> CommandDecision(id, CommandDisposition.UNKNOWN_DELIVERY)
            CommandDisposition.NEW -> error("Command ledger cannot persist NEW")
        }
    }

    @Synchronized
    fun complete(id: CommandId): Boolean = transition(id, CommandDisposition.DUPLICATE_COMPLETED)

    @Synchronized
    fun markUnknown(id: CommandId): Boolean = transition(id, CommandDisposition.UNKNOWN_DELIVERY)

    @Synchronized
    fun disposition(id: CommandId): CommandDisposition? = commands[id]

    private fun transition(id: CommandId, target: CommandDisposition): Boolean {
        if (commands[id] != CommandDisposition.DUPLICATE_IN_FLIGHT) return false
        commands[id] = target
        return true
    }
}

private fun requireCanonicalContinuityDigest(value: String, field: String) {
    require(value.length == CONTINUITY_DIGEST_LENGTH) { "$field must be 32 bytes" }
    require(value.matches(BASE64_URL_PATTERN) && !value.contains('=')) { "$field must be base64url" }
}

private const val MAX_CONTINUITY_LIFETIME_MILLIS = 30 * 24 * 60 * 60 * 1_000L
private const val MAX_RECONNECT_ATTEMPTS = 8
private const val CONTINUITY_DIGEST_LENGTH = 43
private val SESSION_ID_PATTERN = Regex("[a-z][a-z0-9-]{2,63}")
private val COMMAND_ID_PATTERN = Regex("[a-z][a-z0-9-]{2,63}")
private val BASE64_URL_PATTERN = Regex("[A-Za-z0-9_-]+")
