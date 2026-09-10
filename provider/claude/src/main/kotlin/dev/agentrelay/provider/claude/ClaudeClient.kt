/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.claude

import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.StartSessionOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

internal interface ClaudeClient {
    val messages: Flow<JsonObject>

    suspend fun sendUserMessage(text: String)

    suspend fun interrupt()

    suspend fun respondToPermission(requestId: String, allowed: Boolean, remember: Boolean)

    suspend fun close()
}

internal fun interface ClaudeClientStarter {
    suspend fun start(
        sessionId: AgentSessionId,
        resume: Boolean,
        workingDirectory: String?,
        options: StartSessionOptions,
    ): ClaudeClient
}
