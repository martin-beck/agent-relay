package dev.agentrelay.provider.claude

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
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.StartSessionOptions
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal class ClaudeAgentConnection private constructor(
    override val descriptor: AgentProviderDescriptor,
    private val runtime: RemoteAgentRuntime,
    private val executable: String,
    private val clientStarter: ClaudeClientStarter,
    dispatcher: CoroutineDispatcher,
) : AgentProviderConnection {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableSessions = MutableStateFlow<List<AgentSession>>(emptyList())
    private val mutableEvents = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 256)
    private val clients = ConcurrentHashMap<AgentSessionId, ClaudeClient>()
    private val streamJobs = ConcurrentHashMap<AgentSessionId, Job>()
    private val sessionFiles = ConcurrentHashMap<AgentSessionId, String>()
    private val approvalSessions = ConcurrentHashMap<AgentApprovalId, AgentSessionId>()
    private val fileCache =
        ConcurrentHashMap<AgentSessionId, LinkedHashMap<String, AgentChangedFile>>()
    private val clientMutex = Mutex()
    private val sessionLock = Any()
    private val closed = AtomicBoolean()

    override val sessions: StateFlow<List<AgentSession>> = mutableSessions.asStateFlow()
    override val events: SharedFlow<AgentEvent> = mutableEvents.asSharedFlow()

    override suspend fun refreshSessions(): List<AgentSession> {
        check(!closed.get()) { "Claude connection is closed" }
        val result = runtime.execute(
            RemoteCommand("python3", listOf("-c", DISCOVERY_SCRIPT)),
            timeout = 30.seconds,
        )
        check(result.successful) {
            "Claude session discovery failed: " + result.standardError.trim()
        }
        val discovered = json.parseToJsonElement(result.standardOutput).arrayOrNull().orEmpty()
            .mapNotNull { it.objectOrNull() }
            .map(ClaudeSessionMapper::fromDiscovery)
        discovered.forEach { session ->
            session.metadata["claude.sessionFile"]?.let { sessionFiles[session.id] = it }
        }
        val current = mutableSessions.value.associateBy(AgentSession::id)
        val merged = buildMap<AgentSessionId, AgentSession> {
            discovered.forEach { item ->
                val active = current[item.id]?.takeIf { clients.containsKey(item.id) }
                put(item.id, active ?: item)
            }
            current.values.filter { clients.containsKey(it.id) }.forEach { putIfAbsent(it.id, it) }
        }.values.sortedByDescending {
            it.updatedAtEpochSeconds ?: it.createdAtEpochSeconds ?: 0
        }
        synchronized(sessionLock) { mutableSessions.value = merged }
        return merged
    }

    override suspend fun attach(sessionId: AgentSessionId): AgentSession {
        val session = findSession(sessionId)
        val client = ensureClient(
            session,
            resume = true,
            options = StartSessionOptions(workingDirectory = session.workingDirectory),
        )
        ensureStream(sessionId, client)
        return session
    }

    override suspend fun transcript(sessionId: AgentSessionId): List<AgentTranscriptEntry> {
        findSession(sessionId)
        val file = sessionFiles[sessionId] ?: return emptyList()
        val result = runtime.execute(
            RemoteCommand("python3", listOf("-c", READ_TRANSCRIPT_SCRIPT, file)),
            timeout = 30.seconds,
        )
        check(result.successful) {
            "Claude transcript read failed: " + result.standardError.trim()
        }
        val rows = json.parseToJsonElement(result.standardOutput).arrayOrNull()
            ?: JsonArray(emptyList())
        return ClaudeTranscriptMapper.fromRows(sessionId, rows)
    }

    override suspend fun startSession(options: StartSessionOptions): AgentSession {
        check(!closed.get()) { "Claude connection is closed" }
        val id = AgentSessionId(UUID.randomUUID().toString())
        val session = AgentSession(
            id = id,
            providerId = CLAUDE_PROVIDER_ID,
            title = options.providerOptions["name"],
            preview = "",
            workingDirectory = options.workingDirectory,
            model = options.model,
            createdAtEpochSeconds = null,
            updatedAtEpochSeconds = null,
            state = AgentSessionState.IDLE,
            canAcceptInput = true,
        )
        upsertSession(session)
        val client = try {
            ensureClient(session, resume = false, options)
        } catch (error: Throwable) {
            removeSession(id)
            throw error
        }
        ensureStream(id, client)
        return session
    }

    override suspend fun sendInput(sessionId: AgentSessionId, text: String) {
        require(text.isNotBlank()) { "Input must not be blank" }
        val client = clientFor(sessionId)
        client.sendUserMessage(text)
        setState(sessionId, AgentSessionState.RUNNING)
        mutableEvents.emit(AgentEvent.SessionStateChanged(sessionId, AgentSessionState.RUNNING))
    }

    override suspend fun steerActiveTurn(sessionId: AgentSessionId, text: String) {
        throw UnsupportedOperationException(
            "Claude active-turn steering is not exposed until queued input semantics are proven",
        )
    }

    override suspend fun interrupt(sessionId: AgentSessionId) {
        clientFor(sessionId).interrupt()
        setState(sessionId, AgentSessionState.IDLE)
        mutableEvents.emit(AgentEvent.SessionStateChanged(sessionId, AgentSessionState.IDLE))
    }

    override suspend fun respondToApproval(
        approvalId: AgentApprovalId,
        decision: AgentApprovalDecision,
        answers: Map<String, List<String>>,
    ) {
        require(answers.isEmpty()) { "Claude tool permissions do not accept question answers" }
        val sessionId = approvalSessions[approvalId]
            ?: throw NoSuchElementException("No pending Claude approval " + approvalId.value)
        val (allowed, remember) = when (decision) {
            AgentApprovalDecision.APPROVE_ONCE -> true to false
            AgentApprovalDecision.APPROVE_FOR_SESSION -> true to true
            AgentApprovalDecision.DECLINE, AgentApprovalDecision.CANCEL -> false to false
            AgentApprovalDecision.SUBMIT ->
                throw IllegalArgumentException("Claude tool permissions do not support submit")
        }
        clientFor(sessionId).respondToPermission(approvalId.value, allowed, remember)
        approvalSessions.remove(approvalId, sessionId)
        setState(sessionId, AgentSessionState.RUNNING)
    }

    override suspend fun changedFiles(sessionId: AgentSessionId): List<AgentChangedFile> {
        val cached = fileCache[sessionId]?.let { synchronized(it) { it.values.toList() } }.orEmpty()
        val workingDirectory = findSession(sessionId).workingDirectory
        val durable = ClaudeFileChangeMapper.fromTranscript(transcript(sessionId), workingDirectory)
        return (cached + durable).associateBy(AgentChangedFile::remotePath).values.toList()
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        clients.values.toSet().forEach { runCatching { it.close() } }
        clients.clear()
        streamJobs.clear()
        approvalSessions.clear()
        fileCache.clear()
    }

    private suspend fun findSession(sessionId: AgentSessionId): AgentSession =
        mutableSessions.value.firstOrNull { it.id == sessionId }
            ?: refreshSessions().firstOrNull { it.id == sessionId }
            ?: throw NoSuchElementException("No Claude session " + sessionId.value)

    private suspend fun ensureClient(
        session: AgentSession,
        resume: Boolean,
        options: StartSessionOptions,
    ): ClaudeClient = clientMutex.withLock {
        clients[session.id] ?: clientStarter.start(
            session.id,
            resume,
            session.workingDirectory,
            options,
        ).also { clients[session.id] = it }
    }

    private suspend fun clientFor(sessionId: AgentSessionId): ClaudeClient {
        clients[sessionId]?.let { return it }
        attach(sessionId)
        return checkNotNull(clients[sessionId])
    }

    private fun ensureStream(sessionId: AgentSessionId, client: ClaudeClient) {
        if (streamJobs.containsKey(sessionId)) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                client.messages.collect { handleWire(sessionId, it) }
            } finally {
                currentCoroutineContext()[Job]?.let { streamJobs.remove(sessionId, it) }
            }
        }
        val existing = streamJobs.putIfAbsent(sessionId, job)
        if (existing == null) job.start() else job.cancel()
    }

    private suspend fun handleWire(sessionId: AgentSessionId, wire: JsonObject) {
        when (wire.string("type")) {
            "system" -> if (wire.string("subtype") == "init") {
                val current = requireSession(sessionId)
                upsertSession(
                    ClaudeSessionMapper.withState(
                        current,
                        current.state,
                        wire.string("cwd"),
                        wire.string("model"),
                    ),
                )
            }
            "control_request" -> {
                val approval = ClaudeApprovalMapper.fromControlRequest(sessionId, wire)
                if (approval != null) {
                    approvalSessions[approval.id] = sessionId
                    setState(sessionId, AgentSessionState.WAITING_FOR_APPROVAL)
                    mutableEvents.emit(AgentEvent.ApprovalRequested(sessionId, approval))
                }
            }
            "agent_relay_error" -> {
                setState(sessionId, AgentSessionState.FAILED)
                mutableEvents.emit(
                    AgentEvent.Error(
                        sessionId,
                        wire.string("message") ?: "Claude stream stopped",
                        recoverable = true,
                    ),
                )
            }
        }
        ClaudeEventMapper.fromWire(
            sessionId,
            wire,
            requireSession(sessionId).workingDirectory,
        ).forEach { event ->
            when (event) {
                is AgentEvent.FileChanged -> rememberFile(sessionId, event.file)
                is AgentEvent.SessionStateChanged -> setState(sessionId, event.state)
                else -> Unit
            }
            mutableEvents.emit(event)
        }
    }

    private fun rememberFile(sessionId: AgentSessionId, file: AgentChangedFile) {
        val files = fileCache.computeIfAbsent(sessionId) { linkedMapOf() }
        synchronized(files) { files[file.remotePath] = file }
    }

    private fun requireSession(sessionId: AgentSessionId): AgentSession =
        mutableSessions.value.firstOrNull { it.id == sessionId }
            ?: throw NoSuchElementException("No Claude session " + sessionId.value)

    private fun upsertSession(session: AgentSession) {
        synchronized(sessionLock) {
            mutableSessions.value =
                (mutableSessions.value.filterNot { it.id == session.id } + session)
                    .sortedByDescending {
                        it.updatedAtEpochSeconds ?: it.createdAtEpochSeconds ?: 0
                    }
        }
    }

    private fun setState(sessionId: AgentSessionId, state: AgentSessionState) {
        synchronized(sessionLock) {
            mutableSessions.value = mutableSessions.value.map {
                if (it.id == sessionId) ClaudeSessionMapper.withState(it, state) else it
            }
        }
    }

    private fun removeSession(sessionId: AgentSessionId) {
        synchronized(sessionLock) {
            mutableSessions.value = mutableSessions.value.filterNot { it.id == sessionId }
        }
        clients.remove(sessionId)
        streamJobs.remove(sessionId)?.cancel()
        approvalSessions.entries.removeIf { it.value == sessionId }
        fileCache.remove(sessionId)
    }

    companion object {
        suspend fun create(
            descriptor: AgentProviderDescriptor,
            runtime: RemoteAgentRuntime,
            executable: String,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): ClaudeAgentConnection {
            val starter = ClaudeClientStarter { id, resume, directory, options ->
                ClaudeStreamClient.start(
                    runtime,
                    executable,
                    id,
                    resume,
                    directory,
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
            clientStarter: ClaudeClientStarter,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): ClaudeAgentConnection =
            ClaudeAgentConnection(descriptor, runtime, executable, clientStarter, dispatcher)
                .also { it.refreshSessions() }

        private val DISCOVERY_SCRIPT =
            """
            import datetime
            import json
            import pathlib

            root = pathlib.Path.home() / ".claude" / "projects"
            sessions = []
            for path in root.glob("*/*.jsonl"):
                rows = []
                for line in path.open(errors="replace"):
                    try:
                        row = json.loads(line)
                    except Exception:
                        continue
                    if row.get("type") in ("user", "assistant") and not row.get("isSidechain"):
                        rows.append(row)
                if not rows:
                    continue
                def text(row):
                    content = (row.get("message") or {}).get("content", "")
                    if isinstance(content, str):
                        return content
                    return "".join(
                        block.get("text", "")
                        for block in content
                        if isinstance(block, dict) and block.get("type") == "text"
                    )
                timestamps = [r.get("timestamp") for r in rows if r.get("timestamp")]
                def epoch(value):
                    if not value:
                        return None
                    return int(datetime.datetime.fromisoformat(
                        value.replace("Z", "+00:00")
                    ).timestamp())
                assistants = [r for r in rows if r.get("type") == "assistant"]
                sessions.append({
                    "id": rows[0].get("sessionId") or path.stem,
                    "file": str(path),
                    "cwd": next((r.get("cwd") for r in rows if r.get("cwd")), None),
                    "firstText": next((text(r) for r in rows if r.get("type") == "user"), ""),
                    "lastText": text(rows[-1]),
                    "createdAt": epoch(timestamps[0]) if timestamps else int(path.stat().st_mtime),
                    "updatedAt": epoch(timestamps[-1]) if timestamps else int(path.stat().st_mtime),
                    "model": ((assistants[-1].get("message") or {}).get("model")
                              if assistants else None),
                    "version": next((r.get("version") for r in rows if r.get("version")), None),
                })
            print(json.dumps(sorted(
                sessions, key=lambda item: item["updatedAt"] or 0, reverse=True
            )))
            """.trimIndent()

        private val READ_TRANSCRIPT_SCRIPT =
            """
            import json
            import pathlib
            import sys

            root = (pathlib.Path.home() / ".claude" / "projects").resolve()
            path = pathlib.Path(sys.argv[1]).resolve()
            if root not in path.parents or path.suffix != ".jsonl":
                raise SystemExit("Unsafe Claude transcript path")
            rows = []
            for line in path.open(errors="replace"):
                try:
                    row = json.loads(line)
                except Exception:
                    continue
                if row.get("type") in ("user", "assistant") and not row.get("isSidechain"):
                    rows.append(row)
            print(json.dumps(rows))
            """.trimIndent()
    }
}
