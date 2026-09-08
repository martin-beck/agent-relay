/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.aider

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
import dev.agentrelay.provider.api.AgentTranscriptEntry
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.StartSessionOptions
import java.time.Instant
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

internal class AiderAgentConnection private constructor(
    override val descriptor: AgentProviderDescriptor,
    private val runtime: RemoteAgentRuntime,
    private val interpreter: String,
    private val stateRoot: String,
    private val clientStarter: AiderClientStarter,
    dispatcher: CoroutineDispatcher,
) : AgentProviderConnection {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableSessions = MutableStateFlow<List<AgentSession>>(emptyList())
    private val mutableEvents = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 256)
    private val clients = ConcurrentHashMap<AgentSessionId, AiderClient>()
    private val promptJobs = ConcurrentHashMap<AgentSessionId, Job>()
    private val interrupted = ConcurrentHashMap.newKeySet<AgentSessionId>()
    private val fileCache =
        ConcurrentHashMap<AgentSessionId, LinkedHashMap<String, AgentChangedFile>>()
    private val clientMutex = Mutex()
    private val sessionLock = Any()
    private val closed = AtomicBoolean()

    override val sessions: StateFlow<List<AgentSession>> = mutableSessions.asStateFlow()
    override val events: SharedFlow<AgentEvent> = mutableEvents.asSharedFlow()

    override suspend fun refreshSessions(): List<AgentSession> {
        check(!closed.get()) { "Aider connection is closed" }
        val result = runtime.execute(
            RemoteCommand(
                program = "python3",
                arguments = listOf("-c", AIDER_DISCOVER_SCRIPT, stateRoot),
            ),
            timeout = 30.seconds,
        )
        check(result.successful) {
            "Aider session discovery failed: " + result.standardError.trim()
        }
        val rows = json.parseToJsonElement(result.standardOutput).aiderArray()
            ?: JsonArray(emptyList())
        val discovered = rows.mapNotNull { row ->
            row.aiderObject()?.let(AiderSessionMapper::fromState)
        }
        val current = mutableSessions.value.associateBy(AgentSession::id)
        val merged = discovered.map { item ->
            val active = current[item.id]?.takeIf { clients.containsKey(item.id) }
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
            }
        }.toMutableList()
        current.values.filter { clients.containsKey(it.id) }.forEach { active ->
            if (merged.none { it.id == active.id }) merged += active
        }
        val sorted = merged.sortedByDescending {
            it.updatedAtEpochSeconds ?: it.createdAtEpochSeconds ?: 0
        }
        synchronized(sessionLock) { mutableSessions.value = sorted }
        return sorted
    }

    override suspend fun attach(sessionId: AgentSessionId): AgentSession {
        val session = findSession(sessionId)
        ensureClient(session)
        return requireSession(sessionId)
    }

    override suspend fun transcript(sessionId: AgentSessionId): List<AgentTranscriptEntry> {
        val session = findSession(sessionId)
        val path = session.metadata["aider.chatHistoryPath"] ?: return emptyList()
        val result = runtime.execute(
            RemoteCommand(
                program = "python3",
                arguments = listOf("-c", AIDER_READ_TRANSCRIPT_SCRIPT, stateRoot, path),
            ),
            timeout = 30.seconds,
        )
        check(result.successful) {
            "Aider transcript read failed: " + result.standardError.trim()
        }
        val rows = json.parseToJsonElement(result.standardOutput).aiderArray()
            ?: return emptyList()
        return AiderTranscriptMapper.fromRows(sessionId, rows)
    }

    override suspend fun startSession(options: StartSessionOptions): AgentSession {
        check(!closed.get()) { "Aider connection is closed" }
        val id = AgentSessionId(UUID.randomUUID().toString())
        val client = clientStarter.startNew(id, options)
        val now = Instant.now().epochSecond
        val session = AgentSession(
            id = id,
            providerId = AIDER_PROVIDER_ID,
            title = options.workingDirectory
                ?.trimEnd('/')
                ?.substringAfterLast('/')
                ?.takeIf(String::isNotBlank),
            preview = "",
            workingDirectory = options.workingDirectory,
            model = client.currentModel ?: options.model,
            createdAtEpochSeconds = now,
            updatedAtEpochSeconds = now,
            state = AgentSessionState.IDLE,
            canAcceptInput = true,
            metadata = buildMap {
                put("aider.stateDirectory", client.stateDirectory)
                put("aider.chatHistoryPath", client.chatHistoryPath)
                put("aider.files", client.filesJson)
                options.providerOptions["config"]?.takeIf(String::isNotBlank)?.let {
                    put("aider.config", it)
                }
            },
        )
        if (clients.putIfAbsent(id, client) != null) {
            client.close()
            error("Aider returned a duplicate session id: " + id.value)
        }
        upsertSession(session)
        return session
    }

    override suspend fun sendInput(sessionId: AgentSessionId, text: String) {
        require(text.isNotBlank()) { "Input must not be blank" }
        check(promptJobs[sessionId]?.isActive != true) {
            "Aider session already has an active turn"
        }
        val client = clientFor(sessionId)
        setState(sessionId, AgentSessionState.RUNNING)
        mutableEvents.emit(AgentEvent.SessionStateChanged(sessionId, AgentSessionState.RUNNING))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = client.prompt(text)
                result.files.forEach { item ->
                    val file = AgentChangedFile(item.remotePath, item.kind)
                    rememberFile(sessionId, file)
                    mutableEvents.emit(AgentEvent.FileChanged(sessionId, file))
                }
                if (result.text.isNotBlank()) {
                    mutableEvents.emit(
                        AgentEvent.MessageCompleted(
                            sessionId = sessionId,
                            turnId = null,
                            itemId = null,
                            channel = AgentMessageChannel.FINAL,
                            text = result.text,
                        ),
                    )
                }
                updateAfterPrompt(sessionId, text)
                mutableEvents.emit(
                    AgentEvent.TurnCompleted(
                        sessionId = sessionId,
                        turnId = null,
                        successful = true,
                        errorMessage = null,
                    ),
                )
                setState(sessionId, AgentSessionState.IDLE)
                mutableEvents.emit(
                    AgentEvent.SessionStateChanged(sessionId, AgentSessionState.IDLE),
                )
                runCatching { refreshSessions() }
            } catch (error: Throwable) {
                val wasInterrupted = interrupted.remove(sessionId)
                val state = if (wasInterrupted) AgentSessionState.IDLE else AgentSessionState.FAILED
                setState(sessionId, state)
                if (!wasInterrupted) {
                    mutableEvents.emit(
                        AgentEvent.Error(
                            sessionId,
                            error.message ?: "Aider turn failed",
                            recoverable = true,
                        ),
                    )
                }
                mutableEvents.emit(
                    AgentEvent.TurnCompleted(
                        sessionId,
                        turnId = null,
                        successful = false,
                        errorMessage = if (wasInterrupted) {
                            "Aider turn interrupted"
                        } else {
                            error.message
                        },
                    ),
                )
                mutableEvents.emit(AgentEvent.SessionStateChanged(sessionId, state))
            } finally {
                currentCoroutineContext()[Job]?.let { promptJobs.remove(sessionId, it) }
            }
        }
        check(promptJobs.putIfAbsent(sessionId, job) == null) {
            job.cancel()
            "Aider session already has an active turn"
        }
        job.start()
    }

    override suspend fun steerActiveTurn(sessionId: AgentSessionId, text: String) {
        throw UnsupportedOperationException("Aider does not expose active-turn steering")
    }

    override suspend fun interrupt(sessionId: AgentSessionId) {
        check(promptJobs[sessionId]?.isActive == true) { "Aider session has no active turn" }
        interrupted += sessionId
        val client = clients.remove(sessionId)
            ?: throw NoSuchElementException("No active Aider process for " + sessionId.value)
        client.close()
    }

    override suspend fun respondToApproval(
        approvalId: AgentApprovalId,
        decision: AgentApprovalDecision,
        answers: Map<String, List<String>>,
    ) {
        throw UnsupportedOperationException(
            "Aider has no structured approval channel; confirmations are declined",
        )
    }

    override suspend fun changedFiles(sessionId: AgentSessionId): List<AgentChangedFile> {
        val session = findSession(sessionId)
        val cached = fileCache[sessionId]?.let { synchronized(it) { it.values.toList() } }.orEmpty()
        val durable = AiderFileChangeMapper.fromMetadata(session.metadata["aider.changedFiles"])
        return (cached + durable).associateBy(AgentChangedFile::remotePath).values.toList()
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        promptJobs.values.forEach(Job::cancel)
        clients.values.toSet().forEach { runCatching { it.close() } }
        promptJobs.clear()
        clients.clear()
        interrupted.clear()
        fileCache.clear()
        scope.cancel()
    }

    private suspend fun findSession(sessionId: AgentSessionId): AgentSession =
        mutableSessions.value.firstOrNull { it.id == sessionId }
            ?: refreshSessions().firstOrNull { it.id == sessionId }
            ?: throw NoSuchElementException("No Aider session " + sessionId.value)

    private suspend fun ensureClient(session: AgentSession): AiderClient = clientMutex.withLock {
        clients[session.id] ?: clientStarter.resume(session).also { clients[session.id] = it }
    }

    private suspend fun clientFor(sessionId: AgentSessionId): AiderClient {
        clients[sessionId]?.let { return it }
        return ensureClient(findSession(sessionId))
    }

    private fun rememberFile(sessionId: AgentSessionId, file: AgentChangedFile) {
        val files = fileCache.computeIfAbsent(sessionId) { linkedMapOf() }
        synchronized(files) { files[file.remotePath] = file }
    }

    private fun requireSession(sessionId: AgentSessionId): AgentSession =
        mutableSessions.value.firstOrNull { it.id == sessionId }
            ?: throw NoSuchElementException("No Aider session " + sessionId.value)

    private fun updateAfterPrompt(sessionId: AgentSessionId, text: String) {
        val preview = text.lineSequence().firstOrNull(String::isNotBlank)?.trim().orEmpty()
        synchronized(sessionLock) {
            mutableSessions.value = mutableSessions.value.map {
                if (it.id == sessionId) {
                    it.copy(
                        preview = preview.take(160),
                        updatedAtEpochSeconds = Instant.now().epochSecond,
                    )
                } else {
                    it
                }
            }
        }
    }

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
                if (it.id == sessionId) AiderSessionMapper.withState(it, state) else it
            }
        }
    }

    companion object {
        suspend fun create(
            descriptor: AgentProviderDescriptor,
            runtime: RemoteAgentRuntime,
            interpreter: String,
            stateRoot: String,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): AiderAgentConnection {
            val starter = object : AiderClientStarter {
                override suspend fun startNew(
                    sessionId: AgentSessionId,
                    options: StartSessionOptions,
                ): AiderClient =
                    AiderProcessClient.startNew(
                        runtime,
                        interpreter,
                        stateRoot,
                        sessionId,
                        options,
                        dispatcher,
                    )

                override suspend fun resume(session: AgentSession): AiderClient =
                    AiderProcessClient.resume(
                        runtime,
                        interpreter,
                        stateRoot,
                        session,
                        dispatcher,
                    )
            }
            return create(descriptor, runtime, interpreter, stateRoot, starter, dispatcher)
        }

        internal suspend fun create(
            descriptor: AgentProviderDescriptor,
            runtime: RemoteAgentRuntime,
            interpreter: String,
            stateRoot: String,
            clientStarter: AiderClientStarter,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): AiderAgentConnection =
            AiderAgentConnection(
                descriptor,
                runtime,
                interpreter,
                stateRoot,
                clientStarter,
                dispatcher,
            ).also { it.refreshSessions() }
    }
}
