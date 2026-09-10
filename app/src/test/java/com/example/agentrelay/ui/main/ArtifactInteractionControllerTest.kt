/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import com.example.agentrelay.R
import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.RemoteFileAccessException
import dev.agentrelay.provider.api.RemoteFileReference
import dev.agentrelay.provider.api.RemoteFileRevision
import dev.agentrelay.provider.api.RemoteFileSnapshot
import dev.agentrelay.session.api.SessionHubSnapshot
import dev.agentrelay.session.api.SessionLocator
import dev.agentrelay.session.api.SessionObservation
import dev.agentrelay.session.api.SessionRecord
import dev.agentrelay.session.runtime.PreparedArtifactDownload
import dev.agentrelay.session.runtime.SessionCoordinatorSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArtifactInteractionControllerTest {
    @Test
    fun refreshAndCheckedExportResolveOnlyStableUiIdentity() = runTest {
        val locator = artifactLocator(ConnectionProviderId("local.device"))
        val artifact = artifact(locator)
        val runtime = FakeSessionHubRuntime(
            providers = emptyList(),
            coordinator = SessionCoordinatorSnapshot(),
            sessions = SessionHubSnapshot(
                sessions = listOf(artifactSession(locator)),
                artifacts = listOf(artifact),
            ),
        ).apply {
            refreshedArtifacts = listOf(artifact)
            preparedArtifactDownload = preparedDownload(
                artifact = artifact,
                sizeBytes = 6,
                sha256 = "0".repeat(64),
                chunks = flowOf("result".encodeToByteArray()),
            )
        }
        var reportedError: UiMessage? = UiMessage.Verbatim("stale")
        val controller = ArtifactInteractionController(
            scope = this,
            runtime = { runtime },
            reportError = { reportedError = it },
        )

        controller.refreshArtifacts(locator.stableUiKey)
        advanceUntilIdle()
        assertEquals(listOf(locator), runtime.refreshArtifactRequests)

        val destination = FakeArtifactDestination()
        controller.exportArtifact(
            sessionKey = locator.stableUiKey,
            artifactKey = artifact.stableUiKey,
            destination = destination,
        )
        advanceUntilIdle()

        assertEquals(listOf(locator to artifact.id), runtime.preparedArtifactRequests)
        assertEquals("result", destination.writtenChunks.single().decodeToString())
        assertEquals(0, destination.discardCount)
        val completed = controller.state.value.transfers.getValue(artifact.stableUiKey)
        assertTrue(completed.isComplete)
        assertEquals(6L, completed.bytesWritten)
        assertEquals(null, reportedError)
    }

    @Test
    fun failureAndCancellationDiscardPartialDocumentsWithoutLeakingDetails() = runTest {
        val locator = artifactLocator(ConnectionProviderId("ssh.secure-shell"))
        val artifact = artifact(locator)
        val runtime = FakeSessionHubRuntime(
            providers = emptyList(),
            coordinator = SessionCoordinatorSnapshot(),
            sessions = SessionHubSnapshot(
                sessions = listOf(artifactSession(locator)),
                artifacts = listOf(artifact),
            ),
        )
        var reportedError: UiMessage? = null
        val controller = ArtifactInteractionController(
            scope = this,
            runtime = { runtime },
            reportError = { reportedError = it },
        )

        runtime.prepareArtifactFailure = RemoteFileAccessException(
            code = "FILE_CHANGED",
            actionableMessage = "The source file changed. Refresh changed files and try again.",
            cause = IllegalStateException("private-host.example contained a secret path"),
        )
        val failedDestination = FakeArtifactDestination()
        controller.exportArtifact(
            locator.stableUiKey,
            artifact.stableUiKey,
            failedDestination,
        )
        advanceUntilIdle()

        assertEquals(1, failedDestination.discardCount)
        assertEquals(
            UiMessage.Verbatim(
                "The source file changed. Refresh changed files and try again.",
            ),
            reportedError,
        )
        assertFalse(checkNotNull(reportedError).toString().contains("private-host"))

        runtime.prepareArtifactFailure = null
        runtime.preparedArtifactDownload = preparedDownload(
            artifact = artifact,
            sizeBytes = 3,
            sha256 = "1".repeat(64),
            chunks = flow {
                emit("abc".encodeToByteArray())
                awaitCancellation()
            },
        )
        val cancelledDestination = FakeArtifactDestination()
        controller.exportArtifact(
            locator.stableUiKey,
            artifact.stableUiKey,
            cancelledDestination,
        )
        runCurrent()
        assertEquals(
            3L,
            controller.state.value.transfers.getValue(artifact.stableUiKey).bytesWritten,
        )

        val duplicateDestination = FakeArtifactDestination()
        controller.exportArtifact(
            locator.stableUiKey,
            artifact.stableUiKey,
            duplicateDestination,
        )
        runCurrent()

        assertEquals(1, duplicateDestination.discardCount)
        assertEquals(UiMessage.Localized(R.string.artifact_error_save_in_progress), reportedError)
        assertEquals(0, cancelledDestination.discardCount)

        reportedError = null
        controller.cancelArtifactExport(artifact.stableUiKey)
        runCurrent()

        assertEquals(1, cancelledDestination.discardCount)
        assertFalse(artifact.stableUiKey in controller.state.value.transfers)
        assertEquals(null, reportedError)
    }

    @Test
    fun appOwnedRefreshAndSaveFailuresDoNotExposeRuntimeDetails() = runTest {
        val locator = artifactLocator(ConnectionProviderId("ssh.secure-shell"))
        val artifact = artifact(locator)
        val runtime = FakeSessionHubRuntime(
            providers = emptyList(),
            coordinator = SessionCoordinatorSnapshot(),
            sessions = SessionHubSnapshot(
                sessions = listOf(artifactSession(locator)),
                artifacts = listOf(artifact),
            ),
        )
        var reportedError: UiMessage? = null
        val controller = ArtifactInteractionController(
            scope = this,
            runtime = { runtime },
            reportError = { reportedError = it },
        )

        runtime.refreshArtifactFailure =
            IllegalStateException("private.example.test changed-file refresh detail")
        controller.refreshArtifacts(locator.stableUiKey)
        advanceUntilIdle()
        assertEquals(
            UiMessage.Localized(R.string.artifact_error_refresh),
            reportedError,
        )

        runtime.prepareArtifactFailure =
            IllegalStateException("private.example.test checked-copy detail")
        val destination = FakeArtifactDestination()
        controller.exportArtifact(
            locator.stableUiKey,
            artifact.stableUiKey,
            destination,
        )
        advanceUntilIdle()
        assertEquals(1, destination.discardCount)
        assertEquals(
            UiMessage.Localized(R.string.artifact_error_save),
            reportedError,
        )
        assertFalse(checkNotNull(reportedError).toString().contains("private.example.test"))
    }

    @Test
    fun rejectedSaveRequestsDiscardDocumentsCreatedByTheSystemPicker() = runTest {
        var runtime: FakeSessionHubRuntime? = null
        var reportedError: UiMessage? = null
        val controller = ArtifactInteractionController(
            scope = this,
            runtime = { runtime },
            reportError = { reportedError = it },
        )
        val unavailableDestination = FakeArtifactDestination()

        controller.exportArtifact(
            sessionKey = "stale-session",
            artifactKey = "stale-artifact",
            destination = unavailableDestination,
        )
        advanceUntilIdle()

        assertEquals(1, unavailableDestination.discardCount)
        assertEquals(UiMessage.Localized(R.string.artifact_error_unavailable), reportedError)

        val activeRuntime = FakeSessionHubRuntime(
            providers = emptyList(),
            coordinator = SessionCoordinatorSnapshot(),
            sessions = SessionHubSnapshot(
                sessions = listOf(artifactSession(artifactLocator(ConnectionProviderId("local")))),
            ),
        )
        runtime = activeRuntime
        val missingArtifactDestination = FakeArtifactDestination()

        controller.exportArtifact(
            sessionKey = activeRuntime.sessionSnapshot.value.sessions.single().locator.stableUiKey,
            artifactKey = "missing-artifact",
            destination = missingArtifactDestination,
        )
        advanceUntilIdle()

        assertEquals(1, missingArtifactDestination.discardCount)
        assertEquals(UiMessage.Localized(R.string.artifact_error_unavailable), reportedError)
        assertTrue(activeRuntime.preparedArtifactRequests.isEmpty())
    }
}

private fun artifactLocator(providerId: ConnectionProviderId) = SessionLocator(
    connectionProviderId = providerId,
    connectionProfileId = ConnectionProfileId("test-profile"),
    agentProviderId = AgentProviderId("agent.codex"),
    agentSessionId = AgentSessionId("artifact-session"),
)

private fun artifactSession(locator: SessionLocator) = SessionRecord(
    observation = SessionObservation(
        locator = locator,
        connectionLabel = "Test connection",
        connectionTarget = "Test target",
        projectPath = "/workspace/project",
        agentProviderLabel = "Codex",
        title = "Artifacts",
        preview = "Ready",
        agentState = AgentSessionState.IDLE,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    ),
)

private fun preparedDownload(
    artifact: dev.agentrelay.session.api.SessionArtifact,
    sizeBytes: Long,
    sha256: String,
    chunks: kotlinx.coroutines.flow.Flow<ByteArray>,
) = PreparedArtifactDownload(
    artifact = artifact,
    sourceSnapshot = RemoteFileSnapshot(
        reference = RemoteFileReference(
            workspaceRoot = "/workspace/project",
            relativePath = "reports/result.txt",
        ),
        revision = RemoteFileRevision(
            sizeBytes = sizeBytes,
            modifiedAtEpochMillis = 100,
            sha256 = sha256,
        ),
    ),
    chunks = chunks,
)
