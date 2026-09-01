package dev.agentrelay.provider.claude

import dev.agentrelay.provider.api.AgentEvent
import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.AgentToolStatus
import kotlinx.serialization.json.JsonObject

internal object ClaudeEventMapper {
    fun fromWire(
        sessionId: AgentSessionId,
        wire: JsonObject,
        workingDirectory: String? = null,
    ): List<AgentEvent> = when (
        wire.string("type")
    ) {
        "stream_event" -> fromStreamEvent(sessionId, wire.objectValue("event"))
        "assistant" -> fromAssistant(sessionId, wire.objectValue("message"), workingDirectory)
        "result" -> fromResult(sessionId, wire)
        else -> emptyList()
    }

    private fun fromStreamEvent(
        sessionId: AgentSessionId,
        event: JsonObject?,
    ): List<AgentEvent> {
        if (event?.string("type") != "content_block_delta") return emptyList()
        val delta = event.objectValue("delta") ?: return emptyList()
        val type = delta.string("type")
        val text = delta.string("text") ?: delta.string("thinking") ?: return emptyList()
        return listOf(
            AgentEvent.TextDelta(
                sessionId = sessionId,
                turnId = null,
                itemId = null,
                channel = if (type == "thinking_delta") {
                    AgentMessageChannel.REASONING_SUMMARY
                } else {
                    AgentMessageChannel.FINAL
                },
                text = text,
            ),
        )
    }

    private fun fromAssistant(
        sessionId: AgentSessionId,
        message: JsonObject?,
        workingDirectory: String?,
    ): List<AgentEvent> = message?.arrayValue("content").orEmpty().mapNotNull { element ->
        val block = element.objectOrNull() ?: return@mapNotNull null
        when (block.string("type")) {
            "text", "thinking" -> {
                val text = block.string("text") ?: block.string("thinking").orEmpty()
                AgentEvent.MessageCompleted(
                    sessionId = sessionId,
                    turnId = null,
                    itemId = message?.string("id"),
                    channel = if (block.string("type") == "thinking") {
                        AgentMessageChannel.REASONING_SUMMARY
                    } else {
                        AgentMessageChannel.FINAL
                    },
                    text = text,
                ).takeIf { text.isNotBlank() }
            }
            "tool_use" -> {
                val tool = block.string("name") ?: "Claude tool"
                AgentEvent.ToolChanged(
                    sessionId = sessionId,
                    turnId = null,
                    itemId = block.string("id") ?: "claude-tool",
                    toolName = tool,
                    summary = block.objectValue("input")?.readableInput(),
                    status = AgentToolStatus.STARTED,
                )
            }
            else -> null
        }
    }.flatMap { event ->
        if (event is AgentEvent.ToolChanged) {
            val block = message?.arrayValue("content").orEmpty()
                .mapNotNull { it.objectOrNull() }
                .firstOrNull { it.string("id") == event.itemId }
            val input = block?.objectValue("input")
            val path = input?.string("file_path") ?: input?.string("notebook_path")
            val file = ClaudeFileChangeMapper.fromTool(event.toolName, path, workingDirectory)
            if (file != null) {
                listOf(
                    event,
                    AgentEvent.FileChanged(
                        sessionId,
                        file,
                    ),
                )
            } else {
                listOf(event)
            }
        } else {
            listOf(event)
        }
    }

    private fun fromResult(
        sessionId: AgentSessionId,
        result: JsonObject,
    ): List<AgentEvent> {
        val failed = result.boolean("is_error") == true || result.string("subtype") != "success"
        return buildList {
            if (failed) {
                add(
                    AgentEvent.Error(
                        sessionId,
                        result.string("result") ?: result.string("subtype") ?: "Claude turn failed",
                        recoverable = true,
                    ),
                )
            }
            add(
                AgentEvent.TurnCompleted(
                    sessionId = sessionId,
                    turnId = null,
                    successful = !failed,
                    errorMessage = if (failed) result.string("result") else null,
                ),
            )
            add(
                AgentEvent.SessionStateChanged(
                    sessionId,
                    if (failed) AgentSessionState.FAILED else AgentSessionState.IDLE,
                ),
            )
        }
    }
}
