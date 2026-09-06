package dev.agentrelay.companion.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanionSecurityRecoveryModelsTest {
    private val device = CompanionDeviceId("cd_v1_watch001")

    @Test
    fun `accepts ordered message once and rejects replay or downgrade`() {
        val ledger = ledger()
        ledger.enroll(device, 2)
        assertEquals(CompanionRecoveryDecision.Accepted, ledger.accept(message(1, "message-01"), 1_500))
        assertEquals(
            CompanionRecoveryRejection.DUPLICATE_MESSAGE,
            rejected(ledger, message(1, "message-01"), 1_500),
        )
        assertEquals(
            CompanionRecoveryRejection.STALE_REVISION,
            rejected(ledger, message(1, "message-02"), 1_500),
        )
        assertEquals(1, ledger.snapshot(device)?.lastAcceptedRevision)
    }

    @Test
    fun `clock skew expiry and generation mismatch fail closed`() {
        val ledger = ledger()
        ledger.enroll(device, 2)
        assertEquals(
            CompanionRecoveryRejection.CLOCK_SKEW,
            rejected(ledger, message(1, "message-03"), 10_000),
        )
        assertEquals(
            CompanionRecoveryRejection.EXPIRED_MESSAGE,
            rejected(ledger, message(1, "message-04", expiresAt = 1_100), 1_200),
        )
        assertEquals(
            CompanionRecoveryRejection.GENERATION_MISMATCH,
            rejected(ledger, message(1, "message-05", generation = 3), 1_500),
        )
    }

    @Test
    fun `revocation clears replay authority and requires a newer enrollment`() {
        val ledger = ledger()
        ledger.enroll(device, 2)
        assertEquals(CompanionRecoveryDecision.Accepted, ledger.accept(message(1, "message-06"), 1_500))
        assertTrue(ledger.revoke(device))
        assertEquals(true, ledger.snapshot(device)?.revoked)
        assertEquals(
            CompanionRecoveryRejection.REVOKED_DEVICE,
            rejected(ledger, message(2, "message-07"), 1_500),
        )
        ledger.enroll(device, 4)
        assertFalse(ledger.snapshot(device)?.revoked ?: true)
        assertEquals(CompanionRecoveryDecision.Accepted, ledger.accept(message(1, "message-08", generation = 4), 1_500))
    }

    private fun ledger() = CompanionSecurityRecoveryLedger(CompanionRecoveryPolicy(100, 2))

    private fun message(
        revision: Long,
        id: String,
        generation: Long = 2,
        expiresAt: Long = 2_000,
    ) = CompanionMessageEnvelope(device, generation, revision, id, 1_000, expiresAt, "opaque", "signature")

    private fun rejected(
        ledger: CompanionSecurityRecoveryLedger,
        message: CompanionMessageEnvelope,
        now: Long,
    ) = (ledger.accept(message, now) as CompanionRecoveryDecision.Rejected).reason
}
