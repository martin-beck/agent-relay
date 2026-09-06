package dev.agentrelay.connection.api

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Security
import java.security.Signature
import java.util.Base64
import java.security.spec.X509EncodedKeySpec

/** A bounded, opaque handoff opened by a camera or QR reader. */
data class PairingAppLink(
    val grantReference: String,
    val daemonIdentity: StableEndpointIdentity,
    val nonce: String,
    val expiresAtMillis: Long,
    val signature: String,
) {
    init {
        require(grantReference.matches(PAIRING_OPAQUE_TOKEN)) { "Pairing grant reference is invalid" }
        require(nonce.matches(PAIRING_OPAQUE_TOKEN)) { "Pairing nonce is invalid" }
        require(expiresAtMillis > 0) { "Pairing link expiry is invalid" }
        requireCanonicalBase64Url(signature, "Pairing link signature", PAIRING_MAX_SIGNATURE_LENGTH)
    }

    /** Stable bytes signed by the daemon; no URI encoding or transport metadata is included. */
    fun signingPayload(): ByteArray =
        listOf(PAIRING_VERSION, grantReference, daemonIdentity.value, nonce, expiresAtMillis).joinToString("\n")
            .toByteArray(StandardCharsets.UTF_8)
}

fun interface PairingAppLinkSignatureVerifier {
    fun verify(payload: ByteArray, signature: String): Boolean
}

/** Parse and authenticate a camera handoff before any enrollment side effect. */
object PairingAppLinkCodec {
    const val HOST = "pair.agentrelay.dev"
    const val PATH = "/v1/pair"
    const val AUDIENCE = "agent-relay-android"
    private const val MAX_LINK_LENGTH = 2_048

    fun parse(
        raw: String,
        nowMillis: Long,
        expectedHost: String = HOST,
    ): PairingAppLink {
        require(raw.length in 1..MAX_LINK_LENGTH) { "Pairing link is too large" }
        val uri = runCatching { URI(raw) }.getOrElse { error("Pairing link is malformed") }
        require(uri.scheme == "https" && uri.host == expectedHost && uri.port == -1) {
            "Pairing link domain is not verified"
        }
        require(uri.path == PATH && uri.fragment == null && uri.userInfo == null) {
            "Pairing link path is invalid"
        }
        val params = parseQuery(uri.rawQuery ?: "")
        require(params.keys == REQUIRED_KEYS) { "Pairing link fields are invalid" }
        require(params["a"] == AUDIENCE) { "Pairing link audience is invalid" }
        val expiresAt = params["e"]!!.toLongOrNull() ?: error("Pairing link expiry is invalid")
        require(expiresAt > nowMillis && expiresAt - nowMillis <= MAX_LIFETIME_MILLIS) {
            "Pairing link has expired"
        }
        return PairingAppLink(
            grantReference = params["g"]!!,
            daemonIdentity = StableEndpointIdentity(params["d"]!!),
            nonce = params["n"]!!,
            expiresAtMillis = expiresAt,
            signature = params["s"]!!,
        )
    }

    fun parseAndVerify(
        raw: String,
        nowMillis: Long,
        verifier: PairingAppLinkSignatureVerifier,
        expectedHost: String = HOST,
    ): PairingAppLink {
        val link = parse(raw, nowMillis, expectedHost)
        require(verifier.verify(link.signingPayload(), link.signature)) { "Pairing link signature was rejected" }
        return link
    }

    /** Adapter for an Ed25519 public key represented by the JCA X.509 encoding. */
    fun ed25519Verifier(encodedPublicKey: ByteArray): PairingAppLinkSignatureVerifier {
        require(encodedPublicKey.size <= 64) { "Pairing public key is too large" }
        return PairingAppLinkSignatureVerifier { payload, encodedSignature ->
            val signature = runCatching { Base64.getUrlDecoder().decode(encodedSignature) }.getOrNull()
                ?: return@PairingAppLinkSignatureVerifier false
            val keySpec = X509EncodedKeySpec(encodedPublicKey)
            Security.getProviders().asSequence()
                .filterNot { it.name == "AndroidKeyStore" }
                .any { provider ->
                    runCatching {
                        val key = KeyFactory.getInstance("Ed25519", provider).generatePublic(keySpec)
                        Signature.getInstance("Ed25519", provider).run {
                            initVerify(key)
                            update(payload)
                            verify(signature)
                        }
                    }.getOrDefault(false)
                }
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        require(query.isNotEmpty()) { "Pairing link query is missing" }
        val values = linkedMapOf<String, String>()
        query.split('&').forEach { pair ->
            val parts = pair.split('=', limit = 2)
            require(parts.size == 2 && parts[0].isNotEmpty()) { "Pairing link query is malformed" }
            val key = parts[0]
            require(key in REQUIRED_KEYS) { "Pairing link query contains an unknown field" }
            require(key !in values) { "Pairing link has duplicate fields" }
            require(URLDecoder.decode(parts[1], StandardCharsets.UTF_8) == parts[1]) {
                "Pairing link query must use canonical encoding"
            }
            values[key] = parts[1]
        }
        require(values.size == REQUIRED_KEYS.size) { "Pairing link fields are incomplete" }
        return values
    }

    private val REQUIRED_KEYS = setOf("a", "d", "e", "g", "n", "s")
    private const val MAX_LIFETIME_MILLIS = 15 * 60 * 1_000L
}

private const val PAIRING_VERSION = "ari_pair_v1"
private const val PAIRING_MAX_SIGNATURE_LENGTH = 512
private val PAIRING_OPAQUE_TOKEN = Regex("[A-Za-z0-9_-]{8,256}")

private fun requireCanonicalBase64Url(value: String, field: String, maxLength: Int) {
    require(value.isNotEmpty() && value.length <= maxLength && !value.contains('=')) {
        "$field is invalid"
    }
    require(value.matches(Regex("[A-Za-z0-9_-]+"))) { "$field is invalid" }
    require(Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getUrlDecoder().decode(value)) == value) {
        "$field is not canonical"
    }
}
