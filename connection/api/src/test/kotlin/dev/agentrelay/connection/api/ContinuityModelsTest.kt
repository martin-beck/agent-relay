/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.connection.api

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContinuityModelsTest {
    private val token = ContinuityToken(
        sessionId = "session-1",
        daemonIdentity = identity(1),
        grantId = PairingGrantId("grant-1"),
        tokenDigest = encoded(32),
        issuedAtMillis = 100,
        expiresAtMillis = 10_000,
        generation = 1,
    )

    @Test
    fun disconnectAndFailoverPreserveTokenGenerationAndGrantAuthority() {
        val controller = ContinuityController(token)
        assertEquals(DaemonTransportKind.DIRECT, controller.connect(token, DaemonTransportKind.DIRECT, 101).transport)
        assertEquals(ContinuityState.FAILING_OVER, controller.disconnect(200).state)
        val resumed = controller.reconnect(token, DaemonTransportKind.OPAQUE_RELAY, 201)
        assertEquals(ContinuityState.CONNECTED, resumed.state)
        assertEquals(1, resumed.generation)
        assertEquals(DaemonTransportKind.OPAQUE_RELAY, resumed.transport)
        assertFailsWith<IllegalArgumentException> {
            controller.reconnect(token.copy(generation = 2), DaemonTransportKind.DIRECT, 202)
        }
    }

    @Test
    fun expiredOrMismatchedContinuityCannotReconnect() {
        val controller = ContinuityController(token)
        controller.connect(token, DaemonTransportKind.PRIVATE_NETWORK, 101)
        controller.disconnect(200)
        assertFailsWith<IllegalArgumentException> {
            controller.reconnect(token, DaemonTransportKind.NAT_TRAVERSAL, 10_000)
        }
    }

    @Test
    fun commandLedgerResistsDuplicatesAndDoesNotReplayUnknownDelivery() {
        val ledger = CommandLedger()
        val id = CommandId("command-1")
        assertEquals(CommandDisposition.NEW, ledger.begin(id).disposition)
        assertEquals(CommandDisposition.DUPLICATE_IN_FLIGHT, ledger.begin(id).disposition)
        assertTrue(ledger.markUnknown(id))
        assertEquals(CommandDisposition.UNKNOWN_DELIVERY, ledger.begin(id).disposition)
        assertFalse(ledger.complete(id))
    }

    @Test
    fun completedCommandIsIdempotentlyObservedWithoutExecution() {
        val ledger = CommandLedger()
        val id = CommandId("command-2")
        assertTrue(ledger.complete(id).not())
        assertEquals(CommandDisposition.NEW, ledger.begin(id).disposition)
        assertTrue(ledger.complete(id))
        assertEquals(CommandDisposition.DUPLICATE_COMPLETED, ledger.begin(id).disposition)
    }

    private fun identity(seed: Int): StableEndpointIdentity {
        val key = EndpointPublicKey("Ed25519", encoded(32, seed.toByte()))
        return key.stableIdentity
    }

    private fun encoded(size: Int, value: Byte = 3): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(size) { value })
}
