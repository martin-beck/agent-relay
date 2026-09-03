package com.example.agentrelay.ui.main

import com.example.agentrelay.R
import com.example.agentrelay.data.ArtifactExportDestination
import com.example.agentrelay.data.SessionHubRuntime
import dev.agentrelay.provider.api.RemoteFileAccessException
import dev.agentrelay.session.api.SessionLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class ArtifactInteractionState(
    val transfers: Map<String, ArtifactTransferUiState> = emptyMap(),
    val refreshingSessionKeys: Set<String> = emptySet(),
)

internal class ArtifactInteractionController(
    private val scope: CoroutineScope,
    private val runtime: () -> SessionHubRuntime?,
    private val reportError: (UiMessage?) -> Unit,
) {
    private val mutableState = MutableStateFlow(ArtifactInteractionState())
    private val exportJobs = mutableMapOf<String, Job>()

    val state: StateFlow<ArtifactInteractionState> = mutableState.asStateFlow()

    fun refreshArtifacts(sessionKey: String) {
        val active = runtime() ?: return
        val locator = active.findArtifactSessionLocator(sessionKey)
        if (locator == null) {
            reportError(UiMessage.Localized(R.string.main_error_session_unavailable))
            return
        }
        if (sessionKey in state.value.refreshingSessionKeys) {
            return
        }
        reportError(null)
        mutableState.update { current ->
            current.copy(refreshingSessionKeys = current.refreshingSessionKeys + sessionKey)
        }
        scope.launch {
            try {
                active.refreshArtifacts(locator)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                reportError(UiMessage.Localized(R.string.artifact_error_refresh))
            } finally {
                mutableState.update { current ->
                    current.copy(refreshingSessionKeys = current.refreshingSessionKeys - sessionKey)
                }
            }
        }
    }

    fun exportArtifact(
        sessionKey: String,
        artifactKey: String,
        destination: ArtifactExportDestination,
    ) {
        val active = runtime()
        if (active == null) {
            rejectDestination(destination, UiMessage.Localized(R.string.artifact_error_unavailable))
            return
        }
        val locator = active.findArtifactSessionLocator(sessionKey)
        if (locator == null) {
            rejectDestination(destination, UiMessage.Localized(R.string.artifact_error_unavailable))
            return
        }
        val artifact = active.sessionSnapshot.value.artifacts.firstOrNull {
            it.locator == locator && it.stableUiKey == artifactKey
        }
        if (artifact == null) {
            rejectDestination(destination, UiMessage.Localized(R.string.artifact_error_unavailable))
            return
        }
        if (exportJobs[artifactKey]?.isActive == true) {
            rejectDestination(destination, UiMessage.Localized(R.string.artifact_error_save_in_progress))
            return
        }
        reportError(null)
        updateTransfer(artifactKey, ArtifactTransferUiState())
        val exportJob = scope.launch {
            var completed = false
            try {
                val prepared = active.prepareArtifactDownload(locator, artifact.id)
                check(
                    prepared.artifact.id == artifact.id &&
                        prepared.artifact.locator == locator,
                ) {
                    "Prepared artifact identity changed"
                }
                val totalBytes = prepared.sourceSnapshot.revision.sizeBytes
                updateTransfer(
                    artifactKey,
                    ArtifactTransferUiState(totalBytes = totalBytes),
                )
                val written = destination.write(prepared.chunks) { bytesWritten ->
                    updateTransfer(
                        artifactKey,
                        ArtifactTransferUiState(
                            bytesWritten = bytesWritten,
                            totalBytes = totalBytes,
                        ),
                    )
                }
                check(written == totalBytes) { "Artifact export size changed" }
                updateTransfer(
                    artifactKey,
                    ArtifactTransferUiState(
                        bytesWritten = written,
                        totalBytes = totalBytes,
                        isRunning = false,
                        isComplete = true,
                    ),
                )
                completed = true
            } catch (cancelled: CancellationException) {
                discardPartial(destination)
                throw cancelled
            } catch (failure: RemoteFileAccessException) {
                discardPartial(destination)
                reportError(UiMessage.Verbatim(failure.actionableMessage))
            } catch (_: Throwable) {
                discardPartial(destination)
                reportError(UiMessage.Localized(R.string.artifact_error_save))
            } finally {
                if (!completed) {
                    updateTransfer(artifactKey, null)
                }
            }
        }
        exportJobs[artifactKey] = exportJob
        exportJob.invokeOnCompletion {
            if (exportJobs[artifactKey] === exportJob) {
                exportJobs.remove(artifactKey)
            }
        }
    }

    fun cancelArtifactExport(artifactKey: String) {
        exportJobs[artifactKey]?.cancel()
    }

    private fun rejectDestination(
        destination: ArtifactExportDestination,
        message: UiMessage,
    ) {
        reportError(message)
        scope.launch { discardPartial(destination) }
    }

    private fun updateTransfer(
        artifactKey: String,
        transfer: ArtifactTransferUiState?,
    ) {
        mutableState.update { current ->
            current.copy(
                transfers = if (transfer == null) {
                    current.transfers - artifactKey
                } else {
                    current.transfers + (artifactKey to transfer)
                },
            )
        }
    }

    private suspend fun discardPartial(destination: ArtifactExportDestination) {
        withContext(NonCancellable) {
            runCatching { destination.discardPartial() }
        }
    }
}

private fun SessionHubRuntime.findArtifactSessionLocator(sessionKey: String): SessionLocator? =
    sessionSnapshot.value.sessions
        .firstOrNull { it.locator.stableUiKey == sessionKey }
        ?.locator
