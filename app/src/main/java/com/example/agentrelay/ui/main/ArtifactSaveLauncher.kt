package com.example.agentrelay.ui.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.example.agentrelay.data.AndroidSafArtifactExportDestination

@Composable
internal fun rememberArtifactSaveRequest(
    viewModel: MainScreenViewModel,
): (String, String, String) -> Unit {
    val contentResolver = LocalContext.current.contentResolver
    var pendingSessionKey by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingArtifactKey by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val sessionKey = pendingSessionKey
        val artifactKey = pendingArtifactKey
        pendingSessionKey = null
        pendingArtifactKey = null
        if (uri != null && sessionKey != null && artifactKey != null) {
            viewModel.artifactInteractions.exportArtifact(
                sessionKey = sessionKey,
                artifactKey = artifactKey,
                destination = AndroidSafArtifactExportDestination(contentResolver, uri),
            )
        }
    }
    return remember(viewModel, launcher) {
        { sessionKey, artifactKey, suggestedFileName ->
            pendingSessionKey = sessionKey
            pendingArtifactKey = artifactKey
            launcher.launch(suggestedFileName)
        }
    }
}
