/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.connection.api

import java.security.MessageDigest
import java.util.Base64

/** A narrowly scoped capability that may be granted to a paired client. */
enum class PairingScope {
    READ_DIAGNOSTICS,
    READ_SESSIONS,
    SUBMIT_TASKS,
    CONTROL_WORKFLOWS,
}

enum class PairingCeremonyState { PENDING, ACCEPTED, REJECTED, EXPIRED }

enum class PairingGrantState { ACTIVE, REVOKED }

@JvmInline
value class PairingCeremonyId(val value: String) {
    init {
        require(value.matches(ID_PATTERN)) { "Pairing ceremony id is invalid" }
    }
}

@JvmInline
value class PairingGrantId(val value: String) {
    init {
        require(value.matches(ID_PATTERN)) { "Pairing grant id is invalid" }
    }
}

/** Proof that the client authenticated the exact ceremony transcript. */
data class PairingProof(
    val signer: StableEndpointIdentity,
    val transcriptDigest: String,
    val signature: String,
) {
    init {
        requireCanonicalBase64Url(transcriptDigest, "Pairing transcript digest")
        require(transcriptDigest.length == DIGEST_LENGTH) {
            "Pairing transcript digest must be 32 bytes"
        }
        requireCanonicalBase64Url(signature, "Pairing signature")
        require(signature.length <= MAX_SIGNATURE_LENGTH) { "Pairing signature is too large" }
    }
}

data class PairingCeremony(
    val id: PairingCeremonyId,
    val daemonIdentity: StableEndpointIdentity,
    val clientIdentity: StableEndpointIdentity,
    val requestedScopes: Set<PairingScope>,
    val requestedLifetimeMillis: Long,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val state: PairingCeremonyState = PairingCeremonyState.PENDING,
) {
    init {
        require(daemonIdentity != clientIdentity) { "Daemon and client identities must differ" }
        require(requestedScopes.isNotEmpty()) { "Pairing must request at least one scope" }
        require(requestedLifetimeMillis in 1..MAX_GRANT_LIFETIME_MILLIS)
        require(createdAtMillis >= 0)
        require(expiresAtMillis > createdAtMillis)
        require(expiresAtMillis - createdAtMillis <= MAX_CEREMONY_LIFETIME_MILLIS)
    }

    val transcriptDigest: String
        get() = digest(
            listOf(
                id.value,
                daemonIdentity.value,
                clientIdentity.value,
                requestedScopes.map(PairingScope::name).sorted().joinToString(","),
                requestedLifetimeMillis.toString(),
                createdAtMillis.toString(),
                expiresAtMillis.toString(),
            ).joinToString("|"),
        )

    fun activeAt(nowMillis: Long): Boolean = state == PairingCeremonyState.PENDING &&
        nowMillis in createdAtMillis until expiresAtMillis
}

data class PairingGrant(
    val id: PairingGrantId,
    val daemonIdentity: StableEndpointIdentity,
    val clientIdentity: StableEndpointIdentity,
    val scopes: Set<PairingScope>,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val maxLifetimeMillis: Long,
    val revision: Int = 1,
    val state: PairingGrantState = PairingGrantState.ACTIVE,
    val revokedAtMillis: Long? = null,
) {
    init {
        require(daemonIdentity != clientIdentity)
        require(scopes.isNotEmpty())
        require(issuedAtMillis >= 0)
        require(expiresAtMillis > issuedAtMillis)
        require(expiresAtMillis - issuedAtMillis <= MAX_GRANT_LIFETIME_MILLIS)
        require(maxLifetimeMillis in 1..MAX_GRANT_LIFETIME_MILLIS)
        require(revision >= 1)
        require(state == PairingGrantState.REVOKED == (revokedAtMillis != null))
        require(revokedAtMillis == null || revokedAtMillis >= issuedAtMillis)
    }

    fun permits(scope: PairingScope, nowMillis: Long): Boolean =
        state == PairingGrantState.ACTIVE && nowMillis in issuedAtMillis until expiresAtMillis &&
            scope in scopes
}

/** The crypto boundary is injected so the in-memory contract never silently trusts a request. */
fun interface PairingProofVerifier {
    fun verify(
        expectedSigner: StableEndpointIdentity,
        expectedTranscriptDigest: String,
        proof: PairingProof,
    ): Boolean
}

/** Bounded pairing state machine suitable for deterministic tests and a durable adapter. */
class InMemoryPairingGrantStore(
    private val proofVerifier: PairingProofVerifier,
    private val maxCeremonyLifetimeMillis: Long = MAX_CEREMONY_LIFETIME_MILLIS,
    private val maxGrantLifetimeMillis: Long = MAX_GRANT_LIFETIME_MILLIS,
) {
    private val ceremonies = linkedMapOf<PairingCeremonyId, PairingCeremony>()
    private val grants = linkedMapOf<PairingGrantId, PairingGrant>()

    init {
        require(maxCeremonyLifetimeMillis in 1..MAX_CEREMONY_LIFETIME_MILLIS)
        require(maxGrantLifetimeMillis in 1..MAX_GRANT_LIFETIME_MILLIS)
    }

    @Synchronized
    fun beginCeremony(
        id: PairingCeremonyId,
        daemon: EndpointIdentityRecord,
        client: EndpointIdentityRecord,
        requestedScopes: Set<PairingScope>,
        requestedLifetimeMillis: Long,
        nowMillis: Long,
    ): PairingCeremony {
        require(daemon.role == EndpointIdentityRole.DAEMON) { "Pairing target must be a daemon" }
        require(client.role == EndpointIdentityRole.CLIENT) { "Pairing requester must be a client" }
        require(nowMillis >= 0)
        require(id !in ceremonies) { "Pairing ceremony already exists" }
        val ceremony = PairingCeremony(
            id,
            daemon.identity,
            client.identity,
            requestedScopes,
            requestedLifetimeMillis,
            nowMillis,
            nowMillis + maxCeremonyLifetimeMillis,
        )
        ceremonies[id] = ceremony
        return ceremony
    }

    @Synchronized
    fun acceptCeremony(
        ceremonyId: PairingCeremonyId,
        grantId: PairingGrantId,
        approvedScopes: Set<PairingScope>,
        lifetimeMillis: Long,
        proof: PairingProof,
        nowMillis: Long,
    ): PairingGrant {
        val ceremony = activeCeremony(ceremonyId, nowMillis)
        require(grantId !in grants) { "Pairing grant already exists" }
        require(proof.signer == ceremony.clientIdentity) { "Pairing proof signer differs" }
        require(proof.transcriptDigest == ceremony.transcriptDigest) { "Pairing transcript differs" }
        require(proofVerifier.verify(ceremony.clientIdentity, ceremony.transcriptDigest, proof)) {
            "Pairing proof was rejected"
        }
        require(approvedScopes.isNotEmpty() && approvedScopes.all { it in ceremony.requestedScopes }) {
            "Pairing approval must narrow the requested scopes"
        }
        require(lifetimeMillis in 1..ceremony.requestedLifetimeMillis)
        val grant = PairingGrant(
            grantId,
            ceremony.daemonIdentity,
            ceremony.clientIdentity,
            approvedScopes.toSet(),
            nowMillis,
            nowMillis + lifetimeMillis,
            lifetimeMillis,
        )
        grants[grantId] = grant
        ceremonies[ceremonyId] = ceremony.copy(state = PairingCeremonyState.ACCEPTED)
        return grant
    }

    @Synchronized
    fun rejectCeremony(ceremonyId: PairingCeremonyId, nowMillis: Long): Boolean {
        val ceremony = ceremonies[ceremonyId] ?: return false
        if (!ceremony.activeAt(nowMillis)) return false
        ceremonies[ceremonyId] = ceremony.copy(state = PairingCeremonyState.REJECTED)
        return true
    }

    @Synchronized
    fun renew(
        grantId: PairingGrantId,
        lifetimeMillis: Long,
        proof: PairingProof,
        nowMillis: Long,
    ): PairingGrant? {
        val grant = grants[grantId] ?: return null
        if (!grant.permits(grant.scopes.first(), nowMillis)) return null
        require(lifetimeMillis in 1..grant.maxLifetimeMillis)
        require(proof.signer == grant.clientIdentity)
        require(
            proofVerifier.verify(
                grant.clientIdentity,
                pairingRenewalTranscriptDigest(grant, lifetimeMillis, nowMillis),
                proof,
            ),
        ) { "Pairing renewal proof was rejected" }
        val renewed = grant.copy(
            expiresAtMillis = nowMillis + lifetimeMillis,
            maxLifetimeMillis = lifetimeMillis,
            revision = grant.revision + 1,
        )
        grants[grantId] = renewed
        return renewed
    }

    @Synchronized
    fun revoke(grantId: PairingGrantId, nowMillis: Long): Boolean {
        val grant = grants[grantId] ?: return false
        if (grant.state == PairingGrantState.REVOKED) return false
        require(nowMillis >= grant.issuedAtMillis)
        grants[grantId] = grant.copy(state = PairingGrantState.REVOKED, revokedAtMillis = nowMillis)
        return true
    }

    fun authorize(grantId: PairingGrantId, scope: PairingScope, nowMillis: Long): Boolean =
        grants[grantId]?.permits(scope, nowMillis) == true

    fun grant(grantId: PairingGrantId): PairingGrant? = grants[grantId]

    fun ceremony(ceremonyId: PairingCeremonyId): PairingCeremony? = ceremonies[ceremonyId]

    private fun activeCeremony(id: PairingCeremonyId, nowMillis: Long): PairingCeremony {
        val ceremony = ceremonies[id] ?: error("Pairing ceremony is unknown")
        if (!ceremony.activeAt(nowMillis)) {
            if (ceremony.state == PairingCeremonyState.PENDING) {
                ceremonies[id] = ceremony.copy(state = PairingCeremonyState.EXPIRED)
            }
            error("Pairing ceremony is not active")
        }
        return ceremony
    }
}

fun pairingRenewalTranscriptDigest(
    grant: PairingGrant,
    lifetimeMillis: Long,
    nowMillis: Long,
): String = digest(
    listOf(
        "renewal",
        grant.id.value,
        grant.daemonIdentity.value,
        grant.clientIdentity.value,
        grant.scopes.map(PairingScope::name).sorted().joinToString(","),
        lifetimeMillis.toString(),
        nowMillis.toString(),
        grant.revision.toString(),
    ).joinToString("|"),
)

private fun digest(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()),
)

private fun requireCanonicalBase64Url(value: String, field: String) {
    require(value.isNotEmpty() && !value.contains('=')) { "$field must be unpadded base64url" }
    require(value.matches(BASE64_URL_PATTERN)) { "$field must be base64url" }
    val decoded = runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()
    require(decoded != null) { "$field is not valid base64url" }
    require(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value) {
        "$field must use canonical base64url"
    }
}

private const val MAX_CEREMONY_LIFETIME_MILLIS = 5 * 60 * 1_000L
private const val MAX_GRANT_LIFETIME_MILLIS = 30 * 24 * 60 * 60 * 1_000L
private const val DIGEST_LENGTH = 43
private const val MAX_SIGNATURE_LENGTH = 2_048
private val ID_PATTERN = Regex("[a-z][a-z0-9-]{2,63}")
private val BASE64_URL_PATTERN = Regex("[A-Za-z0-9_-]+")
