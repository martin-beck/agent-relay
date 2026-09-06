package dev.agentrelay.companion.api

data class CompanionRecoveryPolicy(
    val maxClockSkewMillis: Long = 5 * 60 * 1_000,
    val maxReplayEntries: Int = 128,
) {
    init {
        require(maxClockSkewMillis in 1..86_400_000) { "Clock-skew budget is invalid" }
        require(maxReplayEntries in 1..10_000) { "Replay budget is invalid" }
    }
}

enum class CompanionRecoveryRejection {
    UNKNOWN_DEVICE,
    REVOKED_DEVICE,
    GENERATION_MISMATCH,
    CLOCK_SKEW,
    EXPIRED_MESSAGE,
    DUPLICATE_MESSAGE,
    STALE_REVISION,
}

sealed interface CompanionRecoveryDecision {
    data object Accepted : CompanionRecoveryDecision
    data class Rejected(val reason: CompanionRecoveryRejection) : CompanionRecoveryDecision
}

data class CompanionRecoverySnapshot(
    val deviceId: CompanionDeviceId,
    val enrollmentGeneration: Long,
    val lastAcceptedRevision: Long,
    val revoked: Boolean,
    val replayEntries: Int,
)

/**
 * Phone-owned replay and recovery guard. It retains only opaque message ids and
 * revisions, and revocation atomically drops both the replay ledger and the
 * caller-visible snapshot of pending authority.
 */
class CompanionSecurityRecoveryLedger(
    private val policy: CompanionRecoveryPolicy = CompanionRecoveryPolicy(),
) {
    private data class Record(
        var generation: Long,
        var lastRevision: Long = 0,
        var revoked: Boolean = false,
        val messageIds: LinkedHashSet<String> = linkedSetOf(),
    )

    private val records = linkedMapOf<CompanionDeviceId, Record>()

    fun enroll(deviceId: CompanionDeviceId, generation: Long) {
        require(generation > 0) { "Enrollment generation must be positive" }
        val current = records[deviceId]
        require(current == null || generation > current.generation) {
            "Enrollment generation must advance"
        }
        records[deviceId] = Record(generation)
    }

    fun revoke(deviceId: CompanionDeviceId): Boolean = records[deviceId]?.let {
        it.revoked = true
        it.generation += 1
        it.lastRevision = 0
        it.messageIds.clear()
        true
    } ?: false

    fun accept(message: CompanionMessageEnvelope, nowEpochMillis: Long): CompanionRecoveryDecision {
        val record = records[message.deviceId]
            ?: return CompanionRecoveryDecision.Rejected(CompanionRecoveryRejection.UNKNOWN_DEVICE)
        if (record.revoked) return CompanionRecoveryDecision.Rejected(CompanionRecoveryRejection.REVOKED_DEVICE)
        if (message.enrollmentGeneration != record.generation) {
            return CompanionRecoveryDecision.Rejected(CompanionRecoveryRejection.GENERATION_MISMATCH)
        }
        if (nowEpochMillis < message.issuedAtEpochMillis - policy.maxClockSkewMillis ||
            nowEpochMillis > message.expiresAtEpochMillis + policy.maxClockSkewMillis
        ) {
            return CompanionRecoveryDecision.Rejected(CompanionRecoveryRejection.CLOCK_SKEW)
        }
        if (!message.isReplayableAt(nowEpochMillis)) {
            return CompanionRecoveryDecision.Rejected(CompanionRecoveryRejection.EXPIRED_MESSAGE)
        }
        if (!record.messageIds.add(message.messageId)) {
            return CompanionRecoveryDecision.Rejected(CompanionRecoveryRejection.DUPLICATE_MESSAGE)
        }
        if (message.revision <= record.lastRevision) {
            record.messageIds.remove(message.messageId)
            return CompanionRecoveryDecision.Rejected(CompanionRecoveryRejection.STALE_REVISION)
        }
        record.lastRevision = message.revision
        while (record.messageIds.size > policy.maxReplayEntries) {
            record.messageIds.remove(record.messageIds.first())
        }
        return CompanionRecoveryDecision.Accepted
    }

    fun snapshot(deviceId: CompanionDeviceId): CompanionRecoverySnapshot? = records[deviceId]?.let {
        CompanionRecoverySnapshot(deviceId, it.generation, it.lastRevision, it.revoked, it.messageIds.size)
    }
}
