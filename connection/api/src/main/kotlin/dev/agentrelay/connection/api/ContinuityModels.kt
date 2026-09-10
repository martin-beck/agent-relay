/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

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

@JvmInline
value class ContinuityDeviceId(val value: String) {
    init {
        require(value.matches(DEVICE_ID_PATTERN)) { "Continuity device id is invalid" }
    }
}

data class ContinuityCursor(val generation: Long, val sequence: Long) {
    init {
        require(generation >= 1) { "Continuity generation is invalid" }
        require(sequence >= 0) { "Continuity sequence is invalid" }
    }
}

data class ContinuityUpdate(val cursor: ContinuityCursor, val attentionDigest: String) {
    init {
        requireCanonicalContinuityDigest(attentionDigest, "Attention digest")
    }
}

enum class ContinuityDeviceState { TRUSTED, OFFLINE, REVOKED }

data class ContinuityDeviceSnapshot(
    val device: ContinuityDeviceId,
    val state: ContinuityDeviceState,
    val acknowledged: ContinuityCursor,
)

/**
 * Ordered, replay-safe projection ledger shared by phone and companion devices.
 * It retains opaque updates until every trusted device acknowledges them.
 */
class CrossDeviceContinuityLedger(private val sessionId: String, generation: Long = 1) {
    private var cursor = ContinuityCursor(generation, 0)
    private val devices = linkedMapOf<ContinuityDeviceId, DeviceRecord>()
    private val updates = mutableListOf<ContinuityUpdate>()

    init {
        require(sessionId.matches(SESSION_ID_PATTERN)) { "Continuity session id is invalid" }
    }

    @Synchronized
    fun enroll(device: ContinuityDeviceId): ContinuityDeviceSnapshot {
        val record = devices[device]
        require(record?.state != ContinuityDeviceState.REVOKED) { "Device has been revoked" }
        val next = DeviceRecord(ContinuityDeviceState.TRUSTED, record?.acknowledged ?: cursor)
        devices[device] = next
        return snapshot(device)
    }

    @Synchronized
    fun publish(attentionDigest: String): ContinuityUpdate {
        val update = ContinuityUpdate(cursor.copy(sequence = cursor.sequence + 1), attentionDigest)
        cursor = update.cursor
        updates += update
        return update
    }

    @Synchronized
    fun markOffline(device: ContinuityDeviceId): ContinuityDeviceSnapshot {
        val record = requireRecord(device)
        require(record.state == ContinuityDeviceState.TRUSTED) { "Device is not trusted" }
        record.state = ContinuityDeviceState.OFFLINE
        return snapshot(device)
    }

    @Synchronized
    fun reconnect(device: ContinuityDeviceId, acknowledged: ContinuityCursor): List<ContinuityUpdate> {
        val record = requireRecord(device)
        require(record.state != ContinuityDeviceState.REVOKED) { "Device has been revoked" }
        acknowledge(device, acknowledged)
        record.state = ContinuityDeviceState.TRUSTED
        return updates.filter { it.cursor.sequence > record.acknowledged.sequence }
    }

    @Synchronized
    fun acknowledge(device: ContinuityDeviceId, acknowledged: ContinuityCursor): ContinuityDeviceSnapshot {
        val record = requireRecord(device)
        require(record.state != ContinuityDeviceState.REVOKED) { "Device has been revoked" }
        require(acknowledged.generation == cursor.generation) { "Cursor generation differs" }
        require(acknowledged.sequence <= cursor.sequence) { "Cursor is ahead of authority" }
        require(acknowledged.sequence >= record.acknowledged.sequence) { "Cursor acknowledgement regressed" }
        record.acknowledged = acknowledged
        return snapshot(device)
    }

    @Synchronized
    fun revoke(device: ContinuityDeviceId): ContinuityDeviceSnapshot {
        val record = requireRecord(device)
        record.state = ContinuityDeviceState.REVOKED
        return snapshot(device)
    }

    @Synchronized
    fun pendingFor(device: ContinuityDeviceId): List<ContinuityUpdate> {
        val record = requireRecord(device)
        require(record.state != ContinuityDeviceState.REVOKED) { "Device has been revoked" }
        return updates.filter { it.cursor.sequence > record.acknowledged.sequence }
    }

    private fun snapshot(device: ContinuityDeviceId): ContinuityDeviceSnapshot {
        val record = requireRecord(device)
        return ContinuityDeviceSnapshot(device, record.state, record.acknowledged)
    }

    private fun requireRecord(device: ContinuityDeviceId): DeviceRecord =
        requireNotNull(devices[device]) { "Device is not enrolled" }

    private data class DeviceRecord(
        var state: ContinuityDeviceState,
        var acknowledged: ContinuityCursor,
    )
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
private val DEVICE_ID_PATTERN = Regex("[a-z][a-z0-9-]{2,63}")
private val BASE64_URL_PATTERN = Regex("[A-Za-z0-9_-]+")
