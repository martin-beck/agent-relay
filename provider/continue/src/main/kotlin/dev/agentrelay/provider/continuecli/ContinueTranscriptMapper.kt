package dev.agentrelay.provider.continuecli

import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentToolStatus
import dev.agentrelay.provider.api.AgentTranscriptEntry
import dev.agentrelay.provider.api.AgentTranscriptRole
import kotlinx.serialization.json.JsonObject

internal object ContinueTranscriptMapper {
    fun fromState(sessionId: AgentSessionId, state: JsonObject): List<AgentTranscriptEntry> {
        val history = state.objectValue("session")?.arrayValue("history").orEmpty()
        return history.flatMapIndexed { index, item ->
            val message = item.objectOrNull()?.objectValue("message")
                ?: return@flatMapIndexed emptyList()
            buildList {
                val role = message.string("role").toRole()
                val content = message.contentText()?.trim().orEmpty()
                if (content.isNotEmpty()) {
                    add(
                        AgentTranscriptEntry(
                            id = message.string("id") ?: "continue:$index",
                            sessionId = sessionId,
                            turnId = null,
                            role = role,
                            channel = if (role == AgentTranscriptRole.AGENT) {
                                AgentMessageChannel.FINAL
                            } else {
                                null
                            },
                            text = content,
                            createdAtEpochSeconds = message.long("timestamp")?.let(::timestampToSeconds),
                            metadata = buildMap {
                                put("continue.historyIndex", index.toString())
                                message.string("tool_call_id")?.let { put("continue.toolCallId", it) }
                                if (role == AgentTranscriptRole.TOOL) {
                                    put("continue.toolStatus", AgentToolStatus.COMPLETED.name)
                                }
                            },
                        ),
                    )
                }
                message.arrayValue("tool_calls").orEmpty().forEachIndexed { toolIndex, element ->
                    val tool = element.objectOrNull() ?: return@forEachIndexed
                    val function = tool.objectValue("function")
                    val toolName = function?.string("name") ?: tool.string("name") ?: "Tool"
                    val arguments = function?.string("arguments")
                        ?: function?.objectValue("arguments")?.readableArguments()
                        ?: tool.objectValue("arguments")?.readableArguments()
                    add(
                        AgentTranscriptEntry(
                            id = tool.string("id") ?: "continue:$index:tool:$toolIndex",
                            sessionId = sessionId,
                            turnId = null,
                            role = AgentTranscriptRole.TOOL,
                            channel = AgentMessageChannel.SYSTEM,
                            text = listOfNotNull(
                                toolName,
                                arguments?.takeIf(String::isNotBlank),
                            ).joinToString("\n\n"),
                            metadata = mapOf(
                                "continue.tool" to toolName,
                                "continue.toolStatus" to AgentToolStatus.STARTED.name,
                            ),
                        ),
                    )
                }
            }
        }
    }
}

private fun String?.toRole(): AgentTranscriptRole = when (this) {
    "user" -> AgentTranscriptRole.USER
    "assistant" -> AgentTranscriptRole.AGENT
    "tool" -> AgentTranscriptRole.TOOL
    else -> AgentTranscriptRole.SYSTEM
}

private fun JsonObject.readableArguments(): String? {
    string("command")?.takeIf(String::isNotBlank)?.let { return it }
    if (isEmpty()) return null
    return entries.joinToString("\n") { (key, value) -> "$key: " + value.readableValue() }
}

private fun timestampToSeconds(value: Long): Long =
    if (value >= 100_000_000_000L) value / 1_000 else value
