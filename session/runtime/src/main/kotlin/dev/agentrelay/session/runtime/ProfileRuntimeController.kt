/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.runtime

import dev.agentrelay.connection.api.ConnectionChallengeId
import dev.agentrelay.connection.api.ConnectionIdentityDecision
import dev.agentrelay.connection.api.ConnectionProfileSummary
import dev.agentrelay.connection.api.ConnectionState
import dev.agentrelay.connection.api.ManagedConnection
import dev.agentrelay.provider.api.AgentCapability
import dev.agentrelay.provider.api.AgentEvent
import dev.agentrelay.provider.api.AgentProviderConnection
import dev.agentrelay.provider.api.AgentProviderDescriptor
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentProviderRegistry
import dev.agentrelay.provider.api.AgentSession
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.AgentTranscriptEntry
import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.session.api.SessionActivity
import dev.agentrelay.session.api.SessionActivitySummary
import dev.agentrelay.session.api.SessionActivitySummaryKind
import dev.agentrelay.session.api.SessionActivityType
import dev.agentrelay.session.api.SessionEventUpdate
import dev.agentrelay.session.api.SessionHubRepository
import dev.agentrelay.session.api.SessionLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

internal interface ProfileRuntimeListener {
    suspend fun onConnectionState(
        key: SessionConnectionKey,
        state: ConnectionState,
    )

    suspend fun onEndpointStatus(status: AgentEndpointStatus)

    suspend fun onIssue(
        id: String,
        issue: SessionCoordinatorIssue?,
    )
}

internal data class ActiveAgentHandle(
    val descriptor: AgentProviderDescriptor,
    val connection: AgentProviderConnection,
    val readiness: ProviderReadiness.Ready,
    val runtime: RemoteAgentRuntime,
)

internal class ProfileRuntimeController(
    val profile: ConnectionProfileSummary,
    private val managed: ManagedConnection,
    private val agentRegistry: AgentProviderRegistry,
    private val repository: SessionHubRepository,
    parentScope: CoroutineScope,
    private val clock: SessionCoordinatorClock,
    private val listener: ProfileRuntimeListener,
) {
    val key = SessionConnectionKey(profile.providerId, profile.id)

    private val controllerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + controllerJob)
    private val runtimeSignal = MutableStateFlow<RemoteAgentRuntime?>(null)
    private val activeMutex = Mutex()
    private val activeAgents = mutableMapOf<AgentProviderId, ActiveAgentHandle>()
    private var runtimeGeneration = 0L
    private var hasConnectedRuntime = false

    fun start() {
        scope.launch {
            managed.state.collect { state ->
                listener.onConnectionState(key, state)
                runtimeSignal.value = if (state is ConnectionState.Connected) {
                    managed.runtimeOrNull()
                } else {
                    null
                }
            }
        }
        scope.launch {
            runtimeSignal.collectLatest { runtime ->
                if (runtime == null) {
                    markAllEndpointsOffline()
                } else {
                    try {
                        synchronizeRuntime(runtime)
                    } finally {
                        withContext(NonCancellable) {
                            markAllEndpointsOffline()
                        }
                    }
                }
            }
        }
    }

    fun connect() = managed.connect()

    suspend fun resolveIdentityChallenge(
        challengeId: ConnectionChallengeId,
        decision: ConnectionIdentityDecision,
    ): Boolean = managed.resolveIdentityChallenge(challengeId, decision)

    suspend fun disconnect() = managed.disconnect()

    suspend fun suspendForBackground() = managed.suspendForBackground()

    fun resumeFromBackground() = managed.resumeFromBackground()

    suspend fun active(agentProviderId: AgentProviderId): ActiveAgentHandle =
        activeMutex.withLock {
            checkNotNull(activeAgents[agentProviderId]) {
                "Agent provider is not connected for this profile"
            }
        }

    suspend fun refreshAgentSessions(agentProviderId: AgentProviderId): List<AgentSession> {
        val active = active(agentProviderId)
        val sessions = retryBounded(
            onRetry = { _, _, _ ->
                publishStatus(active.descriptor, AgentEndpointPhase.RETRYING, active.readiness)
                publishProviderIssue(active.descriptor)
            },
        ) { active.connection.refreshSessions() }
        val accepted = persistSessions(active.descriptor, sessions)
        cacheTranscripts(active, accepted)
        publishReady(active.descriptor, accepted.size, active.readiness)
        return accepted
    }

    suspend fun persistSession(
        descriptor: AgentProviderDescriptor,
        session: AgentSession,
    ): SessionLocator {
        require(session.providerId == descriptor.id) {
            "Agent session provider does not match its factory"
        }
        val observation = SessionDataMapper.observation(profile, descriptor, session)
        repository.upsertSession(observation)
        return observation.locator
    }

    suspend fun persistTranscript(
        active: ActiveAgentHandle,
        sessionId: AgentSessionId,
        entries: List<AgentTranscriptEntry>? = null,
    ) {
        if (AgentCapability.SESSION_HISTORY !in active.descriptor.capabilities) {
            return
        }
        val transcript = entries ?: active.connection.transcript(sessionId)
        val locator = SessionDataMapper.locator(endpoint(active.descriptor.id), sessionId)
        repository.cacheTranscript(
            locator,
            transcript.filter { it.sessionId == sessionId }
                .takeLast(MAX_CACHED_TRANSCRIPT_ENTRIES)
                .map(SessionDataMapper::transcript),
        )
    }

    suspend fun shutdown(disconnect: Boolean) {
        controllerJob.cancelAndJoin()
        if (disconnect) {
            withTimeoutOrNull(CLOSE_TIMEOUT) {
                managed.disconnect()
            }
        }
    }

    private suspend fun synchronizeRuntime(runtime: RemoteAgentRuntime) {
        runtimeGeneration += 1L
        if (hasConnectedRuntime) {
            recordReconnect()
        }
        hasConnectedRuntime = true

        supervisorScope {
            agentRegistry.descriptors().forEach { descriptor ->
                launch {
                    synchronizeAgent(runtime, descriptor)
                }
            }
            awaitCancellation()
        }
    }

    private suspend fun synchronizeAgent(
        runtime: RemoteAgentRuntime,
        descriptor: AgentProviderDescriptor,
    ) {
        try {
            retryBounded(
                onRetry = { _, _, _ ->
                    publishStatus(descriptor, AgentEndpointPhase.RETRYING)
                    publishProviderIssue(descriptor)
                },
            ) {
                synchronizeAgentAttempt(runtime, descriptor)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            publishStatus(
                descriptor = descriptor,
                phase = AgentEndpointPhase.FAILED,
                readiness = ProviderReadiness.Failed(
                    reason = descriptor.displayName + " could not be synchronized",
                    recoverable = true,
                ),
            )
            publishProviderIssue(descriptor)
        }
    }

    private suspend fun synchronizeAgentAttempt(
        runtime: RemoteAgentRuntime,
        descriptor: AgentProviderDescriptor,
    ) {
        val endpoint = endpoint(descriptor.id)
        publishStatus(descriptor, AgentEndpointPhase.PROBING)
        val factory = agentRegistry.factory(descriptor.id)
        val readiness = factory.probe(runtime)

        if (readiness !is ProviderReadiness.Ready) {
            publishStatus(
                descriptor = descriptor,
                phase = if (readiness is ProviderReadiness.Failed) {
                    AgentEndpointPhase.FAILED
                } else {
                    AgentEndpointPhase.UNAVAILABLE
                },
                readiness = readiness,
            )
            clearProviderIssue(descriptor)
            return
        }

        publishStatus(
            descriptor = descriptor,
            phase = AgentEndpointPhase.CONNECTING,
            readiness = readiness,
        )
        var connection: AgentProviderConnection? = null
        try {
            connection = factory.connect(runtime)
            require(connection.descriptor == descriptor) {
                "Agent provider connection descriptor does not match its factory"
            }
            val active = ActiveAgentHandle(descriptor, connection, readiness, runtime)
            activeMutex.withLock {
                activeAgents[descriptor.id] = active
            }
            clearProviderIssue(descriptor)

            coroutineScope {
                launch {
                    connection.events.collect { event ->
                        persistEvent(descriptor, event)
                    }
                }

                val sessions = retryBounded(
                    onRetry = { _, _, _ ->
                        publishStatus(descriptor, AgentEndpointPhase.RETRYING, readiness)
                    },
                ) { connection.refreshSessions() }
                val accepted = persistSessions(descriptor, sessions)
                publishReady(descriptor, accepted.size, readiness)
                cacheTranscripts(active, accepted)

                launch {
                    connection.sessions.drop(1).collect { changed ->
                        val current = persistSessions(descriptor, changed)
                        publishReady(descriptor, current.size, readiness)
                    }
                }
                awaitCancellation()
            }
        } finally {
            activeMutex.withLock {
                if (activeAgents[descriptor.id]?.connection === connection) {
                    activeAgents.remove(descriptor.id)
                }
            }
            if (connection != null) {
                withContext(NonCancellable) {
                    withTimeoutOrNull(CLOSE_TIMEOUT) {
                        connection.close()
                    }
                }
            }
        }
    }

    private suspend fun persistSessions(
        descriptor: AgentProviderDescriptor,
        sessions: List<AgentSession>,
    ): List<AgentSession> {
        val accepted = sessions.filter { it.providerId == descriptor.id }
        try {
            accepted.forEach { persistSession(descriptor, it) }
            val discoveredIds = accepted.mapTo(mutableSetOf()) { it.id }
            repository.snapshot.value.sessions
                .asSequence()
                .filter {
                    it.locator.connectionProviderId == key.providerId &&
                        it.locator.connectionProfileId == key.profileId &&
                        it.locator.agentProviderId == descriptor.id &&
                        it.locator.agentSessionId !in discoveredIds
                }
                .forEach { record ->
                    repository.upsertSession(
                        record.observation.copy(agentState = AgentSessionState.NOT_LOADED),
                    )
                }
            clearPersistenceIssue(descriptor)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            publishPersistenceIssue(descriptor)
        }
        return accepted
    }

    private suspend fun cacheTranscripts(
        active: ActiveAgentHandle,
        sessions: List<AgentSession>,
    ) {
        if (AgentCapability.SESSION_HISTORY !in active.descriptor.capabilities) {
            return
        }
        sessions.forEach { session ->
            try {
                persistTranscript(active, session.id)
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Throwable) {
                publishPersistenceIssue(active.descriptor)
            }
        }
    }

    private suspend fun persistEvent(
        descriptor: AgentProviderDescriptor,
        event: AgentEvent,
    ) {
        val locator = SessionDataMapper.locator(endpoint(descriptor.id), event.sessionId)
        val now = now()
        val current = repository.snapshot.value.session(locator)?.observation
        val projection = SessionDataMapper.event(
            event = event,
            locator = locator,
            now = now,
            workspaceRoot = current?.projectPath,
        )
        if (projection.isEmpty) {
            return
        }
        try {
            val base = current ?: SessionDataMapper.placeholderObservation(
                profile = profile,
                descriptor = descriptor,
                locator = locator,
                preview = projection.preview.orEmpty(),
                now = now,
            )
            val observation = base.copy(
                preview = projection.preview ?: base.preview,
                agentState = projection.state ?: base.agentState,
                updatedAtEpochMillis = now,
            )
            repository.applyEvent(
                SessionEventUpdate(
                    locator = locator,
                    observation = observation,
                    transcriptEntry = projection.transcriptEntry,
                    activity = projection.activity,
                    actionRequest = projection.actionRequest,
                    artifact = projection.artifact,
                ),
            )
            clearPersistenceIssue(descriptor)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            publishPersistenceIssue(descriptor)
        }
    }

    private suspend fun recordReconnect() {
        val now = now()
        val anchor = "reconnect:" + now + ":" + runtimeGeneration
        repository.snapshot.value.sessions
            .filter {
                it.locator.connectionProviderId == key.providerId &&
                    it.locator.connectionProfileId == key.profileId
            }
            .forEach { record ->
                try {
                    repository.applyEvent(
                        SessionEventUpdate(
                            locator = record.locator,
                            activity = SessionActivity(
                                id = "reconnected:" + now + ":" + runtimeGeneration,
                                locator = record.locator,
                                type = SessionActivityType.RECONNECTED,
                                summary = SessionActivitySummary.Generated(
                                    SessionActivitySummaryKind.CONNECTION_RECONNECTED,
                                    profile.label.take(256),
                                ),
                                eventAnchorId = anchor,
                                occurredAtEpochMillis = now,
                            ),
                        ),
                    )
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Throwable) {
                    agentRegistry.descriptors()
                        .firstOrNull { it.id == record.locator.agentProviderId }
                        ?.let { publishPersistenceIssue(it) }
                }
            }
    }

    private suspend fun markAllEndpointsOffline() {
        agentRegistry.descriptors().forEach { descriptor ->
            publishStatus(
                descriptor = descriptor,
                phase = AgentEndpointPhase.OFFLINE,
            )
        }
    }

    private suspend fun publishReady(
        descriptor: AgentProviderDescriptor,
        sessionCount: Int,
        readiness: ProviderReadiness? = null,
    ) {
        val priorReadiness = readiness
        publishStatus(
            descriptor = descriptor,
            phase = AgentEndpointPhase.READY,
            readiness = priorReadiness,
            sessionCount = sessionCount,
        )
    }

    private suspend fun publishStatus(
        descriptor: AgentProviderDescriptor,
        phase: AgentEndpointPhase,
        readiness: ProviderReadiness? = null,
        sessionCount: Int = 0,
    ) {
        listener.onEndpointStatus(
            AgentEndpointStatus(
                key = endpoint(descriptor.id),
                descriptor = descriptor,
                phase = phase,
                fileAccessAvailable = phase == AgentEndpointPhase.READY && runtimeSignal.value?.fileAccess != null,
                readiness = readiness,
                sessionCount = sessionCount,
                updatedAtEpochMillis = now(),
            ),
        )
    }

    private suspend fun publishProviderIssue(descriptor: AgentProviderDescriptor) {
        val id = providerIssueId(descriptor.id)
        listener.onIssue(
            id,
            SessionCoordinatorIssue(
                id = id,
                kind = SessionCoordinatorIssueKind.PROVIDER_SYNCHRONIZATION,
                connection = key,
                agentProviderId = descriptor.id,
                connectionLabel = profile.label.take(256),
                agentProviderLabel = descriptor.displayName
                    .takeIf(String::isNotBlank)?.take(256)
                    ?: descriptor.id.value.take(256),
                recoverable = true,
                occurredAtEpochMillis = now(),
            ),
        )
    }

    private suspend fun clearProviderIssue(descriptor: AgentProviderDescriptor) {
        listener.onIssue(providerIssueId(descriptor.id), null)
    }

    private suspend fun publishPersistenceIssue(descriptor: AgentProviderDescriptor) {
        val id = persistenceIssueId(descriptor.id)
        listener.onIssue(
            id,
            SessionCoordinatorIssue(
                id = id,
                kind = SessionCoordinatorIssueKind.SESSION_PERSISTENCE,
                connection = key,
                agentProviderId = descriptor.id,
                agentProviderLabel = descriptor.displayName
                    .takeIf(String::isNotBlank)?.take(256)
                    ?: descriptor.id.value.take(256),
                recoverable = true,
                occurredAtEpochMillis = now(),
            ),
        )
    }

    private suspend fun clearPersistenceIssue(descriptor: AgentProviderDescriptor) {
        listener.onIssue(persistenceIssueId(descriptor.id), null)
    }

    private fun providerIssueId(agentProviderId: AgentProviderId): String =
        "provider:" + key.providerId.value + ":" + key.profileId.value + ":" + agentProviderId.value

    private fun persistenceIssueId(agentProviderId: AgentProviderId): String =
        "persistence:" + key.providerId.value + ":" + key.profileId.value + ":" + agentProviderId.value

    private fun endpoint(agentProviderId: AgentProviderId) = AgentEndpointKey(key, agentProviderId)

    private fun now(): Long = clock.epochMillis().coerceAtLeast(0L)

    private companion object {
        val CLOSE_TIMEOUT = 5.seconds
        const val MAX_CACHED_TRANSCRIPT_ENTRIES = 500
    }
}
