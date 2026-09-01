package dev.agentrelay.provider.continuecli

import dev.agentrelay.provider.api.AgentApproval
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentApprovalId
import dev.agentrelay.provider.api.AgentApprovalType
import dev.agentrelay.provider.api.AgentSessionId
import kotlinx.serialization.json.JsonObject

internal object ContinueApprovalMapper {
    fun fromState(state: JsonObject): AgentApproval? {
        val permission = state.objectValue("pendingPermission") ?: return null
        val requestId = permission.string("requestId") ?: return null
        val sessionId = state.objectValue("session")?.string("sessionId") ?: return null
        val toolName = permission.string("toolName") ?: "Permission"
        val arguments = permission.objectValue("toolArgs")
        val command = arguments?.string("command")
        val directory = arguments?.string("cwd")
            ?: state.objectValue("session")?.string("workspaceDirectory")
        val preview = permission.string("toolCallPreview") ?: arguments?.readableDescription()
        return AgentApproval(
            id = AgentApprovalId(requestId),
            sessionId = AgentSessionId(sessionId),
            turnId = null,
            type = when {
                command != null || toolName.contains("terminal", ignoreCase = true) ->
                    AgentApprovalType.COMMAND
                toolName.contains("file", ignoreCase = true) ->
                    AgentApprovalType.FILE_CHANGE
                else -> AgentApprovalType.PERMISSION
            },
            title = toolName,
            description = preview,
            command = command,
            workingDirectory = directory,
            availableDecisions = setOf(
                AgentApprovalDecision.APPROVE_ONCE,
                AgentApprovalDecision.DECLINE,
            ),
        )
    }
}

private fun JsonObject.readableDescription(): String? {
    if (isEmpty()) return null
    return entries.joinToString("\n") { (key, value) -> "$key: " + value.readableValue() }
}
