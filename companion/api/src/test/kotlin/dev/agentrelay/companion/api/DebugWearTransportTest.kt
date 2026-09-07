package dev.agentrelay.companion.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class DebugWearTransportTest {
    @Test
    fun roundTripIsBoundedAndRedacted() {
        val packet = packet()
        assertEquals(packet, DebugWearPacketCodec.decode(DebugWearPacketCodec.encode(packet)))
    }

    @Test
    fun receiverRejectsReplayStaleExpiryAndWrongRole() {
        val receiver = DebugWearReceiver(DebugWearRole.WEAR, DebugWearEvidence("debug"))
        val packet = packet()
        assertEquals(DebugWearReceiveOutcome.ACCEPTED, receiver.receive(packet, 1_500))
        assertEquals(DebugWearReceiveOutcome.DUPLICATE, receiver.receive(packet, 1_500))
        assertEquals(DebugWearReceiveOutcome.ACCEPTED, receiver.receive(packet(2), 1_500))
        assertEquals(
            DebugWearReceiveOutcome.STALE,
            receiver.receive(packet(1).copy(messageId = "debug_wear_v1_stale0001"), 1_500),
        )
        assertEquals(
            DebugWearReceiveOutcome.WRONG_ROLE,
            DebugWearReceiver(DebugWearRole.PHONE, DebugWearEvidence("debug"))
                .receive(packet, 1_500),
        )
        assertEquals(
            DebugWearReceiveOutcome.EXPIRED,
            receiver.receive(packet(3, issued = 1_000, expires = 1_400), 1_500),
        )
    }

    @Test
    fun malformedOversizedSecretAndEncodedPacketsFailClosed() {
        assertFailsWith<IllegalArgumentException> { packet(body = "secret: leaked") }
        assertFailsWith<IllegalArgumentException> { packet(body = "x".repeat(513)) }
        assertNull(DebugWearPacketCodec.decode("1|WEAR|bad"))
        assertFailsWith<IllegalArgumentException> { DebugWearEvidence("release") }
        assertFailsWith<IllegalArgumentException> { DebugWearEvidence("debug", true) }
    }

    private fun packet(
        revision: Long = 1,
        generation: Long = 1,
        issued: Long = 1_000,
        expires: Long = 2_000,
        body: String = "Review the pending response",
    ) = DebugWearPacket(
        schemaVersion = 1,
        role = DebugWearRole.PHONE,
        messageId = "debug_wear_v1_msg${revision.toString().padStart(8, '0')}",
        enrollmentGeneration = generation,
        revision = revision,
        issuedAtEpochMillis = issued,
        expiresAtEpochMillis = expires,
        redactedBody = body,
    )
}
