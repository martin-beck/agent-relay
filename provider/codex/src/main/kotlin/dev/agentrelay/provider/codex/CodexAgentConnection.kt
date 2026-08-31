package dev.agentrelay.provider.codex

import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentApprovalId
import dev.agentrelay.provider.api.AgentChangedFile
import dev.agentrelay.provider.api.AgentEvent
import dev.agentrelay.provider.api.AgentProviderConnection
import dev.agentrelay.provider.api.AgentProviderDescriptor
import dev.agentrelay.provider.api.AgentSession
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.AgentTranscriptEntry
import dev.agentrelay.provider.api.AgentTurnId
import dev.agentrelay.provider.api.StartSessionOptions
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class CodexAgentConnection private constructor(
    override val descriptor: AgentProviderDescriptor,
    private val peer: JsonRpcClient,
    dispatcher: CoroutineDispatcher,
) : AgentProviderConnection {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val client = CodexThreadClient(peer)
    private val mutableSessions = MutableStateFlow<List<AgentSession>>(emptyList())
    private val mutableEvents = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 256)
    private val attachedSessions = ConcurrentHashMap.newKeySet<AgentSessionId>()
    private val activeTurns = ConcurrentHashMap<AgentSessionId, AgentTurnId>()
    private val transcriptCache = ConcurrentHashMap<AgentSessionId, List<AgentTranscriptEntry>>()
    private val pendingApprovals = ConcurrentHashMap<AgentApprovalId, PendingCodexApproval>()
    private val changedFileCache =
        ConcurrentHashMap<AgentSessionId, LinkedHashMap<String, AgentChangedFile>>()

    override val sessions: StateFlow<List<AgentSession>> = mutableSessions.asStateFlow()
    override val events: SharedFlow<AgentEvent> = mutableEvents.asSharedFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            peer.calls.collect(::handleCall)
        }
    }

    override suspend fun refreshSessions(): List<AgentSession> {
        val refreshed = client.listSessions()
        mutableSessions.value = refreshed
        return refreshed
    }

    override suspend fun attach(sessionId: AgentSessionId): AgentSession {
        val thread = client.resume(sessionId)
        val session = CodexSessionMapper.fromThread(thread)
        attachedSessions += sessionId
        transcriptCache[sessionId] = CodexTranscriptMapper.fromThread(thread)
        upsertSession(session)
        return session
    }

    override suspend fun transcript(sessionId: AgentSessionId): List<AgentTranscriptEntry> =
        transcriptCache[sessionId] ?: client.transcript(sessionId).also {
            transcriptCache[sessionId] = it
        }

    override suspend fun startSession(options: StartSessionOptions): AgentSession {
        val thread = client.start(options)
        val session = CodexSessionMapper.fromThread(thread)
        attachedSessions += session.id
        transcriptCache[session.id] = CodexTranscriptMapper.fromThread(thread)
        upsertSession(session)
        return session
    }

    override suspend fun sendInput(sessionId: AgentSessionId, text: String) {
        if (sessionId !in attachedSessions) {
            attach(sessionId)
        }
        val turnId = client.startTurn(sessionId, text)
        activeTurns[sessionId] = turnId
        updateSessionState(sessionId, AgentSessionState.RUNNING)
    }

    override suspend fun steerActiveTurn(sessionId: AgentSessionId, text: String) {
        val turnId = activeTurns[sessionId]
            ?: throw IllegalStateException("Session has no active turn")
        client.steer(sessionId, turnId, text)
    }

    override suspend fun interrupt(sessionId: AgentSessionId) {
        val turnId = activeTurns[sessionId]
            ?: throw IllegalStateException("Session has no active turn")
        client.interrupt(sessionId, turnId)
    }

    override suspend fun respondToApproval(
        approvalId: AgentApprovalId,
        decision: AgentApprovalDecision,
        answers: Map<String, List<String>>,
    ) {
        val pending = pendingApprovals[approvalId]
            ?: throw NoSuchElementException("No pending approval " + approvalId.value)
        peer.respond(
            pending.rpcId,
            CodexApprovalMapper.response(pending, decision, answers),
        )
        pendingApprovals.remove(approvalId, pending)
    }

    override suspend fun changedFiles(sessionId: AgentSessionId): List<AgentChangedFile> {
        val files = changedFileCache[sessionId] ?: return emptyList()
        return synchronized(files) { files.values.toList() }
    }

    override suspend fun close() {
        peer.close()
        scope.cancel()
    }

    private suspend fun initialize() {
        peer.request(
            "initialize",
            buildJsonObject {
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", "agent_relay")
                        put("title", "Agent Relay")
                        put("version", descriptor.providerVersion)
                    },
                )
                put(
                    "capabilities",
                    buildJsonObject {
                        put("experimentalApi", true)
                        put("mcpServerOpenaiFormElicitation", false)
                    },
                )
            },
        )
        peer.notify("initialized")
    }

    private suspend fun handleCall(call: JsonRpcCall) {
        CodexApprovalMapper.fromCall(call)?.let { pending ->
            pendingApprovals[pending.approval.id] = pending
            updateSessionState(pending.approval.sessionId, AgentSessionState.WAITING_FOR_APPROVAL)
            mutableEvents.emit(
                AgentEvent.ApprovalRequested(
                    sessionId = pending.approval.sessionId,
                    approval = pending.approval,
                ),
            )
            return
        }

        val params = call.params.objectOrNull()
        val sessionId = params?.string("threadId")?.let(::AgentSessionId)
        when (call.method) {
            "thread/started", "thread/name/updated" -> {
                params?.objectValue("thread")?.let(CodexSessionMapper::fromThread)?.let(::upsertSession)
            }
            "turn/started" -> {
                val turnId = params?.objectValue("turn")?.string("id")
                    ?: params?.string("turnId")
                if (sessionId != null && turnId != null) {
                    activeTurns[sessionId] = AgentTurnId(turnId)
                }
            }
            "turn/completed" -> if (sessionId != null) {
                activeTurns.remove(sessionId)
            }
            "serverRequest/resolved" -> {
                val requestId = params?.string("requestId")
                if (requestId != null) {
                    pendingApprovals.remove(AgentApprovalId(requestId))
                }
            }
            "error" -> if (sessionId != null) {
                val message = params.objectValue("error")?.string("message") ?: "Codex turn failed"
                mutableEvents.emit(AgentEvent.Error(sessionId, message, recoverable = true))
            }
        }

        CodexEventMapper.map(call).forEach { event ->
            when (event) {
                is AgentEvent.FileChanged -> rememberFile(event.sessionId, event.file)
                is AgentEvent.SessionStateChanged -> updateSessionState(event.sessionId, event.state)
                else -> Unit
            }
            mutableEvents.emit(event)
        }
    }

    private fun rememberFile(sessionId: AgentSessionId, file: AgentChangedFile) {
        val files = changedFileCache.computeIfAbsent(sessionId) { linkedMapOf() }
        synchronized(files) {
            files[file.remotePath] = file
        }
    }

    private fun upsertSession(session: AgentSession) {
        mutableSessions.value = (mutableSessions.value.filterNot { it.id == session.id } + session)
            .sortedByDescending { it.updatedAtEpochSeconds ?: it.createdAtEpochSeconds ?: 0 }
    }

    private fun updateSessionState(sessionId: AgentSessionId, state: AgentSessionState) {
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

    companion object {
        suspend fun create(
            descriptor: AgentProviderDescriptor,
            peer: JsonRpcClient,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): CodexAgentConnection = CodexAgentConnection(descriptor, peer, dispatcher).also {
            it.initialize()
            it.refreshSessions()
        }
    }
}
