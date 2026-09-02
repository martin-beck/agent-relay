package com.example.agentrelay.ui.main

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext

/**
 * Requests microphone access only in response to an explicit voice-input action.
 *
 * The pending session is retained across Activity recreation and consumed exactly once so a
 * delayed permission result cannot start recognition for a replacement session.
 */
@Composable
internal fun rememberSpeechStartRequest(
    onPermissionGranted: (String) -> Unit,
    selectedSessionKey: String?,
    onPermissionDenied: () -> Unit,
): (String) -> Unit {
    val context = LocalContext.current
    val currentOnPermissionGranted by rememberUpdatedState(onPermissionGranted)
    val currentSelectedSessionKey by rememberUpdatedState(selectedSessionKey)
    val currentOnPermissionDenied by rememberUpdatedState(onPermissionDenied)
    var pendingSessionKey by rememberSaveable { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val sessionKey = pendingSessionKey
        pendingSessionKey = null
        sessionKey
            ?.takeIf { isCurrentSpeechRequest(it, currentSelectedSessionKey) }
            ?.let { currentSessionKey ->
                if (granted) {
                    currentOnPermissionGranted(currentSessionKey)
                } else {
                    currentOnPermissionDenied()
                }
            }
    }

    return remember(context, permissionLauncher) {
        { sessionKey ->
            if (
                sessionKey.isNotBlank() &&
                pendingSessionKey == null &&
                isCurrentSpeechRequest(sessionKey, currentSelectedSessionKey)
            ) {
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    currentOnPermissionGranted(sessionKey)
                } else {
                    pendingSessionKey = sessionKey
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }
}

internal fun isCurrentSpeechRequest(requestedSessionKey: String?, selectedSessionKey: String?) =
    !requestedSessionKey.isNullOrBlank() &&
        requestedSessionKey == selectedSessionKey
