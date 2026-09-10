/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.workbuddy

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
import dev.agentrelay.provider.api.StartSessionOptions
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

internal class WorkBuddyAgentConnection(
    override val descriptor: AgentProviderDescriptor,
    private val client: WorkBuddyClient,
) : AgentProviderConnection {
    private val mutableSessions = MutableStateFlow<List<AgentSession>>(emptyList())
    private val mutableEvents = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 8)
    private var closed = false

    override val sessions: StateFlow<List<AgentSession>> = mutableSessions.asStateFlow()
    override val events: SharedFlow<AgentEvent> = mutableEvents.asSharedFlow()

    override suspend fun refreshSessions(): List<AgentSession> {
        ensureOpen()
        val updated = when (val status = client.onlineStatus()) {
            is WorkBuddyOnlineResult.Available -> listOf(session(status.online))
            WorkBuddyOnlineResult.AuthenticationRequired -> error("WorkBuddy authentication is required")
            WorkBuddyOnlineResult.AuthorizationDenied ->
                error("WorkBuddy Local Assistant permission is unavailable")
            WorkBuddyOnlineResult.Unavailable -> error("WorkBuddy Local Assistant is unavailable")
        }
        mutableSessions.value = updated
        return updated
    }

    override suspend fun attach(sessionId: AgentSessionId): AgentSession {
        requireLocalAssistant(sessionId)
        return refreshSessions().single()
    }

    override suspend fun transcript(sessionId: AgentSessionId): List<AgentTranscriptEntry> {
        ensureOpen()
        requireLocalAssistant(sessionId)
        return client.history().map { message ->
            AgentTranscriptEntry(
                id = message.id,
                sessionId = LOCAL_ASSISTANT_SESSION_ID,
                turnId = null,
                role = message.role,
                channel = AgentMessageChannel.FINAL,
                text = message.text,
                createdAtEpochSeconds = message.createdAtEpochSeconds,
            )
        }
    }

    override suspend fun startSession(options: StartSessionOptions): AgentSession {
        ensureOpen()
        require(options.workingDirectory == null && options.model == null && options.providerOptions.isEmpty()) {
            "WorkBuddy does not support working-directory, model, or provider session options"
        }
        return refreshSessions().single()
    }

    override suspend fun sendInput(sessionId: AgentSessionId, text: String) {
        ensureOpen()
        requireLocalAssistant(sessionId)
        when (client.sendText(text)) {
            is WorkBuddySendResult.Accepted ->
                mutableSessions.value = listOf(session(online = true, running = true))
            WorkBuddySendResult.AuthenticationRequired -> error("WorkBuddy authentication is required")
            WorkBuddySendResult.AuthorizationDenied ->
                error("WorkBuddy Local Assistant permission is unavailable")
            WorkBuddySendResult.RateLimited -> error("WorkBuddy request rate limit was reached")
            WorkBuddySendResult.UnknownOutcome -> throw WorkBuddyUnknownOutcomeException()
            WorkBuddySendResult.Failed -> error("WorkBuddy message submission failed")
        }
    }

    override suspend fun steerActiveTurn(sessionId: AgentSessionId, text: String): Nothing =
        unsupported("active-turn steering")

    override suspend fun interrupt(sessionId: AgentSessionId): Nothing =
        unsupported("turn interruption")

    override suspend fun respondToApproval(
        approvalId: AgentApprovalId,
        decision: AgentApprovalDecision,
        answers: Map<String, List<String>>,
    ): Nothing = unsupported("approval responses")

    override suspend fun changedFiles(sessionId: AgentSessionId): List<AgentChangedFile> =
        unsupported("changed-file reporting")

    override suspend fun close() {
        closed = true
        mutableSessions.value = emptyList()
    }

    private fun session(online: Boolean, running: Boolean = false) = AgentSession(
        id = LOCAL_ASSISTANT_SESSION_ID,
        providerId = descriptor.id,
        title = "Local Assistant",
        preview = if (online) "Online" else "Offline",
        workingDirectory = null,
        model = null,
        createdAtEpochSeconds = null,
        updatedAtEpochSeconds = null,
        state = when {
            running -> AgentSessionState.RUNNING
            online -> AgentSessionState.IDLE
            else -> AgentSessionState.UNKNOWN
        },
        canAcceptInput = online && !running,
    )

    private fun requireLocalAssistant(sessionId: AgentSessionId) {
        ensureOpen()
        require(sessionId == LOCAL_ASSISTANT_SESSION_ID) {
            "Unknown WorkBuddy Local Assistant session"
        }
    }

    private fun ensureOpen() = check(!closed) { "WorkBuddy connection is closed" }

    private fun unsupported(operation: String): Nothing =
        throw UnsupportedOperationException("WorkBuddy does not support $operation")

    private companion object {
        val LOCAL_ASSISTANT_SESSION_ID = AgentSessionId("local-assistant")
    }
}

/** A submission may have reached WorkBuddy and must never be replayed automatically. */
class WorkBuddyUnknownOutcomeException(cause: Throwable? = null) : IllegalStateException(
    "WorkBuddy message delivery has an unknown outcome; refresh history before retrying",
    cause,
)
