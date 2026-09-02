package com.example.agentrelay.notifications

internal enum class SessionNotificationPermissionState {
    HIDDEN,
    REQUESTABLE,
    RATIONALE,
    SETTINGS_REQUIRED,
}

internal fun resolveSessionNotificationPermissionState(
    runtimePermissionRequired: Boolean,
    permissionGranted: Boolean,
    requestedBefore: Boolean,
    shouldShowRationale: Boolean,
): SessionNotificationPermissionState = when {
    !runtimePermissionRequired || permissionGranted ->
        SessionNotificationPermissionState.HIDDEN
    shouldShowRationale ->
        SessionNotificationPermissionState.RATIONALE
    requestedBefore ->
        SessionNotificationPermissionState.SETTINGS_REQUIRED
    else ->
        SessionNotificationPermissionState.REQUESTABLE
}
