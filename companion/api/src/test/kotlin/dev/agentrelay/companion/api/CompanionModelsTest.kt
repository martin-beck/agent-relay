package dev.agentrelay.companion.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CompanionModelsTest {
    private val device = CompanionDeviceIdentity(
        id = CompanionDeviceId("cd_v1_watch-01"),
        type = CompanionDeviceType.WATCH,
        alias = "Desk watch",
        softwareVersion = "1.0.0",
        schemaVersion = 1,
        capabilities = setOf(CompanionCapability.NOTIFICATIONS, CompanionCapability.SPEECH_INPUT),
        reachability = CompanionReachability.ONLINE,
        batteryPercent = 82,
        lastSeenEpochMillis = 100,
    )

    @Test
    fun projectionIsFreshOnlyWithinItsBoundedLifetime() {
        val projection = projection()
        assertFalse(projection.isFreshAt(99))
        assertTrue(projection.isFreshAt(100))
        assertFalse(projection.isFreshAt(200))
    }

    @Test
    fun highRiskActionsCannotBeProjectedToACompanion() {
        assertFailsWith<IllegalArgumentException> {
            projection(actionClass = CompanionActionClass.HIGH_RISK_APPROVAL)
        }
    }

    @Test
    fun hostileIdentityAndProjectionValuesAreRejected() {
        assertFailsWith<IllegalArgumentException> { CompanionDeviceId("../watch") }
        assertFailsWith<IllegalArgumentException> { device.copy(batteryPercent = 101) }
        assertFailsWith<IllegalArgumentException> { projection(payload = "secret-token") }
        assertFailsWith<IllegalArgumentException> { projection(expiresAt = 100) }
    }

    private fun projection(
        actionClass: CompanionActionClass = CompanionActionClass.NOTIFICATION,
        payload: String = "build complete",
        expiresAt: Long = 200,
    ) = CompanionProjection(
        deviceId = device.id,
        projectionRevision = 1,
        idempotencyKey = "projection-1",
        issuedAtEpochMillis = 100,
        expiresAtEpochMillis = expiresAt,
        privacyClass = CompanionPrivacyClass.PUBLIC_SUMMARY,
        actionClass = actionClass,
        payload = payload,
        authenticationTag = "tag-1",
    )
}
