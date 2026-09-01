package com.example.agentrelay.data

import dev.agentrelay.connection.api.ConnectionChallengeId
import dev.agentrelay.connection.api.ConnectionIdentityDecision
import dev.agentrelay.connection.api.ConnectionProfileEditor
import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProfileSaveResult
import dev.agentrelay.connection.api.ConnectionProfileUpdate
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.connection.api.ConnectionProviderRegistry

import dev.agentrelay.connection.api.ConnectionProviderDescriptor
import dev.agentrelay.session.api.SessionHubSnapshot
import dev.agentrelay.session.api.SessionLocator
import dev.agentrelay.session.runtime.SessionConnectionKey
import dev.agentrelay.session.runtime.SessionCoordinator
import dev.agentrelay.session.runtime.SessionCoordinatorSnapshot
import kotlinx.coroutines.flow.StateFlow

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

    suspend fun connect(key: SessionConnectionKey)

    suspend fun disconnect(key: SessionConnectionKey)

    suspend fun resolveIdentityChallenge(
        key: SessionConnectionKey,
        challengeId: ConnectionChallengeId,
        decision: ConnectionIdentityDecision,
    ): Boolean

    suspend fun markSessionRead(locator: SessionLocator)
}

internal class CoordinatorSessionHubRuntime(
    private val coordinator: SessionCoordinator,
    override val connectionProviders: List<ConnectionProviderDescriptor>,
    private val connections: ConnectionProviderRegistry,
) : SessionHubRuntime {
    override val coordinatorSnapshot: StateFlow<SessionCoordinatorSnapshot>
        get() = coordinator.snapshot

    override val sessionSnapshot: StateFlow<SessionHubSnapshot>
        get() = coordinator.repository.snapshot

    override suspend fun refreshProfiles() = coordinator.refreshProfiles()

    override suspend fun connect(key: SessionConnectionKey) = coordinator.connect(key)
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
        connections.profileManager(providerId).delete(profileId)
        coordinator.refreshProfiles()
    }

    override suspend fun disconnect(key: SessionConnectionKey) = coordinator.disconnect(key)

    override suspend fun resolveIdentityChallenge(
        key: SessionConnectionKey,
        challengeId: ConnectionChallengeId,
        decision: ConnectionIdentityDecision,
    ): Boolean = coordinator.resolveIdentityChallenge(key, challengeId, decision)

    override suspend fun markSessionRead(locator: SessionLocator) {
        coordinator.repository.markSessionRead(locator)
    }
}
