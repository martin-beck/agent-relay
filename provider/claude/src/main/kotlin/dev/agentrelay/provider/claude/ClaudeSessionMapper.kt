/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.claude

import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSession
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import kotlinx.serialization.json.JsonObject

internal val CLAUDE_PROVIDER_ID = AgentProviderId("anthropic.claude-code")

internal object ClaudeSessionMapper {
    fun fromDiscovery(summary: JsonObject): AgentSession {
        val id = requireNotNull(summary.string("id")) { "Claude session is missing its id" }
        val firstText = summary.string("firstText").orEmpty()
        val lastText = summary.string("lastText").orEmpty()
        val title = summary.string("name")?.takeIf(String::isNotBlank)
        return AgentSession(
            id = AgentSessionId(id),
            providerId = CLAUDE_PROVIDER_ID,
            title = title,
            preview = firstText.ifBlank { lastText },
            workingDirectory = summary.string("cwd"),
            model = summary.string("model"),
            createdAtEpochSeconds = summary.long("createdAt"),
            updatedAtEpochSeconds = summary.long("updatedAt"),
            state = AgentSessionState.IDLE,
            canAcceptInput = true,
            metadata = buildMap {
                summary.string("file")?.let { put("claude.sessionFile", it) }
                summary.string("version")?.let { put("claude.version", it) }
            },
        )
    }

    fun withState(
        session: AgentSession,
        state: AgentSessionState,
        cwd: String? = null,
        model: String? = null,
    ): AgentSession = session.copy(
        workingDirectory = cwd ?: session.workingDirectory,
        model = model ?: session.model,
        state = state,
        canAcceptInput = state != AgentSessionState.RUNNING &&
            state != AgentSessionState.WAITING_FOR_APPROVAL,
    )
}
