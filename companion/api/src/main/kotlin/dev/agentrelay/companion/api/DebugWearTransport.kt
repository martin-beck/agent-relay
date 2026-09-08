/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.companion.api

/** The only roles accepted by the unauthenticated emulator harness. */
enum class DebugWearRole { PHONE, WEAR }

/** A bounded, redacted packet injected by an ADB-controlled debug driver. */
data class DebugWearPacket(
    val schemaVersion: Int,
    val role: DebugWearRole,
    val messageId: String,
    val enrollmentGeneration: Long,
    val revision: Long,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val redactedBody: String,
) {
    init {
        require(schemaVersion == 1) { "Unsupported debug transport schema" }
        require(messageId.matches(MESSAGE_ID_PATTERN)) { "Debug message id is invalid" }
        require(enrollmentGeneration > 0) { "Debug generation must be positive" }
        require(revision > 0) { "Debug revision must be positive" }
        require(issuedAtEpochMillis >= 0) { "Debug issue time is invalid" }
        require(expiresAtEpochMillis > issuedAtEpochMillis) { "Debug expiry is invalid" }
        require(expiresAtEpochMillis - issuedAtEpochMillis <= MAX_LIFETIME_MILLIS) {
            "Debug packet lifetime is too long"
        }
        require(redactedBody.isNotBlank() && redactedBody.length <= MAX_BODY_CHARS) {
            "Debug body is invalid or too large"
        }
        require(!redactedBody.contains('\u0000')) { "Debug body contains a NUL" }
        require(!SECRET_PATTERN.containsMatchIn(redactedBody)) {
            "Debug body must be redacted"
        }
    }

    fun isFreshAt(nowEpochMillis: Long): Boolean =
        nowEpochMillis in issuedAtEpochMillis until expiresAtEpochMillis
}

/** Guard proving this adapter cannot represent authentication or pairing. */
data class DebugWearEvidence(
    val buildType: String,
    val pairingGrantCreated: Boolean = false,
    val authenticatedDataLayer: Boolean = false,
) {
    init {
        require(buildType == "debug") { "Debug transport requires a debug build" }
        require(!pairingGrantCreated) { "Debug transport cannot create pairing grants" }
        require(!authenticatedDataLayer) { "Debug evidence cannot claim Data Layer authentication" }
    }
}

enum class DebugWearReceiveOutcome { ACCEPTED, DUPLICATE, STALE, EXPIRED, WRONG_ROLE }

/** Deterministic receiver used by the ADB driver and JVM contract tests. */
class DebugWearReceiver(
    private val expectedRole: DebugWearRole,
    private val evidence: DebugWearEvidence,
) {
    private val receivedIds = linkedSetOf<String>()
    private var latestGeneration = 0L
    private var latestRevision = 0L

    init {
        evidence
    }

    fun receive(packet: DebugWearPacket, nowEpochMillis: Long): DebugWearReceiveOutcome {
        if (packet.role == expectedRole) return DebugWearReceiveOutcome.WRONG_ROLE
        if (!packet.isFreshAt(nowEpochMillis)) return DebugWearReceiveOutcome.EXPIRED
        if (packet.enrollmentGeneration < latestGeneration ||
            (packet.enrollmentGeneration == latestGeneration && packet.revision < latestRevision)
        ) {
            return DebugWearReceiveOutcome.STALE
        }
        if (!receivedIds.add(packet.messageId)) return DebugWearReceiveOutcome.DUPLICATE
        latestGeneration = packet.enrollmentGeneration
        latestRevision = packet.revision
        return DebugWearReceiveOutcome.ACCEPTED
    }
}

/** Stable line codec for `adb shell am start` extras; no credentials or node ids. */
object DebugWearPacketCodec {
    fun encode(packet: DebugWearPacket): String = listOf(
        packet.schemaVersion,
        packet.role.name,
        packet.messageId,
        packet.enrollmentGeneration,
        packet.revision,
        packet.issuedAtEpochMillis,
        packet.expiresAtEpochMillis,
        packet.redactedBody.replace("%", "%25").replace("|", "%7C"),
    ).joinToString("|")

    fun decode(encoded: String): DebugWearPacket? {
        val fields = encoded.split('|')
        if (fields.size != 8) return null
        return runCatching {
            DebugWearPacket(
                schemaVersion = fields[0].toInt(),
                role = DebugWearRole.valueOf(fields[1]),
                messageId = fields[2],
                enrollmentGeneration = fields[3].toLong(),
                revision = fields[4].toLong(),
                issuedAtEpochMillis = fields[5].toLong(),
                expiresAtEpochMillis = fields[6].toLong(),
                redactedBody = fields[7].replace("%7C", "|").replace("%25", "%"),
            )
        }.getOrNull()
    }
}

private const val MAX_BODY_CHARS = 512
private const val MAX_LIFETIME_MILLIS = 5 * 60 * 1000L
private val MESSAGE_ID_PATTERN = Regex("debug_wear_v1_[A-Za-z0-9_-]{8,64}")
private val SECRET_PATTERN = Regex("(?i)(token|password|private.?key|secret|credential)\\s*[:=]")
