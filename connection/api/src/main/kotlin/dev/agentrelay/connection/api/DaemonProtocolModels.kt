/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.connection.api

import java.security.MessageDigest
import java.util.Base64

/** Protocol versions are negotiated independently of the transport implementation. */
data class DaemonProtocolVersion(val major: Int, val minor: Int) : Comparable<DaemonProtocolVersion> {
    init {
        require(major in 1..MAX_VERSION_COMPONENT) { "Protocol major version is invalid" }
        require(minor in 0..MAX_VERSION_COMPONENT) { "Protocol minor version is invalid" }
    }

    override fun compareTo(other: DaemonProtocolVersion): Int =
        compareValuesBy(this, other, DaemonProtocolVersion::major, DaemonProtocolVersion::minor)
}

enum class DaemonProtocolCapability {
    PAIRING_GRANTS,
    PROTECTED_MESSAGES,
    MULTIPLEXED_REQUESTS,
    HEARTBEAT,
}

enum class DaemonProtocolMessageType { REQUEST, RESPONSE, EVENT, HEARTBEAT }

enum class DaemonTransportKind { DIRECT, PRIVATE_NETWORK, NAT_TRAVERSAL, OPAQUE_RELAY }

data class ProtocolNegotiationOffer(
    val versions: Set<DaemonProtocolVersion>,
    val capabilities: Set<DaemonProtocolCapability>,
    val maxFrameBytes: Int,
    val transcriptDigest: String,
) {
    init {
        require(versions.isNotEmpty()) { "Protocol offer must include a version" }
        require(maxFrameBytes in MIN_FRAME_BYTES..MAX_FRAME_BYTES)
        requireCanonicalProtocolDigest(transcriptDigest)
    }
}

data class ProtocolNegotiation(
    val version: DaemonProtocolVersion,
    val capabilities: Set<DaemonProtocolCapability>,
    val maxFrameBytes: Int,
    val transcriptDigest: String,
) {
    init {
        require(maxFrameBytes in MIN_FRAME_BYTES..MAX_FRAME_BYTES)
        requireCanonicalProtocolDigest(transcriptDigest)
    }
}

/** Selects the highest common version and only capabilities both peers offered. */
fun negotiateDaemonProtocol(
    local: ProtocolNegotiationOffer,
    remote: ProtocolNegotiationOffer,
): ProtocolNegotiation? {
    require(local.transcriptDigest == remote.transcriptDigest) { "Negotiation transcript differs" }
    val version = local.versions.intersect(remote.versions).maxOrNull() ?: return null
    return ProtocolNegotiation(
        version,
        local.capabilities.intersect(remote.capabilities),
        minOf(local.maxFrameBytes, remote.maxFrameBytes),
        local.transcriptDigest,
    )
}

data class ProtocolFrameHeader(
    val version: DaemonProtocolVersion,
    val type: DaemonProtocolMessageType,
    val messageId: String,
    val payloadBytes: Int,
) {
    init {
        require(messageId.matches(MESSAGE_ID_PATTERN)) { "Protocol message id is invalid" }
        require(payloadBytes in 1..MAX_FRAME_BYTES) { "Protocol payload is out of bounds" }
    }
}

/** Authenticated frame with ciphertext only; transport addresses and cleartext task data are excluded. */
data class AuthenticatedProtocolFrame(
    val header: ProtocolFrameHeader,
    val sender: StableEndpointIdentity,
    val recipient: StableEndpointIdentity,
    val capabilities: Set<DaemonProtocolCapability>,
    val protectedPayload: String,
    val transcriptDigest: String,
    val signature: String,
) {
    init {
        require(sender != recipient) { "Protocol sender and recipient must differ" }
        requireCanonicalProtocolDigest(transcriptDigest)
        requireCanonicalBase64Url(protectedPayload, "Protected payload")
        requireCanonicalBase64Url(signature, "Protocol signature")
        require(protectedPayload.length <= MAX_ENCODED_PAYLOAD) { "Protected payload is too large" }
        require(signature.length <= MAX_SIGNATURE_LENGTH) { "Protocol signature is too large" }
        require(header.payloadBytes == decodedLength(protectedPayload)) {
            "Protocol payload length does not match ciphertext"
        }
    }
}

/** Adapter seam: direct, private-network, NAT and opaque relays all carry the same frame. */
interface DaemonProtocolTransport {
    val kind: DaemonTransportKind
    fun send(frame: AuthenticatedProtocolFrame)
}

fun protocolTranscriptDigest(
    version: DaemonProtocolVersion,
    capabilities: Set<DaemonProtocolCapability>,
    maxFrameBytes: Int,
): String = digest(
    listOf(
        version.major.toString(),
        version.minor.toString(),
        capabilities.map(DaemonProtocolCapability::name).sorted().joinToString(","),
        maxFrameBytes.toString(),
    ).joinToString("|"),
)

private fun decodedLength(value: String): Int =
    runCatching { Base64.getUrlDecoder().decode(value).size }.getOrElse {
        error("Protected payload is not valid base64url")
    }

private fun digest(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()),
)

private fun requireCanonicalProtocolDigest(value: String) {
    require(value.length == DIGEST_LENGTH) { "Protocol transcript digest must be 32 bytes" }
    requireCanonicalBase64Url(value, "Protocol transcript digest")
}

private fun requireCanonicalBase64Url(value: String, field: String) {
    require(value.isNotEmpty() && !value.contains('=')) { "$field must be unpadded base64url" }
    require(value.matches(BASE64_URL_PATTERN)) { "$field must be base64url" }
    val decoded = runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()
    require(decoded != null) { "$field is not valid base64url" }
    require(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value) {
        "$field must use canonical base64url"
    }
}

private const val MAX_VERSION_COMPONENT = 255
private const val MIN_FRAME_BYTES = 1
private const val MAX_FRAME_BYTES = 1_048_576
private const val MAX_ENCODED_PAYLOAD = 1_398_102
private const val MAX_SIGNATURE_LENGTH = 2_048
private const val DIGEST_LENGTH = 43
private val MESSAGE_ID_PATTERN = Regex("[a-z][a-z0-9-]{2,63}")
private val BASE64_URL_PATTERN = Regex("[A-Za-z0-9_-]+")
