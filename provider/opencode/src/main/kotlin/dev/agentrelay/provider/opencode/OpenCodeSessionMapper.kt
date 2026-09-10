/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.opencode

import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSession
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal val OPENCODE_PROVIDER_ID = AgentProviderId("anomaly.opencode")

internal object OpenCodeSessionMapper {
    fun fromJson(
        session: JsonObject,
        status: String?,
        agentProviderId: AgentProviderId = OPENCODE_PROVIDER_ID,
    ): AgentSession {
        val id = requireNotNull(session.string("id")) { "OpenCode session is missing its id" }
        val title = session.string("title")?.takeIf(String::isNotBlank)
        val modelObject = session.objectValue("model")
        val providerId = modelObject?.string("providerID")
        val modelId = modelObject?.string("id")
        val state = parseState(status)

        return AgentSession(
            id = AgentSessionId(id),
            providerId = agentProviderId,
            title = title,
            preview = title.orEmpty(),
            workingDirectory = session.string("directory"),
            model = if (providerId != null && modelId != null) "$providerId/$modelId" else modelId,
            createdAtEpochSeconds = session.objectValue("time")?.long("created").millisecondsToEpochSeconds(),
            updatedAtEpochSeconds = session.objectValue("time")?.long("updated").millisecondsToEpochSeconds(),
            state = state,
            canAcceptInput = state != AgentSessionState.RUNNING &&
                state != AgentSessionState.WAITING_FOR_APPROVAL,
            metadata = buildMap {
                putIfPresent("projectId", session.string("projectID"))
                putIfPresent("slug", session.string("slug"))
                putIfPresent("path", session.string("path"))
                putIfPresent("version", session.string("version"))
                putIfPresent("agent", session.string("agent"))
                session.objectValue("summary")?.forEach { (key, value) ->
                    (value as? JsonPrimitive)?.content?.let {
                        put("summary.$key", it)
                    }
                }
            },
        )
    }

    fun parseState(status: String?): AgentSessionState = when (status) {
        "idle" -> AgentSessionState.IDLE
        "busy", "retry" -> AgentSessionState.RUNNING
        "waitingForApproval" -> AgentSessionState.WAITING_FOR_APPROVAL
        "failed", "error" -> AgentSessionState.FAILED
        null -> AgentSessionState.UNKNOWN
        else -> AgentSessionState.UNKNOWN
    }
}

private fun MutableMap<String, String>.putIfPresent(key: String, value: String?) {
    if (!value.isNullOrBlank()) {
        put(key, value)
    }
}
