package com.example.agentrelay.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionNotificationPermissionTest {
    @Test
    fun hidesPromptWhenRuntimePermissionIsNotNeededOrAlreadyGranted() {
        assertEquals(
            SessionNotificationPermissionState.HIDDEN,
            resolve(runtimePermissionRequired = false),
        )
        assertEquals(
            SessionNotificationPermissionState.HIDDEN,
            resolve(permissionGranted = true),
        )
    }

    @Test
    fun distinguishesFirstRequestRationaleAndSettingsRecovery() {
        assertEquals(
            SessionNotificationPermissionState.REQUESTABLE,
            resolve(),
        )
        assertEquals(
            SessionNotificationPermissionState.RATIONALE,
            resolve(requestedBefore = true, shouldShowRationale = true),
        )
        assertEquals(
            SessionNotificationPermissionState.SETTINGS_REQUIRED,
            resolve(requestedBefore = true),
        )
    }

    private fun resolve(
        runtimePermissionRequired: Boolean = true,
        permissionGranted: Boolean = false,
        requestedBefore: Boolean = false,
        shouldShowRationale: Boolean = false,
    ): SessionNotificationPermissionState = resolveSessionNotificationPermissionState(
        runtimePermissionRequired = runtimePermissionRequired,
        permissionGranted = permissionGranted,
        requestedBefore = requestedBefore,
        shouldShowRationale = shouldShowRationale,
    )
}
