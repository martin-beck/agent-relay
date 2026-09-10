/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.clinecli

import dev.agentrelay.provider.api.AgentApproval
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentApprovalId
import dev.agentrelay.provider.api.AgentApprovalType
import dev.agentrelay.provider.api.AgentSessionId

internal data class PendingClineApproval(
    val call: ClineAcpCall,
    val approval: AgentApproval,
    val optionIds: Map<AgentApprovalDecision, String>,
)

internal object ClineApprovalMapper {
    fun fromCall(call: ClineAcpCall): PendingClineApproval? {
        if (call.method != "session/request_permission" || call.id == null) return null
        val params = call.params ?: return null
        val sessionId = params.string("sessionId")?.let(::AgentSessionId) ?: return null
        val tool = params.objectValue("toolCall") ?: return null
        val toolCallId = tool.string("toolCallId") ?: return null
        val options = params.arrayValue("options").orEmpty().mapNotNull { it.clineObject() }
        val decisions = buildMap<AgentApprovalDecision, String> {
            options.forEach { option ->
                val id = option.string("optionId") ?: return@forEach
                when (option.string("kind")) {
                    "allow_once" -> put(AgentApprovalDecision.APPROVE_ONCE, id)
                    "allow_always" -> put(AgentApprovalDecision.APPROVE_FOR_SESSION, id)
                    "reject_once", "reject_always" -> putIfAbsent(AgentApprovalDecision.DECLINE, id)
                }
            }
            put(AgentApprovalDecision.CANCEL, "cancelled")
        }
        val input = tool.objectValue("rawInput")
        val kind = tool.string("kind")
        return PendingClineApproval(
            call = call,
            approval = AgentApproval(
                id = AgentApprovalId(sessionId.value + ":" + toolCallId),
                sessionId = sessionId,
                turnId = null,
                type = when (kind) {
                    "execute" -> AgentApprovalType.COMMAND
                    "edit", "delete", "move" -> AgentApprovalType.FILE_CHANGE
                    "fetch" -> AgentApprovalType.EXTERNAL_TOOL
                    else -> AgentApprovalType.PERMISSION
                },
                title = tool.string("title") ?: "Cline tool permission",
                description = input?.readableInput(),
                command = input?.string("command"),
                workingDirectory = input?.string("cwd"),
                availableDecisions = decisions.keys,
            ),
            optionIds = decisions,
        )
    }
}
