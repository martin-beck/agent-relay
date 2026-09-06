package dev.agentrelay.companion.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WearProjectionTransportTest {
    @Test
    fun dispatchAcknowledgesAcceptedAndRetainsOfflineMessages() {
        val device = CompanionDeviceId("cd_v1_watch123")
        val coordinator = CompanionPhoneCoordinator()
        coordinator.enroll(enrollment(device))
        coordinator.enqueue(projection(device, 1, "message-one"))
        coordinator.enqueue(projection(device, 2, "message-two"))
        var online = false
        val transport = WearMessageTransport {
            CompanionDeliveryOutcome.UNKNOWN.takeIf { !online }
                ?: CompanionDeliveryOutcome.ACCEPTED
        }
        val dispatcher = WearProjectionDispatcher(coordinator, transport)

        val offline = dispatcher.dispatch(device, 1_500)
        assertTrue(offline.deliveredRevisions.isEmpty())
        assertEquals(listOf(1L, 2L), coordinator.pending(device, 1_500).map { it.projectionRevision })

        online = true
        val connected = dispatcher.dispatch(device, 1_500)
        assertEquals(listOf(1L, 2L), connected.deliveredRevisions)
        assertTrue(coordinator.pending(device, 1_500).isEmpty())
    }

    @Test
    fun duplicateDeliveryIsTerminalAndDoesNotExposeProtectedPayload() {
        val device = CompanionDeviceId("cd_v1_watch123")
        val coordinator = CompanionPhoneCoordinator()
        coordinator.enroll(enrollment(device))
        coordinator.enqueue(projection(device, 1, "redacted summary"))
        var received: CompanionMessageEnvelope? = null
        val dispatcher = WearProjectionDispatcher(
            coordinator,
            WearMessageTransport {
                received = it
                CompanionDeliveryOutcome.DUPLICATE
            },
        )

        val result = dispatcher.dispatch(device, 1_500)
        assertEquals(listOf(1L), result.deliveredRevisions)
        assertEquals("redacted summary", received?.body)
        assertTrue(received?.body?.contains("token", ignoreCase = true) == false)
    }

    private fun enrollment(device: CompanionDeviceId) = CompanionEnrollment(
        device = CompanionDeviceIdentity(
            id = device,
            type = CompanionDeviceType.WATCH,
            alias = "Watch",
            softwareVersion = "1.0",
            schemaVersion = 1,
            capabilities = setOf(CompanionCapability.NOTIFICATIONS),
            reachability = CompanionReachability.ONLINE,
            batteryPercent = 80,
            lastSeenEpochMillis = 1_000,
        ),
        state = CompanionEnrollmentState.ENROLLED,
        generation = 1,
        changedAtEpochMillis = 1_000,
    )

    private fun projection(device: CompanionDeviceId, revision: Long, payload: String) =
        CompanionProjection(
            deviceId = device,
            projectionRevision = revision,
            idempotencyKey = "message-$revision",
            issuedAtEpochMillis = 1_000,
            expiresAtEpochMillis = 5_000,
            privacyClass = CompanionPrivacyClass.PUBLIC_SUMMARY,
            actionClass = CompanionActionClass.NOTIFICATION,
            payload = payload,
            authenticationTag = "tag-$revision",
        )
}
