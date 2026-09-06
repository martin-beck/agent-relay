package com.example.agentrelay

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.agentrelay.connection.api.PairingAppLinkSignatureVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingAppLinkIntentTest {
    @Test
    fun nonViewIntentIsIgnoredBeforeParsing() {
        val result = parsePairingAppLinkIntent(
            Intent(Intent.ACTION_MAIN).setData(Uri.parse(validLink())),
            NOW,
            verifier,
        )

        assertEquals(PairingAppLinkIntentResult.NotAnAppLink, result)
    }

    @Test
    fun validViewIntentIsParsedAndSignatureVerified() {
        val result = parsePairingAppLinkIntent(
            Intent(Intent.ACTION_VIEW).setData(Uri.parse(validLink())),
            NOW,
            verifier,
        )

        val accepted = result as PairingAppLinkIntentResult.Accepted
        assertEquals("grant-12345678", accepted.link.grantReference)
        assertEquals(DAEMON_IDENTITY, accepted.link.daemonIdentity.value)
    }

    @Test
    fun wrongDomainIsRejectedWithoutAcceptingTheIntent() {
        val result = parsePairingAppLinkIntent(
            Intent(Intent.ACTION_VIEW).setData(Uri.parse(validLink(host = "evil.example"))),
            NOW,
            verifier,
        )

        assertEquals(
            PairingAppLinkIntentResult.Rejected(PairingAppLinkRejection.WRONG_DOMAIN),
            result,
        )
    }

    private fun validLink(host: String = "pair.agentrelay.dev"): String =
        "https://$host/v1/pair?a=agent-relay-android&d=$DAEMON_IDENTITY&e=${NOW + 60_000}" +
            "&g=grant-12345678&n=nonce-12345678&s=AAAA"

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val DAEMON_IDENTITY = "ari_v1_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val verifier = PairingAppLinkSignatureVerifier { payload, signature ->
            assertTrue(payload.isNotEmpty())
            signature == "AAAA"
        }
    }
}
