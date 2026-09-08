/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.claude

import dev.agentrelay.provider.api.AgentApproval
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentApprovalId
import dev.agentrelay.provider.api.AgentApprovalType
import dev.agentrelay.provider.api.AgentSessionId
import kotlinx.serialization.json.JsonObject

internal object ClaudeApprovalMapper {
    fun fromControlRequest(
        sessionId: AgentSessionId,
        wire: JsonObject,
    ): AgentApproval? {
        if (wire.string("type") != "control_request") return null
        val request = wire.objectValue("request") ?: return null
        if (request.string("subtype") != "can_use_tool") return null
        val id = wire.string("request_id") ?: return null
        val tool = request.string("tool_name") ?: "Claude tool"
        val input = request.objectValue("input")
        val command = input?.string("command")
        val path = input?.string("file_path") ?: input?.string("notebook_path")
        return AgentApproval(
            id = AgentApprovalId(id),
            sessionId = sessionId,
            turnId = null,
            type = when {
                command != null || tool == "Bash" -> AgentApprovalType.COMMAND
                path != null || tool in FILE_TOOLS -> AgentApprovalType.FILE_CHANGE
                else -> AgentApprovalType.PERMISSION
            },
            title = request.string("title") ?: request.string("display_name") ?: tool,
            description = request.string("description") ?: input?.readableInput(),
            command = command,
            workingDirectory = input?.string("cwd"),
            availableDecisions = buildSet {
                add(AgentApprovalDecision.APPROVE_ONCE)
                if (!request.arrayValue("permission_suggestions").isNullOrEmpty()) {
                    add(AgentApprovalDecision.APPROVE_FOR_SESSION)
                }
                add(AgentApprovalDecision.DECLINE)
            },
        )
    }

    private val FILE_TOOLS = setOf("Write", "Edit", "MultiEdit", "NotebookEdit")
}
