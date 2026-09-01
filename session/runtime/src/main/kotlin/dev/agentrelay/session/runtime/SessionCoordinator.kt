package dev.agentrelay.session.runtime

import dev.agentrelay.connection.api.ConnectionChallengeId
import dev.agentrelay.connection.api.ConnectionDisconnectReason
import dev.agentrelay.connection.api.ConnectionFailure
import dev.agentrelay.connection.api.ConnectionFailureCategory
import dev.agentrelay.connection.api.ConnectionIdentityDecision
import dev.agentrelay.connection.api.ConnectionProfileSummary
import dev.agentrelay.connection.api.ConnectionProviderRegistry
import dev.agentrelay.connection.api.ConnectionState
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentCapability
import dev.agentrelay.provider.api.AgentChangedFile
import dev.agentrelay.provider.api.AgentProviderRegistry
import dev.agentrelay.provider.api.AgentSession
import dev.agentrelay.provider.api.StartSessionOptions
import dev.agentrelay.session.api.CachedTranscriptEntry
import dev.agentrelay.session.api.SessionHubRepository
import dev.agentrelay.session.api.SessionLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SessionCoordinator(
    private val connectionRegistry: ConnectionProviderRegistry,
    private val agentRegistry: AgentProviderRegistry,
    val repository: SessionHubRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: SessionCoordinatorClock = SessionCoordinatorClock(System::currentTimeMillis),
) : ProfileRuntimeListener {
    private val coordinatorJob = SupervisorJob()
    private val scope = CoroutineScope(dispatcher + coordinatorJob)
    private val stateMutex = Mutex()
    private val refreshMutex = Mutex()
    private val controllers = mutableMapOf<SessionConnectionKey, ProfileRuntimeController>()
    private val mutableSnapshot = MutableStateFlow(SessionCoordinatorSnapshot())
    private var closed = false
    private val actionResponses = SessionActionResponseCoordinator(repository, ::now)

    val snapshot: StateFlow<SessionCoordinatorSnapshot> = mutableSnapshot.asStateFlow()

    suspend fun refreshProfiles() {
        refreshMutex.withLock {
            check(!closed) { "Session coordinator is closed" }
            updateSnapshot { it.copy(isRefreshingProfiles = true) }
            try {
                val priorProfiles = snapshot.value.profiles
                val discovered = mutableListOf<ConnectionProfileSummary>()
                val discoveryIssues = mutableMapOf<String, SessionCoordinatorIssue>()

                connectionRegistry.descriptors().forEach { descriptor ->
                    val issueId = profileIssueId(descriptor.id.value)
                    val provider = connectionRegistry.provider(descriptor.id)
                    try {
                        val profiles = provider.profiles()
                        require(profiles.all { it.providerId == descriptor.id }) {
                            "Connection provider returned a foreign profile"
                        }
                        require(profiles.distinctBy { it.id }.size == profiles.size) {
                            "Connection provider returned duplicate profile ids"
                        }
                        discovered += profiles
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Throwable) {
                        discovered += priorProfiles.filter { it.providerId == descriptor.id }
                        discoveryIssues[issueId] = SessionCoordinatorIssue(
                            id = issueId,
                            kind = SessionCoordinatorIssueKind.PROFILE_DISCOVERY,
                            connection = null,
                            agentProviderId = null,
                            actionableMessage = descriptor.displayName + " profiles could not be loaded",
                            recoverable = true,
                            occurredAtEpochMillis = now(),
                        )
                    }
                }

                val profiles = discovered
                    .distinctBy { SessionConnectionKey(it.providerId, it.id) }
                    .sortedWith(
                        compareBy<ConnectionProfileSummary> { it.providerId.value }
                            .thenBy { it.label },
                    )
                reconcileControllers(profiles, discoveryIssues)
            } finally {
                updateSnapshot { it.copy(isRefreshingProfiles = false) }
            }
        }
    }

    suspend fun connect(key: SessionConnectionKey) {
        controller(key).connect()
    }

    suspend fun resolveIdentityChallenge(
        key: SessionConnectionKey,
        challengeId: ConnectionChallengeId,
        decision: ConnectionIdentityDecision,
    ): Boolean = controller(key).resolveIdentityChallenge(challengeId, decision)

    suspend fun disconnect(key: SessionConnectionKey) {
        controller(key).disconnect()
    }

    suspend fun suspendForBackground() {
        controllerSnapshot().forEach { it.suspendForBackground() }
    }

    suspend fun resumeFromBackground() {
        controllerSnapshot().forEach(ProfileRuntimeController::resumeFromBackground)
    }

    suspend fun refreshAgentSessions(endpoint: AgentEndpointKey): List<AgentSession> =
        controller(endpoint.connection).refreshAgentSessions(endpoint.agentProviderId)

    suspend fun startSession(
        endpoint: AgentEndpointKey,
        options: StartSessionOptions,
    ): SessionLocator {
        val controller = controller(endpoint.connection)
        val active = controller.active(endpoint.agentProviderId)
        requireCapability(active, AgentCapability.SESSION_START)
        val session = active.connection.startSession(options)
        val locator = controller.persistSession(active.descriptor, session)
        controller.persistTranscript(active, session.id)
        return locator
    }

    suspend fun attach(locator: SessionLocator): SessionLocator {
        val controller = controller(locator.connectionKey())
        val active = controller.active(locator.agentProviderId)
        requireCapability(active, AgentCapability.SESSION_RESUME)
        val session = active.connection.attach(locator.agentSessionId)
        val persisted = controller.persistSession(active.descriptor, session)
        controller.persistTranscript(active, session.id)
        return persisted
    }

    suspend fun transcript(locator: SessionLocator): List<CachedTranscriptEntry> {
        val controller = controller(locator.connectionKey())
        val active = controller.active(locator.agentProviderId)
        requireCapability(active, AgentCapability.SESSION_HISTORY)
        controller.persistTranscript(active, locator.agentSessionId)
        return repository.snapshot.value.transcripts[locator].orEmpty()
    }

    suspend fun sendInput(
        locator: SessionLocator,
        text: String,
    ) {
        require(text.isNotBlank()) { "Session input must not be blank" }
        val active = controller(locator.connectionKey()).active(locator.agentProviderId)
        active.connection.sendInput(locator.agentSessionId, text)
    }

    suspend fun steerActiveTurn(
        locator: SessionLocator,
        text: String,
    ) {
        require(text.isNotBlank()) { "Steering input must not be blank" }
        val active = controller(locator.connectionKey()).active(locator.agentProviderId)
        requireCapability(active, AgentCapability.ACTIVE_TURN_STEERING)
        active.connection.steerActiveTurn(locator.agentSessionId, text)
    }

    suspend fun interrupt(locator: SessionLocator) {
        val active = controller(locator.connectionKey()).active(locator.agentProviderId)
        requireCapability(active, AgentCapability.TURN_INTERRUPT)
        active.connection.interrupt(locator.agentSessionId)
    }

    suspend fun respondToAction(
        locator: SessionLocator,
        requestId: String,
        decision: AgentApprovalDecision,
        answers: Map<String, List<String>> = emptyMap(),
        additionalConfirmationGiven: Boolean = false,
    ) {
        val active = controller(locator.connectionKey()).active(locator.agentProviderId)
        requireCapability(active, AgentCapability.APPROVALS)
        actionResponses.respond(
            active = active,
            locator = locator,
            requestId = requestId,
            decision = decision,
            answers = answers,
            additionalConfirmationGiven = additionalConfirmationGiven,
        )
    }

    suspend fun changedFiles(locator: SessionLocator): List<AgentChangedFile> {
        val active = controller(locator.connectionKey()).active(locator.agentProviderId)
        requireCapability(active, AgentCapability.FILE_CHANGES)
        return active.connection.changedFiles(locator.agentSessionId)
    }

    suspend fun shutdown() {
        refreshMutex.withLock {
            if (closed) {
                return
            }
            closed = true
            val current = stateMutex.withLock {
                val copy = controllers.values.toList()
                controllers.clear()
                mutableSnapshot.value = SessionCoordinatorSnapshot()
                copy
            }
            current.forEach { it.shutdown(disconnect = true) }
            coordinatorJob.cancelAndJoin()
            connectionRegistry.close()
        }
    }

    override suspend fun onConnectionState(
        key: SessionConnectionKey,
        state: ConnectionState,
    ) {
        stateMutex.withLock {
            if (key !in controllers || closed) {
                return
            }
            mutableSnapshot.value = mutableSnapshot.value.copy(
                connectionStates = mutableSnapshot.value.connectionStates + (key to state),
            )
        }
    }

    override suspend fun onEndpointStatus(status: AgentEndpointStatus) {
        stateMutex.withLock {
            if (status.key.connection !in controllers || closed) {
                return
            }
            mutableSnapshot.value = mutableSnapshot.value.copy(
                agentEndpoints = mutableSnapshot.value.agentEndpoints + (status.key to status),
            )
        }
    }

    override suspend fun onIssue(
        id: String,
        issue: SessionCoordinatorIssue?,
    ) {
        stateMutex.withLock {
            if (issue?.connection != null && issue.connection !in controllers) {
                return
            }
            if (closed) {
                return
            }
            val issues = if (issue == null) {
                mutableSnapshot.value.issues - id
            } else {
                mutableSnapshot.value.issues + (id to issue)
            }
            mutableSnapshot.value = mutableSnapshot.value.copy(issues = issues)
        }
    }

    private suspend fun reconcileControllers(
        profiles: List<ConnectionProfileSummary>,
        discoveryIssues: Map<String, SessionCoordinatorIssue>,
    ) {
        val profileByKey = profiles.associateBy { SessionConnectionKey(it.providerId, it.id) }
        val removed = stateMutex.withLock {
            val obsolete = controllers.filter { (key, controller) ->
                profileByKey[key] != controller.profile
            }
            obsolete.keys.forEach(controllers::remove)
            mutableSnapshot.value = mutableSnapshot.value.copy(
                profiles = profiles,
                connectionStates = mutableSnapshot.value.connectionStates.filterKeys(controllers::containsKey),
                agentEndpoints = mutableSnapshot.value.agentEndpoints.filterKeys {
                    it.connection in controllers
                },
                issues = mutableSnapshot.value.issues
                    .filterKeys { !it.startsWith(PROFILE_ISSUE_PREFIX) }
                    .filterValues { issue ->
                        issue.connection == null || issue.connection in controllers
                    }
                    .plus(discoveryIssues),
            )
            obsolete.values.toList()
        }
        removed.forEach { it.shutdown(disconnect = true) }

        profiles.forEach { profile ->
            val key = SessionConnectionKey(profile.providerId, profile.id)
            val alreadyPresent = stateMutex.withLock { key in controllers }
            if (alreadyPresent) {
                return@forEach
            }
            val controller = createController(profile)
            if (controller == null) {
                publishSetupFailure(profile)
            } else {
                stateMutex.withLock {
                    controllers[key] = controller
                    val initialEndpoints = agentRegistry.descriptors().associate { descriptor ->
                        val endpoint = AgentEndpointKey(key, descriptor.id)
                        endpoint to AgentEndpointStatus(
                            key = endpoint,
                            descriptor = descriptor,
                            phase = AgentEndpointPhase.OFFLINE,
                            updatedAtEpochMillis = now(),
                        )
                    }
                    mutableSnapshot.value = mutableSnapshot.value.copy(
                        connectionStates = mutableSnapshot.value.connectionStates +
                            (key to controllerInitialState()),
                        agentEndpoints = mutableSnapshot.value.agentEndpoints + initialEndpoints,
                        issues = mutableSnapshot.value.issues - setupIssueId(key),
                    )
                }
                controller.start()
            }
        }
    }

    private fun createController(profile: ConnectionProfileSummary): ProfileRuntimeController? =
        try {
            val managed = connectionRegistry.provider(profile.providerId).connection(profile.id)
            require(managed.providerId == profile.providerId && managed.profileId == profile.id) {
                "Managed connection identity does not match its profile"
            }
            ProfileRuntimeController(
                profile = profile,
                managed = managed,
                agentRegistry = agentRegistry,
                repository = repository,
                parentScope = scope,
                clock = clock,
                listener = this,
            )
        } catch (_: Throwable) {
            null
        }

    private suspend fun publishSetupFailure(profile: ConnectionProfileSummary) {
        val key = SessionConnectionKey(profile.providerId, profile.id)
        val issueId = setupIssueId(key)
        val failure = ConnectionFailure(
            category = ConnectionFailureCategory.CONFIGURATION,
            code = "CONNECTION_SETUP_FAILED",
            actionableMessage = "Connection profile could not be prepared",
            recoverable = true,
        )
        stateMutex.withLock {
            mutableSnapshot.value = mutableSnapshot.value.copy(
                connectionStates = mutableSnapshot.value.connectionStates +
                    (key to ConnectionState.Failed(failure, now())),
                issues = mutableSnapshot.value.issues + (
                    issueId to SessionCoordinatorIssue(
                        id = issueId,
                        kind = SessionCoordinatorIssueKind.CONNECTION_SETUP,
                        connection = key,
                        agentProviderId = null,
                        actionableMessage = "Connection " + profile.label.take(256) + " could not be prepared",
                        recoverable = true,
                        occurredAtEpochMillis = now(),
                    )
                    ),
            )
        }
    }

    private fun controllerInitialState(): ConnectionState =
        ConnectionState.Disconnected(
            reason = ConnectionDisconnectReason.NOT_CONNECTED,
            atEpochMillis = now(),
        )

    private suspend fun controller(key: SessionConnectionKey): ProfileRuntimeController =
        stateMutex.withLock {
            check(!closed) { "Session coordinator is closed" }
            controllers[key] ?: throw NoSuchElementException("Connection profile is unavailable")
        }

    private suspend fun controllerSnapshot(): List<ProfileRuntimeController> =
        stateMutex.withLock { controllers.values.toList() }

    private suspend fun updateSnapshot(
        transform: (SessionCoordinatorSnapshot) -> SessionCoordinatorSnapshot,
    ) {
        stateMutex.withLock {
            mutableSnapshot.value = transform(mutableSnapshot.value)
        }
    }

    private fun requireCapability(
        active: ActiveAgentHandle,
        capability: AgentCapability,
    ) {
        require(capability in active.descriptor.capabilities) {
            active.descriptor.displayName + " does not support " + capability.name.lowercase()
        }
    }

    private fun SessionLocator.connectionKey() = SessionConnectionKey(
        providerId = connectionProviderId,
        profileId = connectionProfileId,
    )

    private fun profileIssueId(providerId: String): String = PROFILE_ISSUE_PREFIX + providerId

    private fun setupIssueId(key: SessionConnectionKey): String =
        "connection-setup:" + key.providerId.value + ":" + key.profileId.value

    private fun now(): Long = clock.epochMillis().coerceAtLeast(0L)

    private companion object {
        const val PROFILE_ISSUE_PREFIX = "profile-discovery:"
    }
}

class SessionActionDeliveryUncertainException :
    IllegalStateException(
        "The provider response could not be confirmed. Do not retry this request; wait for a newly identified provider request.",
    )

class SessionActionAuditFailureException :
    IllegalStateException(
        "The provider accepted the response, but its local audit state could not be confirmed.",
    )
