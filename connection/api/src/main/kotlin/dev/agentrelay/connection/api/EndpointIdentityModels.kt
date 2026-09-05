package dev.agentrelay.connection.api

import java.security.MessageDigest
import java.util.Base64

/** Stable, opaque identity for a daemon or client. It contains no endpoint metadata. */
@JvmInline
value class StableEndpointIdentity(val value: String) {
    init {
        require(value.matches(IDENTITY_PATTERN)) { "Endpoint identity must be an ari_v1 digest" }
    }

    override fun toString(): String = value
}

enum class EndpointIdentityRole { DAEMON, CLIENT }

enum class IdentityKeyState { ACTIVE, RETIRED, COMPROMISED }

/** Public key encoding; private key material is intentionally not part of the API. */
data class EndpointPublicKey(
    val algorithm: String,
    val encodedKey: String,
) {
    init {
        require(algorithm == ED25519_ALGORITHM) { "Only the approved identity algorithm is supported" }
        requireCanonicalBase64Url(encodedKey, "Public key")
        require(encodedKey.length == PUBLIC_KEY_LENGTH) { "Ed25519 public key must be 32 bytes" }
    }

    val stableIdentity: StableEndpointIdentity
        get() = stableEndpointIdentity(this)
}

/** Durable endpoint identity record. Transport addresses and implementation IDs do not belong here. */
data class EndpointIdentityRecord(
    val identity: StableEndpointIdentity,
    val role: EndpointIdentityRole,
    val key: EndpointPublicKey,
    val keyVersion: Int = 1,
    val state: IdentityKeyState = IdentityKeyState.ACTIVE,
    val createdAtMillis: Long,
) {
    init {
        require(identity == key.stableIdentity) { "Identity must be derived from its public key" }
        require(keyVersion in 1..MAX_KEY_VERSION) { "Identity key version is invalid" }
        require(createdAtMillis >= 0) { "Identity creation time is invalid" }
        require(state != IdentityKeyState.ACTIVE || keyVersion >= 1) { "Active identity key is invalid" }
    }
}

enum class IdentityRotationReason { PLANNED, KEY_EXPIRY, COMPROMISE_RECOVERY }

/** Signed continuity statement supplied by the identity owner during a planned rotation. */
data class IdentityContinuityProof(
    val previousIdentity: StableEndpointIdentity,
    val nextIdentity: StableEndpointIdentity,
    val nextKeyVersion: Int,
    val transcriptDigest: String,
    val signature: String,
) {
    init {
        require(nextKeyVersion in 1..MAX_KEY_VERSION) { "Continuity key version is invalid" }
        requireCanonicalBase64Url(transcriptDigest, "Transcript digest")
        require(transcriptDigest.length == DIGEST_LENGTH) { "Transcript digest must be 32 bytes" }
        requireCanonicalBase64Url(signature, "Continuity signature")
        require(signature.length <= MAX_SIGNATURE_LENGTH) { "Continuity signature is too large" }
        require(previousIdentity != nextIdentity) { "Continuity rotation must change identity" }
    }
}

/** Rotation is explicit so a changed key can never be silently accepted as the same endpoint. */
data class EndpointIdentityRotation(
    val previous: EndpointIdentityRecord,
    val replacement: EndpointIdentityRecord,
    val reason: IdentityRotationReason,
    val proof: IdentityContinuityProof? = null,
    val observedAtMillis: Long,
) {
    init {
        require(previous.role == replacement.role) { "Identity rotation cannot change endpoint role" }
        require(previous.identity != replacement.identity) { "Identity rotation must change identity" }
        require(replacement.keyVersion > previous.keyVersion) { "Identity key version must advance" }
        require(previous.state == IdentityKeyState.ACTIVE) { "Only an active identity can be rotated" }
        require(replacement.state == IdentityKeyState.ACTIVE) { "Replacement identity must be active" }
        require(observedAtMillis >= previous.createdAtMillis) { "Rotation time is invalid" }
        if (reason == IdentityRotationReason.COMPROMISE_RECOVERY) {
            require(proof == null) { "Compromise recovery cannot rely on a compromised key" }
        } else {
            require(proof != null) { "Planned rotation requires continuity proof" }
            require(proof.previousIdentity == previous.identity) { "Continuity proof predecessor differs" }
            require(proof.nextIdentity == replacement.identity) { "Continuity proof replacement differs" }
            require(proof.nextKeyVersion == replacement.keyVersion) { "Continuity proof version differs" }
        }
    }
}

enum class IdentityTrustDecision { ACCEPT_FIRST_USE, ACCEPT_ROTATION, REJECT }

/** Hostile or malformed identity changes must stop before transport authentication. */
data class IdentityTrustObservation(
    val presented: EndpointIdentityRecord,
    val pinned: EndpointIdentityRecord?,
    val rotation: EndpointIdentityRotation? = null,
) {
    init {
        require(pinned == null || pinned.role == presented.role) { "Pinned identity role differs" }
        require(rotation == null || rotation.replacement == presented) { "Rotation does not describe presented identity" }
    }

    val requiresExplicitDecision: Boolean
        get() = pinned == null || pinned.identity != presented.identity
}

/** Derive the public identity from key bytes, keeping addresses and labels out of identity. */
fun stableEndpointIdentity(publicKey: EndpointPublicKey): StableEndpointIdentity {
    val digest = MessageDigest.getInstance("SHA-256").digest(
        Base64.getUrlDecoder().decode(publicKey.encodedKey),
    )
    return StableEndpointIdentity("ari_v1_${Base64.getUrlEncoder().withoutPadding().encodeToString(digest)}")
}

private const val ED25519_ALGORITHM = "Ed25519"
private const val PUBLIC_KEY_LENGTH = 43
private const val DIGEST_LENGTH = 43
private const val MAX_SIGNATURE_LENGTH = 2048
private const val MAX_KEY_VERSION = 1_000_000
private val IDENTITY_PATTERN = Regex("ari_v1_[A-Za-z0-9_-]{43}")

private fun requireCanonicalBase64Url(value: String, field: String) {
    require(value.isNotEmpty() && !value.contains('=')) { "$field must be unpadded base64url" }
    require(value.matches(Regex("[A-Za-z0-9_-]+"))) { "$field must be base64url" }
    val decoded = runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()
    require(decoded != null) { "$field is not valid base64url" }
    require(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value) {
        "$field must use canonical base64url"
    }
}
