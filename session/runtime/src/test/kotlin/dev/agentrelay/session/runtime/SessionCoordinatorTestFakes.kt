package dev.agentrelay.session.runtime

import dev.agentrelay.connection.api.ConnectionCapability
import dev.agentrelay.connection.api.ConnectionChallengeId
import dev.agentrelay.connection.api.ConnectionDiagnosticSnapshot
import dev.agentrelay.connection.api.ConnectionDisconnectReason
import dev.agentrelay.connection.api.ConnectionFailure
import dev.agentrelay.connection.api.ConnectionFailureCategory
import dev.agentrelay.connection.api.ConnectionIdentityDecision
import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProfileSummary
import dev.agentrelay.connection.api.ConnectionProvider
import dev.agentrelay.connection.api.ConnectionProviderDescriptor
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.connection.api.ConnectionState
import dev.agentrelay.connection.api.ManagedConnection
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentApprovalId
import dev.agentrelay.provider.api.AgentCapability
import dev.agentrelay.provider.api.AgentChangedFile
import dev.agentrelay.provider.api.AgentEvent
import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentProviderConnection
import dev.agentrelay.provider.api.AgentProviderDescriptor
import dev.agentrelay.provider.api.AgentProviderFactory
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSession
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.AgentTranscriptEntry
import dev.agentrelay.provider.api.AgentTranscriptRole
import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import dev.agentrelay.provider.api.StartSessionOptions
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal class FakeConnectionProvider(
    providerId: String,
    profileId: String,
    label: String,
    initialRuntime: FakeRuntime,
    var failProfileDiscovery: Boolean = false,
) : ConnectionProvider {
    override val descriptor = ConnectionProviderDescriptor(
        id = ConnectionProviderId(providerId),
        displayName = label,
        providerVersion = "1.0.0",
        capabilities = setOf(ConnectionCapability.MULTIPLEXED_PROCESSES),
    )
    val summary = ConnectionProfileSummary(
        id = ConnectionProfileId(profileId),
        providerId = descriptor.id,
        label = label,
        target = label + " target",
        authenticationLabel = null,
    )
    val managed = FakeManagedConnection(summary, initialRuntime)
    var closeCount = 0

    override suspend fun profiles(): List<ConnectionProfileSummary> {
        check(!failProfileDiscovery) { "Injected profile discovery failure" }
        return listOf(summary)
    }

    override fun connection(profileId: ConnectionProfileId): ManagedConnection {
        require(profileId == summary.id)
        return managed
    }

    override fun close() {
        closeCount += 1
    }
}

internal class FakeManagedConnection(
    private val profile: ConnectionProfileSummary,
    private var reconnectRuntime: FakeRuntime,
) : ManagedConnection {
    override val providerId = profile.providerId
    override val profileId = profile.id
    private val mutableState = MutableStateFlow<ConnectionState>(
        ConnectionState.Disconnected(ConnectionDisconnectReason.NOT_CONNECTED, 0L),
    )
    override val state: StateFlow<ConnectionState> = mutableState
    private var runtime: FakeRuntime? = null
    private var timestamp = 1L

    override fun runtimeOrNull(): RemoteAgentRuntime? = runtime

    override fun connect() {
        runtime = reconnectRuntime
        mutableState.value = ConnectionState.Connected(timestamp++, null, null)
    }

    fun loseConnection() {
        runtime = null
        mutableState.value = ConnectionState.Reconnecting(
            attempt = 1,
            delay = 1.seconds,
            retryAtEpochMillis = timestamp++,
            lastFailure = ConnectionFailure(
                category = ConnectionFailureCategory.NETWORK,
                code = "NETWORK_LOST",
                actionableMessage = "Test connection lost",
                recoverable = true,
            ),
        )
    }

    fun reconnectWith(replacement: FakeRuntime) {
        reconnectRuntime = replacement
        runtime = replacement
        mutableState.value = ConnectionState.Connected(timestamp++, null, null)
    }

    override suspend fun resolveIdentityChallenge(
        challengeId: ConnectionChallengeId,
        decision: ConnectionIdentityDecision,
    ): Boolean = false

    override suspend fun disconnect() {
        runtime = null
        mutableState.value = ConnectionState.Disconnected(
            ConnectionDisconnectReason.USER_REQUESTED,
            timestamp++,
        )
    }

    override suspend fun suspendForBackground() {
        runtime = null
        mutableState.value = ConnectionState.Disconnected(
            ConnectionDisconnectReason.BACKGROUND_SUSPENDED,
            timestamp++,
        )
    }

    override fun resumeFromBackground() = connect()

    override fun diagnosticSnapshot() = ConnectionDiagnosticSnapshot(
        providerId = providerId,
        profileId = profileId,
        target = profile.target,
        state = state.value,
    )
}

internal data class FakeRuntime(override val hostId: String) : RemoteAgentRuntime {
    override suspend fun execute(command: RemoteCommand, timeout: Duration): RemoteCommandResult =
        error("Fake agent providers do not execute commands")

    override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess =
        error("Fake agent providers do not open processes")
}

internal class FakeAgentFactory(
    val providerId: AgentProviderId = AgentProviderId("test.agent"),
) : AgentProviderFactory {
    override val descriptor = AgentProviderDescriptor(
        id = providerId,
        displayName = "Test Agent",
        providerVersion = "1.0.0",
        capabilities = setOf(
            AgentCapability.SESSION_DISCOVERY,
            AgentCapability.SESSION_START,
            AgentCapability.SESSION_RESUME,
            AgentCapability.SESSION_HISTORY,
            AgentCapability.LIVE_STREAMING,
            AgentCapability.ACTIVE_TURN_STEERING,
            AgentCapability.TURN_INTERRUPT,
            AgentCapability.APPROVALS,
            AgentCapability.FILE_CHANGES,
        ),
    )
    val connections = mutableListOf<FakeAgentConnection>()
    var readiness: ProviderReadiness = ProviderReadiness.Ready("1.0.0")

    override suspend fun probe(runtime: RemoteAgentRuntime): ProviderReadiness = readiness

    override suspend fun connect(runtime: RemoteAgentRuntime): AgentProviderConnection {
        check(readiness is ProviderReadiness.Ready)
        return FakeAgentConnection(
            hostId = runtime.hostId,
            descriptor = descriptor,
            initialSessions = listOf(session(runtime.hostId, "shared-session")),
        ).also(connections::add)
    }

    fun latest(hostId: String): FakeAgentConnection =
        connections.last { it.hostId == hostId }

    fun session(hostId: String, id: String) = AgentSession(
        id = AgentSessionId(id),
        providerId = providerId,
        title = "Session on " + hostId,
        preview = "Cached output from " + hostId,
        workingDirectory = "/workspace/" + hostId,
        model = "test-model",
        createdAtEpochSeconds = 1L,
        updatedAtEpochSeconds = 2L,
        state = AgentSessionState.IDLE,
        canAcceptInput = true,
        metadata = mapOf("host" to hostId),
    )
}

internal class FakeAgentConnection(
    val hostId: String,
    override val descriptor: AgentProviderDescriptor,
    initialSessions: List<AgentSession>,
) : AgentProviderConnection {
    private val mutableSessions = MutableStateFlow(initialSessions)
    private val mutableEvents = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 16)
    override val sessions: StateFlow<List<AgentSession>> = mutableSessions
    override val events: SharedFlow<AgentEvent> = mutableEvents

    var closeCount = 0
    val sentInputs = mutableListOf<Pair<AgentSessionId, String>>()
    val steeredInputs = mutableListOf<Pair<AgentSessionId, String>>()
    val interrupted = mutableListOf<AgentSessionId>()
    val approvalResponses = mutableListOf<Triple<AgentApprovalId, AgentApprovalDecision, Map<String, List<String>>>>()

    override suspend fun refreshSessions(): List<AgentSession> = sessions.value

    override suspend fun attach(sessionId: AgentSessionId): AgentSession =
        sessions.value.single { it.id == sessionId }

    override suspend fun transcript(sessionId: AgentSessionId): List<AgentTranscriptEntry> = listOf(
        AgentTranscriptEntry(
            id = "cached:" + hostId + ":" + sessionId.value,
            sessionId = sessionId,
            turnId = null,
            role = AgentTranscriptRole.AGENT,
            channel = AgentMessageChannel.FINAL,
            text = "Transcript from " + hostId,
            createdAtEpochSeconds = 2L,
        ),
        AgentTranscriptEntry(
            id = "foreign-row",
            sessionId = AgentSessionId("another-session"),
            turnId = null,
            role = AgentTranscriptRole.AGENT,
            channel = AgentMessageChannel.FINAL,
            text = "Must not leak across sessions",
            createdAtEpochSeconds = 2L,
        ),
    )

    override suspend fun startSession(options: StartSessionOptions): AgentSession {
        val session = AgentSession(
            id = AgentSessionId("started-" + (sessions.value.size + 1)),
            providerId = descriptor.id,
            title = "Started session",
            preview = "",
            workingDirectory = options.workingDirectory,
            model = options.model,
            createdAtEpochSeconds = 3L,
            updatedAtEpochSeconds = 3L,
            state = AgentSessionState.IDLE,
            canAcceptInput = true,
        )
        mutableSessions.value = sessions.value + session
        return session
    }

    override suspend fun sendInput(sessionId: AgentSessionId, text: String) {
        sentInputs += sessionId to text
    }

    override suspend fun steerActiveTurn(sessionId: AgentSessionId, text: String) {
        steeredInputs += sessionId to text
    }

    override suspend fun interrupt(sessionId: AgentSessionId) {
        interrupted += sessionId
    }

    override suspend fun respondToApproval(
        approvalId: AgentApprovalId,
        decision: AgentApprovalDecision,
        answers: Map<String, List<String>>,
    ) {
        approvalResponses += Triple(approvalId, decision, answers)
    }

    override suspend fun changedFiles(sessionId: AgentSessionId): List<AgentChangedFile> = listOf(
        AgentChangedFile(
            remotePath = "/workspace/" + hostId + "/result.txt",
            kind = AgentFileChangeKind.MODIFIED,
        ),
    )

    suspend fun emit(event: AgentEvent) {
        mutableEvents.emit(event)
    }

    override suspend fun close() {
        closeCount += 1
    }
}

internal class TickingCoordinatorClock(
    private var value: Long = 100L,
) : SessionCoordinatorClock {
    override fun epochMillis(): Long = value++
}
