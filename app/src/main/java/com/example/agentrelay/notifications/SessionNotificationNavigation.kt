package com.example.agentrelay.notifications

import android.content.Intent

internal const val SESSION_NAVIGATION_KEY_EXTRA =
    "com.example.agentrelay.extra.SESSION_NAVIGATION_KEY"
internal const val SESSION_NOTIFICATION_OPEN_ACTION_SUFFIX = ".action.OPEN_SESSION"
internal const val SESSION_NOTIFICATION_URI_SCHEME = "agent-relay"
internal const val SESSION_NOTIFICATION_URI_AUTHORITY = "session"

internal fun Intent.sessionNotificationNavigationKey(packageName: String): String? {
    if (action != packageName + SESSION_NOTIFICATION_OPEN_ACTION_SUFFIX) {
        return null
    }
    if (extras?.keySet() != setOf(SESSION_NAVIGATION_KEY_EXTRA)) {
        return null
    }
    val navigationKey = getStringExtra(SESSION_NAVIGATION_KEY_EXTRA)
        ?.takeIf(String::isSha256Digest)
        ?: return null
    val navigationUri = data ?: return null
    if (
        navigationUri.scheme != SESSION_NOTIFICATION_URI_SCHEME ||
        navigationUri.authority != SESSION_NOTIFICATION_URI_AUTHORITY ||
        navigationUri.pathSegments.singleOrNull() != navigationKey
    ) {
        return null
    }
    return navigationKey
}

private fun String.isSha256Digest(): Boolean =
    length == 64 && all { character -> character in '0'..'9' || character in 'a'..'f' }
