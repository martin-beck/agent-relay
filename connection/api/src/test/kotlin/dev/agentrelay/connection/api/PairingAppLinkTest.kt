package dev.agentrelay.connection.api

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PairingAppLinkTest {
    private val identity = StableEndpointIdentity("ari_v1_" + "A".repeat(43))
    private val link = "https://${PairingAppLinkCodec.HOST}${PairingAppLinkCodec.PATH}" +
        "?a=${PairingAppLinkCodec.AUDIENCE}&d=${identity.value}&e=1000&g=grant_12345&n=nonce_12345&s=QQ"

    @Test
    fun validLinkIsBoundedAndVerifiableBeforeEnrollment() {
        val parsed = PairingAppLinkCodec.parse(link, nowMillis = 500)
        assertTrue(parsed.signingPayload().decodeToString().contains("grant_12345"))
        assertTrue(
            PairingAppLinkCodec.parseAndVerify(
                link,
                500,
                verifier = PairingAppLinkSignatureVerifier { _, signature -> signature == "QQ" },
            )
                .daemonIdentity == identity,
        )
    }

    @Test
    fun wrongDomainExpiryReplayAndTamperingFailClosed() {
        assertFailsWith<IllegalArgumentException> {
            PairingAppLinkCodec.parse(link.replace(PairingAppLinkCodec.HOST, "evil.example"), 500)
        }
        assertFailsWith<IllegalArgumentException> { PairingAppLinkCodec.parse(link, 1000) }
        assertFailsWith<IllegalArgumentException> {
            PairingAppLinkCodec.parse(link.replace("g=grant_12345", "g=grant_12345&g=other_123"), 500)
        }
        assertFailsWith<IllegalArgumentException> {
            PairingAppLinkCodec.parseAndVerify(
                link,
                500,
                verifier = PairingAppLinkSignatureVerifier { _, _ -> false },
            )
        }
    }

    @Test
    fun unknownFieldsAndNonHttpsLinksCannotReachEnrollment() {
        assertFailsWith<IllegalArgumentException> {
            PairingAppLinkCodec.parse(link.replace("?a=", "?x=bad&a="), 500)
        }
        assertFailsWith<IllegalArgumentException> {
            PairingAppLinkCodec.parse(link.replace("https://", "http://"), 500)
        }
    }

    @Test
    fun encodedEd25519FixtureVerifiesThroughAvailableSoftwareProvider() {
        val expiry = 1_788_712_980_000L
        val identity = StableEndpointIdentity("ari_v1_" + "A".repeat(43))
        val raw = "https://${PairingAppLinkCodec.HOST}${PairingAppLinkCodec.PATH}" +
            "?a=${PairingAppLinkCodec.AUDIENCE}&d=${identity.value}&e=$expiry" +
            "&g=grant-qr-12345678&n=nonce-qr-12345678" +
            "&s=kux20imrkcLhIeiZYNXDhk9QJaz5V53XD97rARdbDozbdqlGQSc-avdic_wzM1YjA6WGZWBL670ORtuHMwRWCw"
        assertTrue(
            PairingAppLinkCodec.parseAndVerify(
                raw,
                nowMillis = 1_788_712_920_000L,
                verifier = PairingAppLinkCodec.ed25519Verifier(
                    java.util.Base64.getUrlDecoder().decode(
                        "MCowBQYDK2VwAyEAl0kjCTi6QUNeG1vAE2huS4nGw3tZjEiv3RvyMBKun-8",
                    ),
                ),
            ).daemonIdentity == identity,
        )
    }
}
