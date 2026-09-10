/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.api

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface AgentProviderFactory {
    val descriptor: AgentProviderDescriptor

    suspend fun probe(runtime: RemoteAgentRuntime): ProviderReadiness

    suspend fun connect(runtime: RemoteAgentRuntime): AgentProviderConnection
}

interface AgentProviderConnection {
    val descriptor: AgentProviderDescriptor
    val sessions: StateFlow<List<AgentSession>>
    val events: SharedFlow<AgentEvent>

    suspend fun refreshSessions(): List<AgentSession>

    suspend fun attach(sessionId: AgentSessionId): AgentSession

    suspend fun transcript(sessionId: AgentSessionId): List<AgentTranscriptEntry>

    suspend fun startSession(options: StartSessionOptions): AgentSession

    suspend fun sendInput(sessionId: AgentSessionId, text: String)

    suspend fun steerActiveTurn(sessionId: AgentSessionId, text: String)

    suspend fun interrupt(sessionId: AgentSessionId)

    suspend fun respondToApproval(
        approvalId: AgentApprovalId,
        decision: AgentApprovalDecision,
        answers: Map<String, List<String>> = emptyMap(),
    )

    suspend fun changedFiles(sessionId: AgentSessionId): List<AgentChangedFile>

    suspend fun close()
}
