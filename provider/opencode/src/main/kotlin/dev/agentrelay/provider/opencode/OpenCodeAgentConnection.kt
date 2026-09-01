package dev.agentrelay.provider.opencode

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
import dev.agentrelay.provider.api.StartSessionOptions
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class OpenCodeAgentConnection private constructor(
    override val descriptor: AgentProviderDescriptor,
    private val client: OpenCodeClient,
    dispatcher: CoroutineDispatcher,
) : AgentProviderConnection {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableSessions = MutableStateFlow<List<AgentSession>>(emptyList())
    private val mutableEvents = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 256)
    private val sessionDirectories = ConcurrentHashMap<AgentSessionId, String>()
    private val sessionModels = ConcurrentHashMap<AgentSessionId, String>()
    private val pendingApprovals = ConcurrentHashMap<AgentApprovalId, AgentSessionId>()
    private val changedFileCache =
        ConcurrentHashMap<AgentSessionId, LinkedHashMap<String, AgentChangedFile>>()
    private val eventJobs = ConcurrentHashMap<String, Job>()

    override val sessions: StateFlow<List<AgentSession>> = mutableSessions.asStateFlow()
    override val events: SharedFlow<AgentEvent> = mutableEvents.asSharedFlow()

    override suspend fun refreshSessions(): List<AgentSession> {
        val directories = discoverDirectories()
        val refreshed = linkedMapOf<AgentSessionId, AgentSession>()
        directories.forEach { directory ->
            val statuses = client.get("/session/status", directory).objectOrNull().orEmpty()
            client.get("/session", directory).arrayOrNull().orEmpty().forEach { element ->
                val raw = element.objectOrNull() ?: return@forEach
                val id = raw.string("id") ?: return@forEach
                val status = statuses[id]?.objectOrNull()?.string("type")
                val session = OpenCodeSessionMapper.fromJson(raw.withDirectory(directory), status)
                rememberSession(session, directory)
                refreshed[session.id] = session
            }
            ensureEvents(directory)
        }
        val result = refreshed.values.sortedByDescending {
            it.updatedAtEpochSeconds ?: it.createdAtEpochSeconds ?: 0
        }
        mutableSessions.value = result
        return result
    }

    override suspend fun attach(sessionId: AgentSessionId): AgentSession {
        val known = mutableSessions.value.firstOrNull { it.id == sessionId }
            ?: refreshSessions().firstOrNull { it.id == sessionId }
            ?: throw NoSuchElementException("No OpenCode session " + sessionId.value)
        val directory = directoryFor(sessionId) ?: known.workingDirectory
        val raw = client.get("/session/" + sessionId.value, directory).objectOrNull()
            ?: error("OpenCode session detail returned a non-object result")
        val current = mutableSessions.value.firstOrNull { it.id == sessionId }
        val session = OpenCodeSessionMapper.fromJson(
            raw.withDirectory(directory),
            current?.state.toWireStatus(),
        )
        rememberSession(session, directory)
        ensureEvents(directory)
        upsertSession(session)
        return session
    }

    override suspend fun transcript(sessionId: AgentSessionId): List<AgentTranscriptEntry> {
        val directory = requireDirectoryContext(sessionId)
        val messages = client.get("/session/" + sessionId.value + "/message", directory)
            .arrayOrNull()
            ?: JsonArray(emptyList())
        return OpenCodeTranscriptMapper.fromJson(sessionId, messages)
    }

    override suspend fun startSession(options: StartSessionOptions): AgentSession {
        val body = buildJsonObject {
            options.providerOptions["title"]?.let { put("title", it) }
        }
        val raw = client.post("/session", body, options.workingDirectory).objectOrNull()
            ?: error("OpenCode session creation returned a non-object result")
        val session = OpenCodeSessionMapper.fromJson(
            raw.withDirectory(options.workingDirectory),
            "idle",
        )
        rememberSession(session, options.workingDirectory)
        options.model?.let { sessionModels[session.id] = it }
        ensureEvents(options.workingDirectory)
        upsertSession(session)
        return session
    }

    override suspend fun sendInput(sessionId: AgentSessionId, text: String) {
        require(text.isNotBlank()) { "Input must not be blank" }
        if (mutableSessions.value.none { it.id == sessionId }) {
            attach(sessionId)
        }
        val directory = requireDirectoryContext(sessionId)
        client.post(
            "/session/" + sessionId.value + "/prompt_async",
            buildJsonObject {
                sessionModels[sessionId]?.toModelJson()?.let { put("model", it) }
                put(
                    "parts",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", text)
                            },
                        )
                    },
                )
            },
            directory,
        )
        updateSessionState(sessionId, AgentSessionState.RUNNING)
    }

    override suspend fun steerActiveTurn(sessionId: AgentSessionId, text: String) {
        throw UnsupportedOperationException("OpenCode does not expose active-turn steering")
    }

    override suspend fun interrupt(sessionId: AgentSessionId) {
        val directory = requireDirectoryContext(sessionId)
        client.post("/session/" + sessionId.value + "/abort", directory = directory)
        updateSessionState(sessionId, AgentSessionState.IDLE)
    }

    override suspend fun respondToApproval(
        approvalId: AgentApprovalId,
        decision: AgentApprovalDecision,
        answers: Map<String, List<String>>,
    ) {
        require(answers.isEmpty()) { "OpenCode permission requests do not accept question answers" }
        val sessionId = pendingApprovals[approvalId]
            ?: throw NoSuchElementException("No pending OpenCode approval " + approvalId.value)
        val response = when (decision) {
            AgentApprovalDecision.APPROVE_ONCE -> "once"
            AgentApprovalDecision.APPROVE_FOR_SESSION -> "always"
            AgentApprovalDecision.DECLINE, AgentApprovalDecision.CANCEL -> "reject"
            AgentApprovalDecision.SUBMIT ->
                throw IllegalArgumentException("OpenCode permission requests do not support submit")
        }
        client.post(
            "/session/" + sessionId.value + "/permissions/" + approvalId.value,
            buildJsonObject { put("response", response) },
            requireDirectoryContext(sessionId),
        )
        pendingApprovals.remove(approvalId, sessionId)
        updateSessionState(sessionId, AgentSessionState.RUNNING)
    }

    override suspend fun changedFiles(sessionId: AgentSessionId): List<AgentChangedFile> {
        val directory = requireDirectoryContext(sessionId)
        val files = OpenCodeDiffMapper.fromJson(
            client.get("/session/" + sessionId.value + "/diff", directory)
                .arrayOrNull()
                ?: JsonArray(emptyList()),
        )
        files.forEach { rememberFile(sessionId, it) }
        return files
    }

    override suspend fun close() {
        scope.cancel()
        client.close()
    }

    private suspend fun discoverDirectories(): List<String?> {
        val projects = client.get("/project").arrayOrNull().orEmpty()
        return buildList<String?> {
            add(null)
            projects.mapNotNullTo(this) { project ->
                project.objectOrNull()?.string("worktree")
            }
        }.distinct()
    }

    private suspend fun ensureEvents(directory: String?) {
        val key = directory.orEmpty()
        if (eventJobs.containsKey(key)) {
            return
        }
        val flow = client.events(directory)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                flow.catch { error ->
                    mutableSessions.value
                        .filter { directoryFor(it.id) == directory }
                        .forEach {
                            mutableEvents.emit(
                                AgentEvent.Error(
                                    sessionId = it.id,
                                    message = "OpenCode event stream stopped: " + error.message,
                                    recoverable = true,
                                ),
                            )
                        }
                }.collect(::handleEvent)
            } finally {
                currentCoroutineContext()[Job]?.let { eventJobs.remove(key, it) }
            }
        }
        val existing = eventJobs.putIfAbsent(key, job)
        if (existing == null) {
            job.start()
        } else {
            job.cancel()
        }
    }

    private suspend fun handleEvent(raw: JsonObject) {
        when (raw.string("type")) {
            "session.created", "session.updated" -> {
                val info = raw.objectValue("properties")?.objectValue("info")
                if (info != null) {
                    val id = info.string("id")?.let(::AgentSessionId)
                    val current = mutableSessions.value.firstOrNull { it.id == id }
                    val session = OpenCodeSessionMapper.fromJson(
                        info.withDirectory(id?.let(::directoryFor)),
                        current?.state.toWireStatus(),
                    )
                    rememberSession(session, session.workingDirectory)
                    upsertSession(session)
                }
            }
            "session.deleted" -> {
                val id = raw.objectValue("properties")?.objectValue("info")?.string("id")
                    ?: raw.objectValue("properties")?.string("sessionID")
                if (id != null) {
                    removeSession(AgentSessionId(id))
                }
            }
        }

        OpenCodeEventMapper.map(raw).forEach { event ->
            when (event) {
                is AgentEvent.ApprovalRequested -> {
                    pendingApprovals[event.approval.id] = event.sessionId
                    updateSessionState(event.sessionId, AgentSessionState.WAITING_FOR_APPROVAL)
                }
                is AgentEvent.FileChanged -> rememberFile(event.sessionId, event.file)
                is AgentEvent.SessionStateChanged -> updateSessionState(event.sessionId, event.state)
                else -> Unit
            }
            mutableEvents.emit(event)
        }
    }

    private fun rememberSession(session: AgentSession, fallbackDirectory: String?) {
        sessionDirectories[session.id] = session.workingDirectory ?: fallbackDirectory ?: DEFAULT_DIRECTORY
        session.model?.let { sessionModels[session.id] = it }
    }

    private fun rememberFile(sessionId: AgentSessionId, file: AgentChangedFile) {
        val files = changedFileCache.computeIfAbsent(sessionId) { linkedMapOf() }
        synchronized(files) {
            files[file.remotePath] = file
        }
    }

    private fun requireDirectoryContext(sessionId: AgentSessionId): String? {
        if (!sessionDirectories.containsKey(sessionId)) {
            throw NoSuchElementException("No OpenCode session context for " + sessionId.value)
        }
        return directoryFor(sessionId)
    }

    private fun directoryFor(sessionId: AgentSessionId): String? =
        sessionDirectories[sessionId]?.takeUnless { it == DEFAULT_DIRECTORY }

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

    private fun removeSession(sessionId: AgentSessionId) {
        mutableSessions.value = mutableSessions.value.filterNot { it.id == sessionId }
        sessionDirectories.remove(sessionId)
        sessionModels.remove(sessionId)
        changedFileCache.remove(sessionId)
        pendingApprovals.entries.removeIf { it.value == sessionId }
    }

    private fun JsonObject.withDirectory(directory: String?): JsonObject {
        if (string("directory") != null || directory == null) {
            return this
        }
        return buildJsonObject {
            this@withDirectory.forEach { (key, value) -> put(key, value) }
            put("directory", directory)
        }
    }

    private fun String.toModelJson(): JsonObject? {
        val separator = indexOf('/')
        if (separator <= 0 || separator == lastIndex) {
            return null
        }
        return buildJsonObject {
            put("providerID", substring(0, separator))
            put("modelID", substring(separator + 1))
        }
    }

    private fun AgentSessionState?.toWireStatus(): String? = when (this) {
        AgentSessionState.IDLE -> "idle"
        AgentSessionState.RUNNING -> "busy"
        AgentSessionState.WAITING_FOR_APPROVAL -> "waitingForApproval"
        AgentSessionState.FAILED -> "failed"
        else -> null
    }

    companion object {
        private const val DEFAULT_DIRECTORY = "<default>"

        suspend fun create(
            descriptor: AgentProviderDescriptor,
            client: OpenCodeClient,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): OpenCodeAgentConnection = OpenCodeAgentConnection(descriptor, client, dispatcher).also {
            it.refreshSessions()
        }
    }
}
