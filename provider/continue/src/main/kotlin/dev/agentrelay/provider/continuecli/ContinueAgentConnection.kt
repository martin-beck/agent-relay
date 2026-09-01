package dev.agentrelay.provider.continuecli

import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentApprovalId
import dev.agentrelay.provider.api.AgentChangedFile
import dev.agentrelay.provider.api.AgentEvent
import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentProviderConnection
import dev.agentrelay.provider.api.AgentProviderDescriptor
import dev.agentrelay.provider.api.AgentSession
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.AgentToolStatus
import dev.agentrelay.provider.api.AgentTranscriptEntry
import dev.agentrelay.provider.api.AgentTranscriptRole
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.StartSessionOptions
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal class ContinueAgentConnection private constructor(
    override val descriptor: AgentProviderDescriptor,
    private val runtime: RemoteAgentRuntime,
    private val executable: String,
    private val clientStarter: ContinueClientStarter,
    dispatcher: CoroutineDispatcher,
) : AgentProviderConnection {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableSessions = MutableStateFlow<List<AgentSession>>(emptyList())
    private val mutableEvents = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 256)
    private val clients = ConcurrentHashMap<AgentSessionId, ContinueClient>()
    private val pollJobs = ConcurrentHashMap<AgentSessionId, Job>()
    private val pendingApprovals = ConcurrentHashMap<AgentApprovalId, AgentSessionId>()
    private val seenEntries = ConcurrentHashMap<AgentSessionId, MutableSet<String>>()
    private val clientMutex = Mutex()
    private val sessionLock = Any()
    private val closed = AtomicBoolean()

    override val sessions: StateFlow<List<AgentSession>> = mutableSessions.asStateFlow()
    override val events: SharedFlow<AgentEvent> = mutableEvents.asSharedFlow()

    override suspend fun refreshSessions(): List<AgentSession> {
        check(!closed.get()) { "Continue connection is closed" }
        val result = runtime.execute(
            RemoteCommand(executable, listOf("ls", "--json")),
            timeout = 30.seconds,
        )
        check(result.successful) {
            val details = result.standardError.trim().ifEmpty { result.standardOutput.trim() }
            "Continue session discovery failed: $details"
        }
        val root = json.parseToJsonElement(result.standardOutput).objectOrNull()
            ?: error("Continue session listing returned a non-object result")
        val current = mutableSessions.value.associateBy(AgentSession::id)
        val listed = root.arrayValue("sessions").orEmpty().mapNotNull { element ->
            element.objectOrNull()?.let(ContinueSessionMapper::fromListing)
        }
        val merged = buildMap<AgentSessionId, AgentSession> {
            listed.forEach { listing ->
                val active = current[listing.id]?.takeIf { clients.containsKey(listing.id) }
                put(
                    listing.id,
                    active?.copy(
                        title = listing.title ?: active.title,
                        preview = listing.preview.ifBlank { active.preview },
                        workingDirectory = listing.workingDirectory ?: active.workingDirectory,
                        createdAtEpochSeconds = listing.createdAtEpochSeconds
                            ?: active.createdAtEpochSeconds,
                        updatedAtEpochSeconds = listing.updatedAtEpochSeconds
                            ?: active.updatedAtEpochSeconds,
                    ) ?: listing,
                )
            }
            current.values.filter { clients.containsKey(it.id) }.forEach { putIfAbsent(it.id, it) }
        }.values.sortedByDescending {
            it.updatedAtEpochSeconds ?: it.createdAtEpochSeconds ?: 0
        }
        synchronized(sessionLock) {
            mutableSessions.value = merged
        }
        return merged
    }

    override suspend fun attach(sessionId: AgentSessionId): AgentSession {
        val known = findSession(sessionId)
        val client = ensureClient(
            known,
            StartSessionOptions(workingDirectory = known.workingDirectory),
        )
        val state = client.state()
        seedTranscript(sessionId, state)
        handleState(sessionId, client, state, emitTranscript = false)
        ensurePolling(sessionId, client)
        return requireSession(sessionId)
    }

    override suspend fun transcript(sessionId: AgentSessionId): List<AgentTranscriptEntry> {
        val known = findSession(sessionId)
        val client = ensureClient(
            known,
            StartSessionOptions(workingDirectory = known.workingDirectory),
        )
        ensurePolling(sessionId, client)
        return ContinueTranscriptMapper.fromState(sessionId, client.state())
    }

    override suspend fun startSession(options: StartSessionOptions): AgentSession {
        check(!closed.get()) { "Continue connection is closed" }
        val id = AgentSessionId(UUID.randomUUID().toString())
        val provisional = AgentSession(
            id = id,
            providerId = CONTINUE_PROVIDER_ID,
            title = options.providerOptions["title"],
            preview = "",
            workingDirectory = options.workingDirectory,
            model = options.model,
            createdAtEpochSeconds = null,
            updatedAtEpochSeconds = null,
            state = AgentSessionState.NOT_LOADED,
            canAcceptInput = false,
        )
        upsertSession(provisional)
        val client = try {
            ensureClient(provisional, options)
        } catch (error: Throwable) {
            removeSession(id)
            throw error
        }
        val state = client.state()
        seedTranscript(id, state)
        handleState(id, client, state, emitTranscript = false)
        ensurePolling(id, client)
        return requireSession(id)
    }

    override suspend fun sendInput(sessionId: AgentSessionId, text: String) {
        require(text.isNotBlank()) { "Input must not be blank" }
        val client = clientFor(sessionId)
        client.sendMessage(text)
        updateSessionState(sessionId, AgentSessionState.RUNNING)
        mutableEvents.emit(
            AgentEvent.SessionStateChanged(sessionId, AgentSessionState.RUNNING),
        )
    }

    override suspend fun steerActiveTurn(sessionId: AgentSessionId, text: String) {
        throw UnsupportedOperationException(
            "Continue queues messages and does not expose active-turn steering",
        )
    }

    override suspend fun interrupt(sessionId: AgentSessionId) {
        val client = clientFor(sessionId)
        client.pause()
        handleState(sessionId, client, client.state())
    }

    override suspend fun respondToApproval(
        approvalId: AgentApprovalId,
        decision: AgentApprovalDecision,
        answers: Map<String, List<String>>,
    ) {
        require(answers.isEmpty()) { "Continue permissions do not accept question answers" }
        val sessionId = pendingApprovals[approvalId]
            ?: throw NoSuchElementException("No pending Continue approval " + approvalId.value)
        val approved = when (decision) {
            AgentApprovalDecision.APPROVE_ONCE -> true
            AgentApprovalDecision.DECLINE, AgentApprovalDecision.CANCEL -> false
            AgentApprovalDecision.APPROVE_FOR_SESSION ->
                throw IllegalArgumentException("Continue server permissions are one-shot")
            AgentApprovalDecision.SUBMIT ->
                throw IllegalArgumentException("Continue permissions do not support submit")
        }
        val client = clientFor(sessionId)
        client.respondToPermission(approvalId.value, approved)
        pendingApprovals.remove(approvalId, sessionId)
        handleState(sessionId, client, client.state())
    }

    override suspend fun changedFiles(sessionId: AgentSessionId): List<AgentChangedFile> {
        val session = requireSession(sessionId)
        val workspace = session.workingDirectory ?: return emptyList()
        return ContinueDiffMapper.fromPatch(clientFor(sessionId).diff(), workspace)
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        clients.values.toSet().forEach { client ->
            runCatching { client.close() }
        }
        clients.clear()
        pollJobs.clear()
        pendingApprovals.clear()
        seenEntries.clear()
    }

    private suspend fun findSession(sessionId: AgentSessionId): AgentSession =
        mutableSessions.value.firstOrNull { it.id == sessionId }
            ?: refreshSessions().firstOrNull { it.id == sessionId }
            ?: throw NoSuchElementException("No Continue session " + sessionId.value)

    private suspend fun ensureClient(
        session: AgentSession,
        options: StartSessionOptions,
    ): ContinueClient = clientMutex.withLock {
        clients[session.id] ?: clientStarter.start(
            session.id,
            session.workingDirectory,
            options,
        ).also { clients[session.id] = it }
    }

    private suspend fun clientFor(sessionId: AgentSessionId): ContinueClient {
        clients[sessionId]?.let { return it }
        attach(sessionId)
        return checkNotNull(clients[sessionId])
    }

    private fun ensurePolling(sessionId: AgentSessionId, client: ContinueClient) {
        if (pollJobs.containsKey(sessionId)) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (currentCoroutineContext().isActive) {
                    delay(POLL_INTERVAL)
                    try {
                        handleState(sessionId, client, client.state())
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        mutableEvents.emit(
                            AgentEvent.Error(
                                sessionId = sessionId,
                                message = "Continue state polling failed: " + error.message,
                                recoverable = true,
                            ),
                        )
                    }
                }
            } finally {
                currentCoroutineContext()[Job]?.let { pollJobs.remove(sessionId, it) }
            }
        }
        val existing = pollJobs.putIfAbsent(sessionId, job)
        if (existing == null) job.start() else job.cancel()
    }

    private suspend fun handleState(
        sessionId: AgentSessionId,
        client: ContinueClient,
        state: JsonObject,
        emitTranscript: Boolean = true,
    ) {
        val previous = mutableSessions.value.firstOrNull { it.id == sessionId }
        val mapped = ContinueSessionMapper.fromState(state, previous)
        check(mapped.id == sessionId) {
            "Continue server changed session identity from " + sessionId.value +
                " to " + mapped.id.value
        }
        upsertSession(mapped)
        if (previous != null && previous.state != mapped.state) {
            mutableEvents.emit(AgentEvent.SessionStateChanged(sessionId, mapped.state))
        }

        val approval = ContinueApprovalMapper.fromState(state)
        pendingApprovals.entries.removeIf { it.value == sessionId && it.key != approval?.id }
        if (approval != null && pendingApprovals.putIfAbsent(approval.id, sessionId) == null) {
            mutableEvents.emit(AgentEvent.ApprovalRequested(sessionId, approval))
        }

        val entries = ContinueTranscriptMapper.fromState(sessionId, state)
        val seen = seenEntries.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }
        if (emitTranscript) {
            entries.filter { seen.add(it.id) }.forEach { emitEntry(it) }
        } else {
            entries.forEach { seen.add(it.id) }
        }

        if (previous?.state == AgentSessionState.RUNNING &&
            mapped.state != AgentSessionState.RUNNING &&
            mapped.state != AgentSessionState.WAITING_FOR_APPROVAL
        ) {
            mutableEvents.emit(
                AgentEvent.TurnCompleted(
                    sessionId = sessionId,
                    turnId = null,
                    successful = mapped.state != AgentSessionState.FAILED,
                    errorMessage = null,
                ),
            )
            runCatching {
                val workspace = mapped.workingDirectory ?: return@runCatching emptyList()
                ContinueDiffMapper.fromPatch(client.diff(), workspace)
            }.getOrDefault(emptyList()).forEach {
                mutableEvents.emit(AgentEvent.FileChanged(sessionId, it))
            }
        }
    }

    private suspend fun emitEntry(entry: AgentTranscriptEntry) {
        when (entry.role) {
            AgentTranscriptRole.AGENT -> mutableEvents.emit(
                AgentEvent.MessageCompleted(
                    sessionId = entry.sessionId,
                    turnId = entry.turnId,
                    itemId = entry.id,
                    channel = entry.channel ?: AgentMessageChannel.FINAL,
                    text = entry.text,
                ),
            )
            AgentTranscriptRole.TOOL -> mutableEvents.emit(
                AgentEvent.ToolChanged(
                    sessionId = entry.sessionId,
                    turnId = entry.turnId,
                    itemId = entry.id,
                    toolName = entry.metadata["continue.tool"] ?: "Continue tool",
                    summary = entry.text,
                    status = entry.metadata["continue.toolStatus"]
                        ?.let { runCatching { AgentToolStatus.valueOf(it) }.getOrNull() }
                        ?: AgentToolStatus.COMPLETED,
                ),
            )
            else -> Unit
        }
    }

    private fun seedTranscript(sessionId: AgentSessionId, state: JsonObject) {
        val seen = seenEntries.computeIfAbsent(sessionId) { ConcurrentHashMap.newKeySet() }
        ContinueTranscriptMapper.fromState(sessionId, state).forEach { seen.add(it.id) }
    }

    private fun requireSession(sessionId: AgentSessionId): AgentSession =
        mutableSessions.value.firstOrNull { it.id == sessionId }
            ?: throw NoSuchElementException("No Continue session " + sessionId.value)

    private fun upsertSession(session: AgentSession) {
        synchronized(sessionLock) {
            mutableSessions.value =
                (mutableSessions.value.filterNot { it.id == session.id } + session)
                    .sortedByDescending {
                        it.updatedAtEpochSeconds ?: it.createdAtEpochSeconds ?: 0
                    }
        }
    }

    private fun updateSessionState(sessionId: AgentSessionId, state: AgentSessionState) {
        synchronized(sessionLock) {
            mutableSessions.value = mutableSessions.value.map { session ->
                if (session.id == sessionId) {
                    session.copy(
                        state = state,
                        canAcceptInput = state != AgentSessionState.RUNNING &&
                            state != AgentSessionState.WAITING_FOR_APPROVAL,
                    )
                } else {
                    session
                }
            }
        }
    }

    private fun removeSession(sessionId: AgentSessionId) {
        synchronized(sessionLock) {
            mutableSessions.value = mutableSessions.value.filterNot { it.id == sessionId }
        }
        clients.remove(sessionId)
        pollJobs.remove(sessionId)?.cancel()
        pendingApprovals.entries.removeIf { it.value == sessionId }
        seenEntries.remove(sessionId)
    }

    companion object {
        private val POLL_INTERVAL = 750.milliseconds

        suspend fun create(
            descriptor: AgentProviderDescriptor,
            runtime: RemoteAgentRuntime,
            executable: String,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): ContinueAgentConnection {
            val starter = ContinueClientStarter { sessionId, workingDirectory, options ->
                ContinueServerClient.start(
                    runtime,
                    executable,
                    sessionId,
                    workingDirectory,
                    options,
                    dispatcher,
                )
            }
            return create(descriptor, runtime, executable, starter, dispatcher)
        }

        internal suspend fun create(
            descriptor: AgentProviderDescriptor,
            runtime: RemoteAgentRuntime,
            executable: String,
            clientStarter: ContinueClientStarter,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): ContinueAgentConnection =
            ContinueAgentConnection(
                descriptor,
                runtime,
                executable,
                clientStarter,
                dispatcher,
            ).also { it.refreshSessions() }
    }
}
