package dev.agentrelay.companion.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanionPhoneCoordinatorTest {
    private val device = CompanionDeviceIdentity(
        CompanionDeviceId("cd_v1_watch-01"), CompanionDeviceType.WATCH, "Watch", "1.0.0", 1,
        setOf(CompanionCapability.NOTIFICATIONS), CompanionReachability.ONLINE, 80, 100,
    )

    @Test
    fun queueIsBoundedAndDuplicateRevisionIsSuppressed() {
        val coordinator = CompanionPhoneCoordinator(maxQueuedProjections = 1)
        coordinator.enroll(CompanionEnrollment(device, CompanionEnrollmentState.ENROLLED, 1, 100))
        assertTrue(coordinator.enqueue(projection(1)))
        assertFalse(coordinator.enqueue(projection(1)))
        coordinator.enqueue(projection(2))
        assertEquals(listOf(2L), coordinator.pending(device.id, 100).map { it.projectionRevision })
    }

    @Test
    fun reconciliationRejectsDuplicateStaleAndRevokedMessages() {
        val coordinator = CompanionPhoneCoordinator()
        coordinator.enroll(CompanionEnrollment(device, CompanionEnrollmentState.ENROLLED, 1, 100))
        val message = CompanionMessageEnvelope(device.id, 1, 1, "message-1", 100, 200, "opaque", "tag")
        assertEquals(CompanionReconciliationOutcome.APPLY, coordinator.reconcile(message, 100))
        assertEquals(CompanionReconciliationOutcome.IGNORE_DUPLICATE, coordinator.reconcile(message, 100))
        assertEquals(CompanionReconciliationOutcome.IGNORE_STALE, coordinator.reconcile(message.copy(messageId = "message-2"), 200))
        coordinator.revoke(device.id, 201)
        assertEquals(CompanionReconciliationOutcome.REJECT_REVOKED, coordinator.reconcile(message.copy(messageId = "message-3"), 101))
    }

    private fun projection(revision: Long) = CompanionProjection(
        device.id, revision, "projection-$revision", 100, 200,
        CompanionPrivacyClass.PUBLIC_SUMMARY, CompanionActionClass.NOTIFICATION, "ready", "tag",
    )
}
