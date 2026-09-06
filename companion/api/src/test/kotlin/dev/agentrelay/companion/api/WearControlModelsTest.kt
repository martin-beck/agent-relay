package dev.agentrelay.companion.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WearControlModelsTest {
    @Test
    fun safeActionExecutesOnlyOnceOnPhone() {
        val coordinator = coordinator()
        val executed = mutableListOf<WearControlAction>()
        val processor = WearControlProcessor(
            coordinator,
            WearControlAuthorizer { it.authorizationTag == "valid" },
            WearControlExecutor {
                executed += it.action
                WearControlExecution(it.action, it.cardId, it.cardRevision)
            },
        )
        val request = request(WearControlAction.ACKNOWLEDGE)

        assertEquals(
            WearControlOutcome.EXECUTED,
            processor.process(request, 150, phoneAvailable = true),
        )
        assertEquals(WearControlOutcome.REJECT_REPLAY, processor.process(request, 150, phoneAvailable = true))
        assertEquals(listOf(WearControlAction.ACKNOWLEDGE), executed)
    }

    @Test
    fun approvalRequiresPhoneConfirmationAndDoesNotExecute() {
        val coordinator = coordinator()
        var executed = false
        val processor = WearControlProcessor(
            coordinator,
            WearControlAuthorizer { true },
            WearControlExecutor {
                executed = true
                WearControlExecution(it.action, it.cardId, it.cardRevision)
            },
        )

        assertEquals(
            WearControlOutcome.PHONE_CONFIRMATION_REQUIRED,
            processor.process(request(WearControlAction.REQUEST_APPROVAL), 150, phoneAvailable = true),
        )
        assertFalse(executed)
        assertEquals(
            WearControlOutcome.REJECT_REPLAY,
            processor.process(request(WearControlAction.REQUEST_APPROVAL), 150, phoneAvailable = true),
        )
    }

    @Test
    fun expiryRevocationAuthorizationAndPhoneAvailabilityFailClosed() {
        val coordinator = coordinator()
        val processor = WearControlProcessor(
            coordinator,
            WearControlAuthorizer { false },
            WearControlExecutor {
                WearControlExecution(it.action, it.cardId, it.cardRevision)
            },
        )
        assertEquals(WearControlOutcome.REJECT_EXPIRED, processor.process(request(), 200, true))
        assertEquals(WearControlOutcome.REJECT_UNAUTHORIZED, processor.process(request(), 150, true))
        assertEquals(
            WearControlOutcome.PHONE_UNAVAILABLE,
            WearControlProcessor(
                coordinator,
                WearControlAuthorizer { true },
                WearControlExecutor { WearControlExecution(it.action, it.cardId, it.cardRevision) },
            ).process(request(), 150, false),
        )
        coordinator.revoke(device(), 150)
        assertEquals(WearControlOutcome.REJECT_REVOKED, processor.process(request(), 150, true))
    }

    @Test
    fun validAuthorizerCanBeUsedForDeferAndOpen() {
        val coordinator = coordinator()
        val processor = WearControlProcessor(
            coordinator,
            WearControlAuthorizer { it.authorizationTag == "valid" },
            WearControlExecutor { WearControlExecution(it.action, it.cardId, it.cardRevision) },
        )
        assertEquals(
            WearControlOutcome.EXECUTED,
            processor.process(request(WearControlAction.DEFER, "valid"), 150, true),
        )
        assertEquals(
            WearControlOutcome.EXECUTED,
            processor.process(
                request(WearControlAction.OPEN_ON_PHONE, "valid", id = "control_v1_open00001"),
                150,
                true,
            ),
        )
    }

    private fun coordinator(): CompanionPhoneCoordinator = CompanionPhoneCoordinator().also {
        it.enroll(
            CompanionEnrollment(
                identity(),
                CompanionEnrollmentState.ENROLLED,
                generation = 1,
                changedAtEpochMillis = 1,
            ),
        )
    }

    private fun device() = CompanionDeviceId("cd_v1_watch001")

    private fun identity() = CompanionDeviceIdentity(
        id = device(),
        type = CompanionDeviceType.WATCH,
        alias = "Watch",
        softwareVersion = "1.0",
        schemaVersion = 1,
        capabilities = setOf(CompanionCapability.SAFE_ACTIONS),
        reachability = CompanionReachability.ONLINE,
        batteryPercent = 90,
        lastSeenEpochMillis = 1,
    )

    private fun request(
        action: WearControlAction = WearControlAction.ACKNOWLEDGE,
        tag: String = "valid",
        id: String = "control_v1_req00001",
    ) = WearControlRequest(device(), 1, id, "card_v1_card0001", 3, action, 100, 200, tag)
}
