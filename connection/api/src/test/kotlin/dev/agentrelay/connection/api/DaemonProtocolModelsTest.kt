/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.connection.api

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DaemonProtocolModelsTest {
    private val daemon = identity(1, EndpointIdentityRole.DAEMON)
    private val client = identity(2, EndpointIdentityRole.CLIENT)
    private val version1 = DaemonProtocolVersion(1, 0)
    private val version2 = DaemonProtocolVersion(1, 1)

    @Test
    fun negotiationUsesHighestCommonVersionAndCapabilityIntersection() {
        val digest = protocolTranscriptDigest(version2, setOf(DaemonProtocolCapability.HEARTBEAT), 4096)
        val local = offer(
            setOf(version1, version2),
            setOf(DaemonProtocolCapability.HEARTBEAT, DaemonProtocolCapability.PAIRING_GRANTS),
            digest,
        )
        val remote = offer(
            setOf(version2),
            setOf(DaemonProtocolCapability.HEARTBEAT, DaemonProtocolCapability.PROTECTED_MESSAGES),
            digest,
            2048,
        )
        val selected = negotiateDaemonProtocol(local, remote)
        assertEquals(version2, selected?.version)
        assertEquals(setOf(DaemonProtocolCapability.HEARTBEAT), selected?.capabilities)
        assertEquals(2048, selected?.maxFrameBytes)
    }

    @Test
    fun negotiationRejectsTranscriptMismatchAndDowngradeWithoutCommonVersion() {
        val digest = protocolTranscriptDigest(version1, emptySet(), 4096)
        val other = protocolTranscriptDigest(version2, emptySet(), 4096)
        assertFailsWith<IllegalArgumentException> {
            negotiateDaemonProtocol(offer(setOf(version1), emptySet(), digest), offer(setOf(version1), emptySet(), other))
        }
        assertNull(negotiateDaemonProtocol(offer(setOf(version2), emptySet(), digest), offer(setOf(version1), emptySet(), digest)))
    }

    @Test
    fun authenticatedFrameCarriesOnlyBoundedOpaquePayload() {
        val ciphertext = encoded(32)
        val digest = protocolTranscriptDigest(version1, setOf(DaemonProtocolCapability.PROTECTED_MESSAGES), 4096)
        AuthenticatedProtocolFrame(
            ProtocolFrameHeader(version1, DaemonProtocolMessageType.REQUEST, "message-1", 32),
            daemon.identity,
            client.identity,
            setOf(DaemonProtocolCapability.PROTECTED_MESSAGES),
            ciphertext,
            digest,
            encoded(64),
        )
    }

    @Test
    fun malformedFramesAndUnboundedPayloadsFailBeforeTransport() {
        val digest = protocolTranscriptDigest(version1, emptySet(), 4096)
        assertFailsWith<IllegalArgumentException> {
            ProtocolFrameHeader(version1, DaemonProtocolMessageType.EVENT, "!", 1)
        }
        assertFailsWith<IllegalArgumentException> {
            AuthenticatedProtocolFrame(
                ProtocolFrameHeader(version1, DaemonProtocolMessageType.EVENT, "message-2", 31),
                daemon.identity,
                client.identity,
                emptySet(),
                encoded(32),
                digest,
                encoded(64),
            )
        }
    }

    private fun offer(
        versions: Set<DaemonProtocolVersion>,
        capabilities: Set<DaemonProtocolCapability>,
        digest: String,
        maxFrameBytes: Int = 4096,
    ) = ProtocolNegotiationOffer(versions, capabilities, maxFrameBytes, digest)

    private fun identity(seed: Int, role: EndpointIdentityRole): EndpointIdentityRecord {
        val key = EndpointPublicKey("Ed25519", encoded(32, seed.toByte()))
        return EndpointIdentityRecord(key.stableIdentity, role, key, createdAtMillis = 1)
    }

    private fun encoded(size: Int, value: Byte = 3): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(size) { value })
}
