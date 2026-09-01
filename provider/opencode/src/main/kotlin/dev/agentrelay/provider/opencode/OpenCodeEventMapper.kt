package dev.agentrelay.provider.opencode

import dev.agentrelay.provider.api.AgentApproval
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentApprovalId
import dev.agentrelay.provider.api.AgentApprovalType
import dev.agentrelay.provider.api.AgentEvent
import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.AgentToolStatus
import kotlinx.serialization.json.JsonObject

internal object OpenCodeEventMapper {
    fun map(event: JsonObject): List<AgentEvent> {
        val type = event.string("type") ?: return emptyList()
        val properties = event.objectValue("properties") ?: return emptyList()
        return when (type) {
            "message.part.updated" -> partUpdated(properties)
            "session.status" -> sessionStatus(properties)
            "session.idle" -> properties.string("sessionID")?.let {
                listOf(AgentEvent.SessionStateChanged(AgentSessionId(it), AgentSessionState.IDLE))
            }.orEmpty()
            "permission.updated" -> permissionUpdated(properties)
            "session.diff" -> sessionDiff(properties)
            "session.error" -> sessionError(properties)
            else -> emptyList()
        }
    }

    private fun partUpdated(properties: JsonObject): List<AgentEvent> {
        val part = properties.objectValue("part") ?: return emptyList()
        val sessionId = part.string("sessionID")?.let(::AgentSessionId) ?: return emptyList()
        return when (part.string("type")) {
            "text", "reasoning" -> {
                val delta = properties.string("delta").orEmpty()
                if (delta.isNotEmpty()) {
                    listOf(
                        AgentEvent.TextDelta(
                            sessionId = sessionId,
                            turnId = null,
                            itemId = part.string("id"),
                            channel = if (part.string("type") == "reasoning") {
                                AgentMessageChannel.REASONING_SUMMARY
                            } else {
                                AgentMessageChannel.COMMENTARY
                            },
                            text = delta,
                        ),
                    )
                } else if (part.objectValue("time")?.long("end") != null) {
                    part.string("text")?.takeIf(String::isNotBlank)?.let { text ->
                        listOf(
                            AgentEvent.MessageCompleted(
                                sessionId = sessionId,
                                turnId = null,
                                itemId = part.string("id"),
                                channel = AgentMessageChannel.FINAL,
                                text = text,
                            ),
                        )
                    }.orEmpty()
                } else {
                    emptyList()
                }
            }
            "tool" -> listOf(
                AgentEvent.ToolChanged(
                    sessionId = sessionId,
                    turnId = null,
                    itemId = part.string("id") ?: part.string("tool").orEmpty(),
                    toolName = part.string("tool") ?: "Tool",
                    summary = part.objectValue("state")?.string("title")
                        ?: part.objectValue("state")?.objectValue("input")?.string("command"),
                    status = part.objectValue("state")?.string("status").toToolStatus(),
                ),
            )
            else -> emptyList()
        }
    }

    private fun sessionStatus(properties: JsonObject): List<AgentEvent> {
        val sessionId = properties.string("sessionID")?.let(::AgentSessionId) ?: return emptyList()
        val status = properties.objectValue("status")?.string("type")
        return listOf(AgentEvent.SessionStateChanged(sessionId, OpenCodeSessionMapper.parseState(status)))
    }

    private fun permissionUpdated(properties: JsonObject): List<AgentEvent> {
        val id = properties.string("id") ?: return emptyList()
        val sessionId = properties.string("sessionID")?.let(::AgentSessionId) ?: return emptyList()
        val permission = properties.string("type") ?: properties.string("permission")
        val metadata = properties.objectValue("metadata")
        val approval = AgentApproval(
            id = AgentApprovalId(id),
            sessionId = sessionId,
            turnId = null,
            type = when (permission) {
                "bash" -> AgentApprovalType.COMMAND
                "edit", "write" -> AgentApprovalType.FILE_CHANGE
                else -> AgentApprovalType.PERMISSION
            },
            title = properties.string("title") ?: "Allow " + (permission ?: "operation"),
            description = properties.string("description"),
            command = metadata?.string("command"),
            workingDirectory = metadata?.string("cwd"),
            availableDecisions = setOf(
                AgentApprovalDecision.APPROVE_ONCE,
                AgentApprovalDecision.APPROVE_FOR_SESSION,
                AgentApprovalDecision.DECLINE,
            ),
        )
        return listOf(AgentEvent.ApprovalRequested(sessionId, approval))
    }

    private fun sessionDiff(properties: JsonObject): List<AgentEvent> {
        val sessionId = properties.string("sessionID")?.let(::AgentSessionId) ?: return emptyList()
        val diff = properties.arrayValue("diff")
            ?: kotlinx.serialization.json.JsonArray(emptyList())
        return OpenCodeDiffMapper.fromJson(diff).map {
            AgentEvent.FileChanged(sessionId, it)
        }
    }

    private fun sessionError(properties: JsonObject): List<AgentEvent> {
        val sessionId = properties.string("sessionID")?.let(::AgentSessionId) ?: return emptyList()
        val error = properties.objectValue("error")
        val message = error?.string("message") ?: error?.string("name") ?: "OpenCode session failed"
        return listOf(
            AgentEvent.Error(sessionId, message, recoverable = true),
            AgentEvent.SessionStateChanged(sessionId, AgentSessionState.FAILED),
        )
    }
}

private fun String?.toToolStatus(): AgentToolStatus = when (this) {
    "completed" -> AgentToolStatus.COMPLETED
    "error", "failed" -> AgentToolStatus.FAILED
    "declined" -> AgentToolStatus.DECLINED
    else -> AgentToolStatus.STARTED
}
