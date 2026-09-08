/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.codex

import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSession
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import kotlinx.serialization.json.JsonObject

internal val CODEX_PROVIDER_ID = AgentProviderId("openai.codex")

internal object CodexSessionMapper {
    fun fromThread(thread: JsonObject): AgentSession {
        val id = requireNotNull(thread.string("id")) { "Codex thread is missing its id" }
        val preview = thread.string("preview").orEmpty().trim()
        val state = parseState(thread.objectValue("status")?.string("type"))

        return AgentSession(
            id = AgentSessionId(id),
            providerId = CODEX_PROVIDER_ID,
            title = thread.string("name")?.takeIf(String::isNotBlank),
            preview = preview,
            workingDirectory = thread.string("cwd"),
            model = thread.string("model"),
            createdAtEpochSeconds = thread.long("createdAt"),
            updatedAtEpochSeconds = thread.long("updatedAt"),
            state = state,
            canAcceptInput = thread.boolean("canAcceptDirectInput")
                ?: (state != AgentSessionState.RUNNING && state != AgentSessionState.WAITING_FOR_APPROVAL),
            metadata = buildMap {
                putIfPresent("source", thread.string("source"))
                putIfPresent("modelProvider", thread.string("modelProvider"))
                putIfPresent("cliVersion", thread.string("cliVersion"))
                putIfPresent("sessionPath", thread.string("path"))
                thread.objectValue("gitInfo")?.let { git ->
                    putIfPresent("git.branch", git.string("branch"))
                    putIfPresent("git.sha", git.string("sha"))
                    putIfPresent("git.origin", git.string("originUrl"))
                }
            },
        )
    }

    fun parseState(type: String?): AgentSessionState = when (type) {
        "notLoaded" -> AgentSessionState.NOT_LOADED
        "idle" -> AgentSessionState.IDLE
        "active", "running" -> AgentSessionState.RUNNING
        "waitingForApproval" -> AgentSessionState.WAITING_FOR_APPROVAL
        "systemError", "failed" -> AgentSessionState.FAILED
        else -> AgentSessionState.UNKNOWN
    }
}

private fun MutableMap<String, String>.putIfPresent(key: String, value: String?) {
    if (!value.isNullOrBlank()) {
        put(key, value)
    }
}
