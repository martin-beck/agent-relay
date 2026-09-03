package com.example.agentrelay.ui.main

import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.session.api.SessionArtifact
import dev.agentrelay.session.api.SessionArtifactAvailability

internal object SessionArtifactUiMapper {
    fun map(
        artifact: SessionArtifact,
        transfer: ArtifactTransferUiState?,
        providerReady: Boolean,
        fileAccessAvailable: Boolean,
    ) = SessionArtifactUiModel(
        stableKey = artifact.stableUiKey,
        sessionKey = artifact.locator.stableUiKey,
        displayPath = artifact.safeDisplayPath(),
        changeLabel = artifact.kind.uiLabel,
        availabilityStatus =
        artifact.availability.uiStatus(providerReady, fileAccessAvailable),
        suggestedFileName = artifact.suggestedFileName(),
        isDownloadable =
        artifact.availability == SessionArtifactAvailability.DOWNLOADABLE,
        canSave =
        artifact.availability == SessionArtifactAvailability.DOWNLOADABLE &&
            providerReady &&
            fileAccessAvailable &&
            transfer?.isRunning != true,
        bytesWritten = transfer?.bytesWritten ?: 0L,
        totalBytes = transfer?.totalBytes,
        isExporting = transfer?.isRunning == true,
        isExportComplete = transfer?.isComplete == true,
    )

    private fun SessionArtifact.safeDisplayPath(): String =
        relativePath ?: when (availability) {
            SessionArtifactAvailability.DELETED -> "Deleted file"
            SessionArtifactAvailability.OUTSIDE_WORKSPACE -> "File outside workspace"
            SessionArtifactAvailability.WORKSPACE_UNKNOWN -> "File with unknown workspace"
            SessionArtifactAvailability.DOWNLOADABLE ->
                error("Downloadable artifact lacks a path")
        }

    private fun SessionArtifact.suggestedFileName(): String =
        relativePath
            ?.substringAfterLast('/')
            ?.filterNot { it.isISOControl() || it == '/' || it == '\\' }
            ?.take(MAX_SAF_FILE_NAME_CHARS)
            ?.takeIf(String::isNotBlank)
            ?: "session-artifact"

    private val AgentFileChangeKind.uiLabel: String
        get() = when (this) {
            AgentFileChangeKind.ADDED -> "Added"
            AgentFileChangeKind.MODIFIED -> "Modified"
            AgentFileChangeKind.DELETED -> "Deleted"
            AgentFileChangeKind.RENAMED -> "Renamed"
            AgentFileChangeKind.UNKNOWN -> "Changed"
        }

    private fun SessionArtifactAvailability.uiStatus(
        providerReady: Boolean,
        fileAccessAvailable: Boolean,
    ): SessionArtifactAvailabilityStatus =
        when (this) {
            SessionArtifactAvailability.DOWNLOADABLE -> when {
                !providerReady -> SessionArtifactAvailabilityStatus.RECONNECT
                !fileAccessAvailable ->
                    SessionArtifactAvailabilityStatus.UNSUPPORTED
                else -> SessionArtifactAvailabilityStatus.READY
            }
            SessionArtifactAvailability.DELETED ->
                SessionArtifactAvailabilityStatus.DELETED
            SessionArtifactAvailability.OUTSIDE_WORKSPACE ->
                SessionArtifactAvailabilityStatus.OUTSIDE_WORKSPACE
            SessionArtifactAvailability.WORKSPACE_UNKNOWN ->
                SessionArtifactAvailabilityStatus.WORKSPACE_UNKNOWN
        }

    private const val MAX_SAF_FILE_NAME_CHARS = 255
}
