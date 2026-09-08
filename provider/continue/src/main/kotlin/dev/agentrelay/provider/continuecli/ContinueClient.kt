/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.continuecli

import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.StartSessionOptions
import kotlinx.serialization.json.JsonObject

internal interface ContinueClient {
    suspend fun state(): JsonObject

    suspend fun sendMessage(message: String)

    suspend fun respondToPermission(requestId: String, approved: Boolean)

    suspend fun pause()

    suspend fun diff(): String

    suspend fun close()
}

internal fun interface ContinueClientStarter {
    suspend fun start(
        sessionId: AgentSessionId,
        workingDirectory: String?,
        options: StartSessionOptions,
    ): ContinueClient
}
