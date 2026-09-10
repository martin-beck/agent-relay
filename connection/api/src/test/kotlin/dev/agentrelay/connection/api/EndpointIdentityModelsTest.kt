/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.connection.api

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EndpointIdentityModelsTest {
    private val encodedKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
    private val key = EndpointPublicKey("Ed25519", encodedKey)
    private val identity = key.stableIdentity

    @Test
    fun identityIsOpaqueAndStableAcrossTransportMetadata() {
        assertEquals(identity, stableEndpointIdentity(EndpointPublicKey("Ed25519", encodedKey)))
        assertTrue(identity.value.matches(Regex("ari_v1_[A-Za-z0-9_-]{43}")))
        assertFalse(identity.value.contains("host"))
    }

    @Test
    fun recordRejectsIdentityNotDerivedFromPublicKey() {
        val otherKey = EndpointPublicKey(
            "Ed25519",
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 }),
        )
        assertFailsWith<IllegalArgumentException> {
            EndpointIdentityRecord(identity, EndpointIdentityRole.DAEMON, otherKey, createdAtMillis = 1)
        }
    }

    @Test
    fun plannedRotationRequiresMatchingContinuityProof() {
        val replacementKey = EndpointPublicKey(
            "Ed25519",
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 2 }),
        )
        val previous = EndpointIdentityRecord(identity, EndpointIdentityRole.DAEMON, key, createdAtMillis = 10)
        val replacement = EndpointIdentityRecord(
            replacementKey.stableIdentity,
            EndpointIdentityRole.DAEMON,
            replacementKey,
            keyVersion = 2,
            createdAtMillis = 20,
        )
        assertFailsWith<IllegalArgumentException> {
            EndpointIdentityRotation(previous, replacement, IdentityRotationReason.PLANNED, observedAtMillis = 20)
        }
        val proof = IdentityContinuityProof(
            previous.identity,
            replacement.identity,
            2,
            encodedKey,
            encodedKey,
        )
        val rotation = EndpointIdentityRotation(
            previous,
            replacement,
            IdentityRotationReason.PLANNED,
            proof,
            observedAtMillis = 20,
        )
        assertEquals(replacement.identity, rotation.replacement.identity)
    }

    @Test
    fun compromiseRecoveryIsExplicitAndCannotUseOldProof() {
        val replacementKey = EndpointPublicKey(
            "Ed25519",
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 3 }),
        )
        val previous = EndpointIdentityRecord(identity, EndpointIdentityRole.CLIENT, key, createdAtMillis = 10)
        val replacement = EndpointIdentityRecord(
            replacementKey.stableIdentity,
            EndpointIdentityRole.CLIENT,
            replacementKey,
            keyVersion = 2,
            createdAtMillis = 20,
        )
        EndpointIdentityRotation(
            previous,
            replacement,
            IdentityRotationReason.COMPROMISE_RECOVERY,
            observedAtMillis = 20,
        )
        assertFailsWith<IllegalArgumentException> {
            EndpointIdentityRotation(
                previous,
                replacement,
                IdentityRotationReason.COMPROMISE_RECOVERY,
                IdentityContinuityProof(previous.identity, replacement.identity, 2, encodedKey, encodedKey),
                observedAtMillis = 20,
            )
        }
    }

    @Test
    fun changedPinnedIdentityRequiresExplicitDecision() {
        val replacementKey = EndpointPublicKey(
            "Ed25519",
            Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 4 }),
        )
        val pinned = EndpointIdentityRecord(identity, EndpointIdentityRole.DAEMON, key, createdAtMillis = 10)
        val presented = EndpointIdentityRecord(
            replacementKey.stableIdentity,
            EndpointIdentityRole.DAEMON,
            replacementKey,
            keyVersion = 2,
            createdAtMillis = 20,
        )
        assertTrue(IdentityTrustObservation(presented, pinned).requiresExplicitDecision)
        assertFalse(IdentityTrustObservation(pinned, pinned).requiresExplicitDecision)
    }
}
