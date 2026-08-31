package dev.agentrelay.provider.codex

import dev.agentrelay.provider.api.AgentChangedFile
import dev.agentrelay.provider.api.AgentEvent
import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.AgentToolStatus
import dev.agentrelay.provider.api.AgentTurnId
import kotlinx.serialization.json.JsonObject

internal object CodexEventMapper {
    fun map(call: JsonRpcCall): List<AgentEvent> {
        val params = call.params.objectOrNull() ?: return emptyList()
        val sessionId = params.string("threadId")?.let(::AgentSessionId) ?: return emptyList()
        val turnId = params.string("turnId")?.let(::AgentTurnId)

        return when (call.method) {
            "item/agentMessage/delta" -> textDelta(
                params,
                sessionId,
                turnId,
                AgentMessageChannel.COMMENTARY,
            )
            "item/plan/delta" -> textDelta(params, sessionId, turnId, AgentMessageChannel.PLAN)
            "item/reasoning/summaryTextDelta" -> textDelta(
                params,
                sessionId,
                turnId,
                AgentMessageChannel.REASONING_SUMMARY,
            )
            "item/completed" -> completedItem(params, sessionId, turnId)
            "item/started" -> startedItem(params, sessionId, turnId)
            "turn/started" -> listOf(
                AgentEvent.SessionStateChanged(sessionId, AgentSessionState.RUNNING),
            )
            "turn/completed" -> completedTurn(params, sessionId, turnId)
            "thread/status/changed" -> listOf(
                AgentEvent.SessionStateChanged(
                    sessionId,
                    CodexSessionMapper.parseState(params.objectValue("status")?.string("type")),
                ),
            )
            else -> emptyList()
        }
    }

    private fun textDelta(
        params: JsonObject,
        sessionId: AgentSessionId,
        turnId: AgentTurnId?,
        channel: AgentMessageChannel,
    ): List<AgentEvent> {
        val delta = params.string("delta").orEmpty()
        if (delta.isEmpty()) {
            return emptyList()
        }
        return listOf(
            AgentEvent.TextDelta(
                sessionId = sessionId,
                turnId = turnId,
                itemId = params.string("itemId"),
                channel = channel,
                text = delta,
            ),
        )
    }

    private fun startedItem(params: JsonObject, sessionId: AgentSessionId, turnId: AgentTurnId?): List<AgentEvent> {
        val item = params.objectValue("item") ?: return emptyList()
        if (!item.isTool()) {
            return emptyList()
        }
        return listOf(item.toToolEvent(sessionId, turnId, AgentToolStatus.STARTED))
    }

    private fun completedItem(params: JsonObject, sessionId: AgentSessionId, turnId: AgentTurnId?): List<AgentEvent> {
        val item = params.objectValue("item") ?: return emptyList()
        return when (item.string("type")) {
            "agentMessage" -> item.string("text")?.let { text ->
                listOf(
                    AgentEvent.MessageCompleted(
                        sessionId = sessionId,
                        turnId = turnId,
                        itemId = item.string("id"),
                        channel = AgentMessageChannel.FINAL,
                        text = text,
                    ),
                )
            }.orEmpty()
            "plan" -> item.string("text")?.let { text ->
                listOf(
                    AgentEvent.MessageCompleted(
                        sessionId = sessionId,
                        turnId = turnId,
                        itemId = item.string("id"),
                        channel = AgentMessageChannel.PLAN,
                        text = text,
                    ),
                )
            }.orEmpty()
            "fileChange" -> buildList {
                add(item.toToolEvent(sessionId, turnId, item.toolStatus()))
                addAll(item.changedFiles(turnId).map { AgentEvent.FileChanged(sessionId, it) })
            }
            "commandExecution", "mcpToolCall", "webSearch" ->
                listOf(item.toToolEvent(sessionId, turnId, item.toolStatus()))
            else -> emptyList()
        }
    }

    private fun completedTurn(
        params: JsonObject,
        sessionId: AgentSessionId,
        fallbackTurnId: AgentTurnId?,
    ): List<AgentEvent> {
        val turn = params.objectValue("turn")
        val status = turn?.string("status") ?: params.string("status")
        val error = turn?.objectValue("error")?.string("message")
            ?: params.objectValue("error")?.string("message")
        val turnId = turn?.string("id")?.let(::AgentTurnId) ?: fallbackTurnId
        return listOf(
            AgentEvent.TurnCompleted(
                sessionId = sessionId,
                turnId = turnId,
                successful = status == "completed" || status == "succeeded",
                errorMessage = error,
            ),
            AgentEvent.SessionStateChanged(
                sessionId = sessionId,
                state = if (error == null) AgentSessionState.IDLE else AgentSessionState.FAILED,
            ),
        )
    }

    private fun JsonObject.isTool(): Boolean = string("type") in setOf(
        "commandExecution",
        "fileChange",
        "mcpToolCall",
        "webSearch",
    )

    private fun JsonObject.toToolEvent(
        sessionId: AgentSessionId,
        turnId: AgentTurnId?,
        status: AgentToolStatus,
    ): AgentEvent.ToolChanged = AgentEvent.ToolChanged(
        sessionId = sessionId,
        turnId = turnId,
        itemId = string("id") ?: string("type").orEmpty(),
        toolName = when (string("type")) {
            "commandExecution" -> "Command"
            "fileChange" -> "File change"
            "mcpToolCall" -> string("tool") ?: string("name") ?: "MCP tool"
            "webSearch" -> "Web search"
            else -> "Tool"
        },
        summary = string("command") ?: string("query") ?: string("aggregatedOutput"),
        status = status,
    )

    private fun JsonObject.toolStatus(): AgentToolStatus = when (string("status")) {
        "failed" -> AgentToolStatus.FAILED
        "declined" -> AgentToolStatus.DECLINED
        else -> AgentToolStatus.COMPLETED
    }

    private fun JsonObject.changedFiles(turnId: AgentTurnId?): List<AgentChangedFile> =
        arrayValue("changes").orEmpty().mapNotNull { element ->
            val change = element.objectOrNull() ?: return@mapNotNull null
            val path = change.string("path") ?: return@mapNotNull null
            AgentChangedFile(
                remotePath = path,
                kind = when (change.string("kind")) {
                    "add", "added", "create" -> AgentFileChangeKind.ADDED
                    "modify", "modified", "update" -> AgentFileChangeKind.MODIFIED
                    "delete", "deleted" -> AgentFileChangeKind.DELETED
                    "rename", "renamed" -> AgentFileChangeKind.RENAMED
                    else -> AgentFileChangeKind.UNKNOWN
                },
                oldRemotePath = change.string("oldPath"),
                turnId = turnId,
            )
        }
}
