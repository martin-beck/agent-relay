/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.data

import dev.agentrelay.connection.api.ConnectionChallengeId
import dev.agentrelay.connection.api.ConnectionIdentityDecision
import dev.agentrelay.connection.api.ConnectionProfileEditor
import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProfileOperationId
import dev.agentrelay.connection.api.ConnectionProfileOperationResult
import dev.agentrelay.connection.api.ConnectionProfileSaveResult
import dev.agentrelay.connection.api.ConnectionProfileUpdate
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.connection.api.ConnectionProviderRegistry

import dev.agentrelay.connection.api.ConnectionProviderDescriptor
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.StartSessionOptions
import dev.agentrelay.session.api.SessionArtifact
import dev.agentrelay.session.api.SessionDraft
import dev.agentrelay.session.api.SessionHubSnapshot
import dev.agentrelay.session.api.SessionLocator
import dev.agentrelay.session.api.SessionPreferences
import dev.agentrelay.session.runtime.AgentEndpointKey
import dev.agentrelay.session.runtime.PreparedArtifactDownload
import dev.agentrelay.session.runtime.SessionConnectionKey
import dev.agentrelay.session.runtime.SessionCoordinator
import dev.agentrelay.session.runtime.SessionCoordinatorSnapshot
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.CancellationException

internal interface SessionHubRuntime {
    val connectionProviders: List<ConnectionProviderDescriptor>
    val coordinatorSnapshot: StateFlow<SessionCoordinatorSnapshot>
    val sessionSnapshot: StateFlow<SessionHubSnapshot>

    suspend fun refreshProfiles()
    suspend fun profileEditor(
        providerId: ConnectionProviderId,
        profileId: ConnectionProfileId? = null,
    ): ConnectionProfileEditor

    suspend fun saveProfile(update: ConnectionProfileUpdate): ConnectionProfileSaveResult

    suspend fun deleteProfile(
        providerId: ConnectionProviderId,
        profileId: ConnectionProfileId,
    )

    suspend fun performProfileOperation(
        providerId: ConnectionProviderId,
        profileId: ConnectionProfileId,
        operationId: ConnectionProfileOperationId,
    ): ConnectionProfileOperationResult {
        error("Connection profile operations are unavailable")
    }

    suspend fun connect(key: SessionConnectionKey)

    suspend fun disconnect(key: SessionConnectionKey)

    suspend fun resolveIdentityChallenge(
        key: SessionConnectionKey,
        challengeId: ConnectionChallengeId,
        decision: ConnectionIdentityDecision,
    ): Boolean

    suspend fun markSessionRead(locator: SessionLocator)

    suspend fun setSessionPreferences(locator: SessionLocator, preferences: SessionPreferences) {
        error("Session preferences are unavailable")
    }

    suspend fun updateDraft(locator: SessionLocator, draft: SessionDraft)

    suspend fun resumeSession(locator: SessionLocator)

    suspend fun sendInput(locator: SessionLocator, text: String)

    suspend fun steerActiveTurn(locator: SessionLocator, text: String)

    suspend fun interrupt(locator: SessionLocator)

    suspend fun refreshArtifacts(locator: SessionLocator): List<SessionArtifact>

    suspend fun prepareArtifactDownload(locator: SessionLocator, artifactId: String): PreparedArtifactDownload

    suspend fun startSession(
        endpoint: AgentEndpointKey,
        options: StartSessionOptions,
    ): SessionLocator

    suspend fun respondToAction(
        locator: SessionLocator,
        requestId: String,
        decision: AgentApprovalDecision,
        answers: Map<String, List<String>>,
        additionalConfirmationGiven: Boolean,
    )
}

internal interface BackgroundAwareSessionHubRuntime {
    suspend fun suspendForBackground()

    suspend fun resumeFromBackground()
}

internal class CoordinatorSessionHubRuntime(
    private val coordinator: SessionCoordinator,
    override val connectionProviders: List<ConnectionProviderDescriptor>,
    private val connections: ConnectionProviderRegistry,
    private val onConnectRequested: suspend (SessionConnectionKey) -> Unit = {},
    private val onDisconnectRequested: suspend (SessionConnectionKey) -> Unit = {},
) : SessionHubRuntime, BackgroundAwareSessionHubRuntime {
    override val coordinatorSnapshot: StateFlow<SessionCoordinatorSnapshot>
        get() = coordinator.snapshot

    override val sessionSnapshot: StateFlow<SessionHubSnapshot>
        get() = coordinator.repository.snapshot

    override suspend fun refreshProfiles() = coordinator.refreshProfiles()

    override suspend fun connect(key: SessionConnectionKey) {
        onConnectRequested(key)
        coordinator.connect(key)
    }
    override suspend fun profileEditor(
        providerId: ConnectionProviderId,
        profileId: ConnectionProfileId?,
    ): ConnectionProfileEditor = connections.profileManager(providerId).editor(profileId)

    override suspend fun saveProfile(
        update: ConnectionProfileUpdate,
    ): ConnectionProfileSaveResult {
        val result = connections.profileManager(update.providerId).save(update)
        coordinator.refreshProfiles()
        return result
    }

    override suspend fun deleteProfile(
        providerId: ConnectionProviderId,
        profileId: ConnectionProfileId,
    ) {
        runAfterRecordingDisconnect(SessionConnectionKey(providerId, profileId)) {
            connections.profileManager(providerId).delete(profileId)
            coordinator.refreshProfiles()
        }
    }

    override suspend fun performProfileOperation(
        providerId: ConnectionProviderId,
        profileId: ConnectionProfileId,
        operationId: ConnectionProfileOperationId,
    ): ConnectionProfileOperationResult {
        val result = connections.profileManager(providerId).performOperation(profileId, operationId)
        coordinator.refreshProfiles()
        return result
    }

    override suspend fun disconnect(key: SessionConnectionKey) {
        runAfterRecordingDisconnect(key) {
            coordinator.disconnect(key)
        }
    }

    override suspend fun suspendForBackground() = coordinator.suspendForBackground()

    override suspend fun resumeFromBackground() = coordinator.resumeFromBackground()

    override suspend fun resolveIdentityChallenge(
        key: SessionConnectionKey,
        challengeId: ConnectionChallengeId,
        decision: ConnectionIdentityDecision,
    ): Boolean = coordinator.resolveIdentityChallenge(key, challengeId, decision)

    override suspend fun markSessionRead(locator: SessionLocator) {
        coordinator.repository.markSessionRead(locator)
    }

    override suspend fun setSessionPreferences(
        locator: SessionLocator,
        preferences: SessionPreferences,
    ) {
        coordinator.repository.setPreferences(locator, preferences)
    }

    override suspend fun updateDraft(locator: SessionLocator, draft: SessionDraft) {
        coordinator.repository.updateDraft(locator, draft)
    }

    override suspend fun resumeSession(locator: SessionLocator) {
        coordinator.attach(locator)
    }

    override suspend fun sendInput(locator: SessionLocator, text: String) {
        coordinator.sendInput(locator, text)
    }

    override suspend fun steerActiveTurn(locator: SessionLocator, text: String) {
        coordinator.steerActiveTurn(locator, text)
    }

    override suspend fun interrupt(locator: SessionLocator) {
        coordinator.interrupt(locator)
    }

    override suspend fun refreshArtifacts(locator: SessionLocator): List<SessionArtifact> =
        coordinator.changedFiles(locator)

    override suspend fun prepareArtifactDownload(
        locator: SessionLocator,
        artifactId: String,
    ): PreparedArtifactDownload = coordinator.prepareArtifactDownload(locator, artifactId)

    override suspend fun startSession(
        endpoint: AgentEndpointKey,
        options: StartSessionOptions,
    ): SessionLocator = coordinator.startSession(endpoint, options)

    override suspend fun respondToAction(
        locator: SessionLocator,
        requestId: String,
        decision: AgentApprovalDecision,
        answers: Map<String, List<String>>,
        additionalConfirmationGiven: Boolean,
    ) {
        coordinator.respondToAction(
            locator = locator,
            requestId = requestId,
            decision = decision,
            answers = answers,
            additionalConfirmationGiven = additionalConfirmationGiven,
        )
    }

    private suspend fun runAfterRecordingDisconnect(
        key: SessionConnectionKey,
        operation: suspend () -> Unit,
    ) {
        val leaseFailure = try {
            onDisconnectRequested(key)
            null
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            failure
        }
        try {
            operation()
        } catch (failure: Throwable) {
            failure.rethrowCancellation()
            if (leaseFailure != null) {
                failure.addSuppressed(leaseFailure)
            }
            throw failure
        }
        if (leaseFailure != null) {
            throw leaseFailure
        }
    }
}

private fun Throwable.rethrowCancellation() {
    if (this is CancellationException) {
        throw this
    }
}
