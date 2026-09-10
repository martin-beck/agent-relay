/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.clinecli

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

internal class ClineAgentConnection private constructor(
    override val descriptor: AgentProviderDescriptor,
    private val runtime: RemoteAgentRuntime,
    private val executable: String,
    private val clientStarter: ClineClientStarter,
    dispatcher: CoroutineDispatcher,
) : AgentProviderConnection {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableSessions = MutableStateFlow<List<AgentSession>>(emptyList())
    private val mutableEvents = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 256)
    private val clients = ConcurrentHashMap<AgentSessionId, ClineClient>()
    private val streamJobs = ConcurrentHashMap<AgentSessionId, Job>()
    private val promptJobs = ConcurrentHashMap<AgentSessionId, Job>()
    private val pendingApprovals = ConcurrentHashMap<AgentApprovalId, PendingClineApproval>()
    private val messagesPaths = ConcurrentHashMap<AgentSessionId, String>()
    private val fileCache =
        ConcurrentHashMap<AgentSessionId, LinkedHashMap<String, AgentChangedFile>>()
    private val clientMutex = Mutex()
    private val turnText = ConcurrentHashMap<AgentSessionId, StringBuilder>()
    private val sessionLock = Any()
    private val closed = AtomicBoolean()

    override val sessions: StateFlow<List<AgentSession>> = mutableSessions.asStateFlow()
    override val events: SharedFlow<AgentEvent> = mutableEvents.asSharedFlow()

    override suspend fun refreshSessions(): List<AgentSession> {
        check(!closed.get()) { "Cline connection is closed" }
        val historyRows = mutableListOf<JsonObject>()
        val seenIds = mutableSetOf<String>()
        for (page in 1..MAX_HISTORY_PAGES) {
            val result = runtime.execute(
                RemoteCommand(
                    executable,
                    listOf(
                        "history",
                        "--limit",
                        HISTORY_LIMIT.toString(),
                        "--page",
                        page.toString(),
                        "--json",
                    ),
                ),
                timeout = 30.seconds,
            )
            check(result.successful) {
                "Cline session discovery failed: " + result.standardError.trim()
            }
            val pageRows = (
                json.parseToJsonElement(result.standardOutput).clineArray()
                    ?: JsonArray(emptyList())
                ).mapNotNull { it.clineObject() }
            val added = pageRows.filter { row ->
                row.string("sessionId")?.let(seenIds::add) == true
            }
            historyRows += added
            if (pageRows.size < HISTORY_LIMIT || added.isEmpty()) break
        }
        val discovered = historyRows.map(ClineSessionMapper::fromHistory)
        discovered.forEach { session ->
            session.metadata["cline.messagesPath"]?.let { messagesPaths[session.id] = it }
        }
        val current = mutableSessions.value.associateBy(AgentSession::id)
        val merged = buildMap<AgentSessionId, AgentSession> {
            discovered.forEach { item ->
                val active = current[item.id]?.takeIf { clients.containsKey(item.id) }
                put(
                    item.id,
                    if (active == null) {
                        item
                    } else {
                        active.copy(
                            title = item.title ?: active.title,
                            preview = item.preview.ifBlank { active.preview },
                            model = item.model ?: active.model,
                            createdAtEpochSeconds =
                            item.createdAtEpochSeconds ?: active.createdAtEpochSeconds,
                            updatedAtEpochSeconds =
                            item.updatedAtEpochSeconds ?: active.updatedAtEpochSeconds,
                            metadata = active.metadata + item.metadata,
                        )
                    },
                )
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
        val client = ensureClient(session)
        ensureStream(sessionId, client)
        return requireSession(sessionId)
    }

    override suspend fun transcript(sessionId: AgentSessionId): List<AgentTranscriptEntry> {
        val session = findSession(sessionId)
        val path = messagesPaths[sessionId]
            ?: session.metadata["cline.messagesPath"]
            ?: return emptyList()
        val result = runtime.execute(
            RemoteCommand("python3", listOf("-c", READ_TRANSCRIPT_SCRIPT, path)),
            timeout = 30.seconds,
        )
        check(result.successful) { "Cline transcript read failed: " + result.standardError.trim() }
        val document = json.parseToJsonElement(result.standardOutput).clineObject()
            ?: return emptyList()
        return ClineTranscriptMapper.fromDocument(sessionId, document)
    }

    override suspend fun startSession(options: StartSessionOptions): AgentSession {
        check(!closed.get()) { "Cline connection is closed" }
        val client = clientStarter.startNew(options)
        val id = AgentSessionId(client.sessionId)
        val session = AgentSession(
            id = id,
            providerId = CLINE_PROVIDER_ID,
            title = null,
            preview = "",
            workingDirectory = options.workingDirectory,
            model = client.currentModel ?: options.model,
            createdAtEpochSeconds = null,
            updatedAtEpochSeconds = null,
            state = AgentSessionState.IDLE,
            canAcceptInput = true,
            metadata = buildMap {
                options.providerOptions["provider"]?.let { put("cline.provider", it) }
            },
        )
        if (clients.putIfAbsent(id, client) != null) {
            client.close()
            error("Cline returned a duplicate session id: " + id.value)
        }
        upsertSession(session)
        ensureStream(id, client)
        return session
    }

    override suspend fun sendInput(sessionId: AgentSessionId, text: String) {
        require(text.isNotBlank()) { "Input must not be blank" }
        check(promptJobs[sessionId]?.isActive != true) { "Cline session already has an active turn" }
        val client = clientFor(sessionId)
        setState(sessionId, AgentSessionState.RUNNING)
        mutableEvents.emit(AgentEvent.SessionStateChanged(sessionId, AgentSessionState.RUNNING))
        turnText[sessionId] = StringBuilder()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = client.prompt(text)
                val reason = result.string("stopReason") ?: "end_turn"
                val successful = reason != "cancelled"
                turnText.remove(sessionId)
                    ?.toString()
                    ?.takeIf(String::isNotBlank)
                    ?.let {
                        mutableEvents.emit(
                            AgentEvent.MessageCompleted(
                                sessionId = sessionId,
                                turnId = null,
                                itemId = null,
                                channel = dev.agentrelay.provider.api.AgentMessageChannel.FINAL,
                                text = it,
                            ),
                        )
                    }
                mutableEvents.emit(
                    AgentEvent.TurnCompleted(
                        sessionId = sessionId,
                        turnId = null,
                        successful = successful,
                        errorMessage = if (successful) null else "Cline turn cancelled",
                    ),
                )
                setState(sessionId, AgentSessionState.IDLE)
                mutableEvents.emit(
                    AgentEvent.SessionStateChanged(sessionId, AgentSessionState.IDLE),
                )
                runCatching { refreshSessions() }
            } catch (error: Throwable) {
                setState(sessionId, AgentSessionState.FAILED)
                mutableEvents.emit(
                    AgentEvent.Error(
                        sessionId,
                        error.message ?: "Cline turn failed",
                        recoverable = true,
                    ),
                )
                mutableEvents.emit(
                    AgentEvent.SessionStateChanged(sessionId, AgentSessionState.FAILED),
                )
                mutableEvents.emit(
                    AgentEvent.TurnCompleted(
                        sessionId,
                        turnId = null,
                        successful = false,
                        errorMessage = error.message,
                    ),
                )
            } finally {
                turnText.remove(sessionId)
                currentCoroutineContext()[Job]?.let { promptJobs.remove(sessionId, it) }
            }
        }
        check(promptJobs.putIfAbsent(sessionId, job) == null) {
            job.cancel()
            "Cline session already has an active turn"
        }
        job.start()
    }

    override suspend fun steerActiveTurn(sessionId: AgentSessionId, text: String) {
        throw UnsupportedOperationException("Cline ACP does not expose active-turn steering")
    }

    override suspend fun interrupt(sessionId: AgentSessionId) {
        check(promptJobs[sessionId]?.isActive == true) { "Cline session has no active turn" }
        clientFor(sessionId).cancel()
    }

    override suspend fun respondToApproval(
        approvalId: AgentApprovalId,
        decision: AgentApprovalDecision,
        answers: Map<String, List<String>>,
    ) {
        require(answers.isEmpty()) { "Cline tool permissions do not accept question answers" }
        val pending = pendingApprovals[approvalId]
            ?: throw NoSuchElementException("No pending Cline approval " + approvalId.value)
        clientFor(pending.approval.sessionId).respondToPermission(
            pending.call,
            decision,
            pending.optionIds,
        )
        pendingApprovals.remove(approvalId, pending)
        setState(pending.approval.sessionId, AgentSessionState.RUNNING)
    }

    override suspend fun changedFiles(sessionId: AgentSessionId): List<AgentChangedFile> {
        val session = findSession(sessionId)
        val cached = fileCache[sessionId]?.let { synchronized(it) { it.values.toList() } }.orEmpty()
        val durable = ClineFileChangeMapper.fromTranscript(
            transcript(sessionId),
            session.workingDirectory,
        )
        return (cached + durable).associateBy(AgentChangedFile::remotePath).values.toList()
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        promptJobs.values.forEach(Job::cancel)
        streamJobs.values.forEach(Job::cancel)
        clients.values.toSet().forEach { runCatching { it.close() } }
        promptJobs.clear()
        streamJobs.clear()
        clients.clear()
        pendingApprovals.clear()
        fileCache.clear()
        scope.cancel()
    }

    private suspend fun findSession(sessionId: AgentSessionId): AgentSession =
        mutableSessions.value.firstOrNull { it.id == sessionId }
            ?: refreshSessions().firstOrNull { it.id == sessionId }
            ?: throw NoSuchElementException("No Cline session " + sessionId.value)

    private suspend fun ensureClient(session: AgentSession): ClineClient = clientMutex.withLock {
        clients[session.id] ?: clientStarter.resume(session).also { clients[session.id] = it }
    }

    private suspend fun clientFor(sessionId: AgentSessionId): ClineClient {
        clients[sessionId]?.let { return it }
        attach(sessionId)
        return checkNotNull(clients[sessionId])
    }

    private fun ensureStream(sessionId: AgentSessionId, client: ClineClient) {
        if (streamJobs.containsKey(sessionId)) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                client.calls.collect { handleCall(sessionId, client, it) }
            } finally {
                currentCoroutineContext()[Job]?.let { streamJobs.remove(sessionId, it) }
            }
        }
        val existing = streamJobs.putIfAbsent(sessionId, job)
        if (existing == null) job.start() else job.cancel()
    }

    private suspend fun handleCall(
        sessionId: AgentSessionId,
        client: ClineClient,
        call: ClineAcpCall,
    ) {
        val pending = ClineApprovalMapper.fromCall(call)
        if (pending != null) {
            pendingApprovals[pending.approval.id] = pending
            setState(sessionId, AgentSessionState.WAITING_FOR_APPROVAL)
            mutableEvents.emit(AgentEvent.ApprovalRequested(sessionId, pending.approval))
            return
        }
        if (call.id != null) {
            client.rejectUnsupported(call)
            return
        }
        val workingDirectory = requireSession(sessionId).workingDirectory
        ClineEventMapper.fromCall(sessionId, call, workingDirectory).forEach { event ->
            when (event) {
                is AgentEvent.FileChanged -> rememberFile(sessionId, event.file)
                is AgentEvent.SessionStateChanged -> setState(sessionId, event.state)
                is AgentEvent.TextDelta -> if (
                    event.channel == dev.agentrelay.provider.api.AgentMessageChannel.FINAL
                ) {
                    turnText[sessionId]?.let { synchronized(it) { it.append(event.text) } }
                }
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
            ?: throw NoSuchElementException("No Cline session " + sessionId.value)

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
                if (it.id == sessionId) ClineSessionMapper.withState(it, state) else it
            }
        }
    }

    companion object {
        suspend fun create(
            descriptor: AgentProviderDescriptor,
            runtime: RemoteAgentRuntime,
            executable: String,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): ClineAgentConnection {
            val starter = object : ClineClientStarter {
                override suspend fun startNew(options: StartSessionOptions): ClineClient =
                    ClineAcpClient.startNew(runtime, executable, options, dispatcher)

                override suspend fun resume(session: AgentSession): ClineClient =
                    ClineAcpClient.resume(runtime, executable, session, dispatcher)
            }
            return create(descriptor, runtime, executable, starter, dispatcher)
        }

        internal suspend fun create(
            descriptor: AgentProviderDescriptor,
            runtime: RemoteAgentRuntime,
            executable: String,
            clientStarter: ClineClientStarter,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): ClineAgentConnection =
            ClineAgentConnection(descriptor, runtime, executable, clientStarter, dispatcher)
                .also { it.refreshSessions() }

        private const val HISTORY_LIMIT = 200
        private const val MAX_HISTORY_PAGES = 100

        private val READ_TRANSCRIPT_SCRIPT =
            """
            import json
            import pathlib
            import sys

            root = (pathlib.Path.home() / ".cline").resolve()
            path = pathlib.Path(sys.argv[1]).resolve()
            if root not in path.parents or path.suffix != ".json":
                raise SystemExit("Unsafe Cline transcript path")
            with path.open(errors="replace") as stream:
                document = json.load(stream)
            print(json.dumps(document))
            """.trimIndent()
    }
}
