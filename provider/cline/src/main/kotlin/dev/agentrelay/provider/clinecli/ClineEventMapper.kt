/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.clinecli

import dev.agentrelay.provider.api.AgentEvent
import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.AgentToolStatus

internal object ClineEventMapper {
    fun fromCall(
        attachedSessionId: AgentSessionId,
        call: ClineAcpCall,
        workingDirectory: String?,
    ): List<AgentEvent> {
        if (call.method == "agent_relay/error") {
            return listOf(
                AgentEvent.Error(
                    attachedSessionId,
                    call.params?.string("message") ?: "Cline ACP process stopped",
                    recoverable = true,
                ),
                AgentEvent.SessionStateChanged(
                    attachedSessionId,
                    AgentSessionState.FAILED,
                ),
            )
        }
        if (call.method != "session/update") return emptyList()
        val params = call.params ?: return emptyList()
        val sessionId = params.string("sessionId")?.let(::AgentSessionId) ?: attachedSessionId
        if (sessionId != attachedSessionId) return emptyList()
        val update = params.objectValue("update") ?: return emptyList()
        return when (update.string("sessionUpdate")) {
            "agent_message_chunk" -> text(update, sessionId, AgentMessageChannel.FINAL)
            "agent_thought_chunk" -> text(
                update,
                sessionId,
                AgentMessageChannel.REASONING_SUMMARY,
            )
            "tool_call" -> toolStarted(update, sessionId, workingDirectory)
            "tool_call_update" -> listOf(
                AgentEvent.ToolChanged(
                    sessionId = sessionId,
                    turnId = null,
                    itemId = update.string("toolCallId") ?: "cline-tool",
                    toolName = update.string("title") ?: "Cline tool",
                    summary = update["rawOutput"]?.readableValue(),
                    status = when (update.string("status")) {
                        "completed" -> AgentToolStatus.COMPLETED
                        "failed" -> AgentToolStatus.FAILED
                        else -> AgentToolStatus.STARTED
                    },
                ),
            )
            else -> emptyList()
        }
    }

    private fun text(
        update: kotlinx.serialization.json.JsonObject,
        sessionId: AgentSessionId,
        channel: AgentMessageChannel,
    ): List<AgentEvent> {
        val text = update.objectValue("content")?.string("text") ?: return emptyList()
        return listOf(
            AgentEvent.TextDelta(
                sessionId = sessionId,
                turnId = null,
                itemId = null,
                channel = channel,
                text = text,
            ),
        )
    }

    private fun toolStarted(
        update: kotlinx.serialization.json.JsonObject,
        sessionId: AgentSessionId,
        workingDirectory: String?,
    ): List<AgentEvent> {
        val id = update.string("toolCallId") ?: "cline-tool"
        val title = update.string("title") ?: "Cline tool"
        val kind = update.string("kind")
        val input = update.objectValue("rawInput")
        val tool = AgentEvent.ToolChanged(
            sessionId = sessionId,
            turnId = null,
            itemId = id,
            toolName = title,
            summary = input?.readableInput(),
            status = AgentToolStatus.STARTED,
        )
        val file = ClineFileChangeMapper.fromTool(kind, input, workingDirectory)
        return if (file == null) {
            listOf(tool)
        } else {
            listOf(tool, AgentEvent.FileChanged(sessionId, file))
        }
    }
}
