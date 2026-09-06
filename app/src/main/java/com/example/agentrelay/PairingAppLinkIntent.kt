package com.example.agentrelay

import android.content.Intent
import dev.agentrelay.connection.api.PairingAppLink
import dev.agentrelay.connection.api.PairingAppLinkCodec
import dev.agentrelay.connection.api.PairingAppLinkSignatureVerifier

internal sealed interface PairingAppLinkIntentResult {
    data class Accepted(val link: PairingAppLink) : PairingAppLinkIntentResult
    data class Rejected(val reason: PairingAppLinkRejection) : PairingAppLinkIntentResult
    data object NotAnAppLink : PairingAppLinkIntentResult
}

internal enum class PairingAppLinkRejection {
    MALFORMED,
    EXPIRED,
    UNTRUSTED_SIGNATURE,
    WRONG_DOMAIN,
    REPLAYED,
}

/** Activity boundary: parse and verify before UI or enrollment side effects. */
internal fun parsePairingAppLinkIntent(
    intent: Intent,
    nowMillis: Long,
    verifier: PairingAppLinkSignatureVerifier,
): PairingAppLinkIntentResult {
    if (intent.action != Intent.ACTION_VIEW || intent.data == null) {
        return PairingAppLinkIntentResult.NotAnAppLink
    }
    return runCatching {
        PairingAppLinkIntentResult.Accepted(
            PairingAppLinkCodec.parseAndVerify(intent.data.toString(), nowMillis, verifier),
        )
    }.getOrElse { failure ->
        val reason = when {
            failure.message?.contains("expired") == true -> PairingAppLinkRejection.EXPIRED
            failure.message?.contains("signature") == true -> PairingAppLinkRejection.UNTRUSTED_SIGNATURE
            failure.message?.contains("domain") == true -> PairingAppLinkRejection.WRONG_DOMAIN
            else -> PairingAppLinkRejection.MALFORMED
        }
        PairingAppLinkIntentResult.Rejected(reason)
    }
}
