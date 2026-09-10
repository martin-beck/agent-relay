/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.connection.api

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class PairingGrantModelsTest {
    private val daemonKey = key(1)
    private val clientKey = key(2)
    private val daemon = EndpointIdentityRecord(
        daemonKey.stableIdentity,
        EndpointIdentityRole.DAEMON,
        daemonKey,
        createdAtMillis = 1,
    )
    private val client = EndpointIdentityRecord(
        clientKey.stableIdentity,
        EndpointIdentityRole.CLIENT,
        clientKey,
        createdAtMillis = 1,
    )

    private fun store() = InMemoryPairingGrantStore(
        PairingProofVerifier { signer, digest, proof ->
            proof.signer == signer && proof.transcriptDigest == digest
        },
    )

    @Test
    fun ceremonyRequiresExplicitAuthenticatedApprovalAndNarrowScopes() {
        val store = store()
        val ceremony = store.beginCeremony(
            PairingCeremonyId("ceremony-1"),
            daemon,
            client,
            setOf(PairingScope.READ_SESSIONS, PairingScope.SUBMIT_TASKS),
            10_000,
            100,
        )
        val proof = PairingProof(client.identity, ceremony.transcriptDigest, encoded(3))
        val grant = store.acceptCeremony(
            ceremony.id,
            PairingGrantId("grant-1"),
            setOf(PairingScope.READ_SESSIONS),
            5_000,
            proof,
            101,
        )
        assertEquals(setOf(PairingScope.READ_SESSIONS), grant.scopes)
        assertTrue(store.authorize(grant.id, PairingScope.READ_SESSIONS, 101))
        assertFalse(store.authorize(grant.id, PairingScope.SUBMIT_TASKS, 101))
    }

    @Test
    fun replayDuplicateAndWrongSignerFailClosed() {
        val store = store()
        val ceremony = store.beginCeremony(
            PairingCeremonyId("ceremony-2"),
            daemon,
            client,
            setOf(PairingScope.READ_DIAGNOSTICS),
            1_000,
            100,
        )
        val proof = PairingProof(client.identity, ceremony.transcriptDigest, encoded(4))
        store.acceptCeremony(ceremony.id, PairingGrantId("grant-2"), ceremony.requestedScopes, 500, proof, 101)
        assertFailsWith<IllegalStateException> {
            store.acceptCeremony(ceremony.id, PairingGrantId("grant-3"), ceremony.requestedScopes, 500, proof, 102)
        }
        val wrongSignerCeremony = store.beginCeremony(
            PairingCeremonyId("ceremony-3"),
            daemon,
            client,
            ceremony.requestedScopes,
            1_000,
            101,
        )
        assertFailsWith<IllegalArgumentException> {
            store.acceptCeremony(
                wrongSignerCeremony.id,
                PairingGrantId("grant-3"),
                ceremony.requestedScopes,
                500,
                PairingProof(daemon.identity, wrongSignerCeremony.transcriptDigest, encoded(4)),
                102,
            )
        }
        assertFalse(store.authorize(PairingGrantId("unknown"), PairingScope.READ_DIAGNOSTICS, 101))
    }

    @Test
    fun expiryRenewalAndRevocationAreFailClosed() {
        val store = store()
        val ceremony = store.beginCeremony(
            PairingCeremonyId("ceremony-4"),
            daemon,
            client,
            setOf(PairingScope.CONTROL_WORKFLOWS),
            2_000,
            100,
        )
        val proof = PairingProof(client.identity, ceremony.transcriptDigest, encoded(5))
        val grant = store.acceptCeremony(
            ceremony.id,
            PairingGrantId("grant-4"),
            ceremony.requestedScopes,
            500,
            proof,
            101,
        )
        assertFalse(store.authorize(grant.id, PairingScope.CONTROL_WORKFLOWS, 601))
        assertNull(store.renew(grant.id, 500, proof, 601))
        assertTrue(store.revoke(grant.id, 200))
        assertFalse(store.revoke(grant.id, 201))
        assertFalse(store.authorize(grant.id, PairingScope.CONTROL_WORKFLOWS, 201))
    }

    @Test
    fun renewalAdvancesRevisionAndCannotExpandLifetimeOrScope() {
        val store = store()
        val ceremony = store.beginCeremony(
            PairingCeremonyId("ceremony-5"),
            daemon,
            client,
            setOf(PairingScope.READ_DIAGNOSTICS),
            1_000,
            100,
        )
        val proof = PairingProof(client.identity, ceremony.transcriptDigest, encoded(6))
        val grant = store.acceptCeremony(
            ceremony.id,
            PairingGrantId("grant-5"),
            ceremony.requestedScopes,
            500,
            proof,
            101,
        )
        val renewalProof = PairingProof(
            client.identity,
            pairingRenewalTranscriptDigest(grant, 400, 200),
            encoded(7),
        )
        val renewed = store.renew(grant.id, 400, renewalProof, 200)
        assertEquals(2, renewed?.revision)
        assertEquals(600, renewed?.expiresAtMillis)
        assertFalse(store.authorize(grant.id, PairingScope.READ_DIAGNOSTICS, 601))
    }

    @Test
    fun expiredCeremonyCannotBeAcceptedAndRejectIsExplicit() {
        val store = store()
        val ceremony = store.beginCeremony(
            PairingCeremonyId("ceremony-6"),
            daemon,
            client,
            setOf(PairingScope.READ_DIAGNOSTICS),
            500,
            100,
        )
        val proof = PairingProof(client.identity, ceremony.transcriptDigest, encoded(8))
        assertFailsWith<IllegalStateException> {
            store.acceptCeremony(ceremony.id, PairingGrantId("grant-6"), ceremony.requestedScopes, 500, proof, 301_000)
        }
        val rejected = store.beginCeremony(
            PairingCeremonyId("ceremony-7"),
            daemon,
            client,
            setOf(PairingScope.READ_DIAGNOSTICS),
            500,
            100,
        )
        assertTrue(store.rejectCeremony(rejected.id, 101))
        assertEquals(PairingCeremonyState.REJECTED, store.ceremony(rejected.id)?.state)
    }

    private fun key(seed: Int) = EndpointPublicKey("Ed25519", encoded(seed))

    private fun encoded(seed: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { seed.toByte() })
}
