package dev.agentrelay.provider.claude

import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentToolStatus
import dev.agentrelay.provider.api.AgentTranscriptEntry
import dev.agentrelay.provider.api.AgentTranscriptRole
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

internal object ClaudeTranscriptMapper {
    fun fromRows(
        sessionId: AgentSessionId,
        rows: JsonArray,
    ): List<AgentTranscriptEntry> = rows.flatMapIndexed { rowIndex, element ->
        val row = element.objectOrNull() ?: return@flatMapIndexed emptyList()
        if (row.boolean("isSidechain") == true) return@flatMapIndexed emptyList()
        val message = row.objectValue("message") ?: return@flatMapIndexed emptyList()
        val role = message.string("role")
        val rowId = row.string("uuid") ?: "claude:$rowIndex"
        val createdAt = row.string("timestamp")?.let(::epochSeconds)
        val content = message["content"]
        when (content) {
            is kotlinx.serialization.json.JsonPrimitive -> listOf(
                textEntry(sessionId, rowId, role, content.content, createdAt),
            ).filter { it.text.isNotBlank() }
            is JsonArray -> content.mapIndexedNotNull { index, blockElement ->
                val block = blockElement.objectOrNull() ?: return@mapIndexedNotNull null
                block.toEntry(sessionId, rowId, role, createdAt, index)
            }
            else -> emptyList()
        }
    }

    private fun JsonObject.toEntry(
        sessionId: AgentSessionId,
        rowId: String,
        wireRole: String?,
        createdAt: Long?,
        index: Int,
    ): AgentTranscriptEntry? = when (string("type")) {
        "text", "thinking" -> {
            val text = string("text") ?: string("thinking").orEmpty()
            textEntry(
                sessionId,
                "$rowId:$index",
                wireRole,
                text,
                createdAt,
                if (string("type") == "thinking") {
                    AgentMessageChannel.REASONING_SUMMARY
                } else {
                    null
                },
            ).takeIf { it.text.isNotBlank() }
        }
        "tool_use" -> {
            val tool = string("name") ?: "Claude tool"
            val input = objectValue("input")
            val path = input?.string("file_path") ?: input?.string("notebook_path")
            AgentTranscriptEntry(
                id = string("id") ?: "$rowId:$index",
                sessionId = sessionId,
                turnId = null,
                role = AgentTranscriptRole.TOOL,
                channel = AgentMessageChannel.SYSTEM,
                text = listOfNotNull(tool, input?.readableInput()).joinToString("\n\n"),
                createdAtEpochSeconds = createdAt,
                metadata = buildMap {
                    put("claude.tool", tool)
                    put("claude.toolStatus", AgentToolStatus.STARTED.name)
                    path?.let { put("claude.filePath", it) }
                },
            )
        }
        "tool_result" -> AgentTranscriptEntry(
            id = "$rowId:$index",
            sessionId = sessionId,
            turnId = null,
            role = AgentTranscriptRole.TOOL,
            channel = AgentMessageChannel.SYSTEM,
            text = get("content")?.readableValue().orEmpty(),
            createdAtEpochSeconds = createdAt,
            metadata = buildMap {
                put("claude.toolStatus", AgentToolStatus.COMPLETED.name)
                string("tool_use_id")?.let { put("claude.toolUseId", it) }
            },
        )
        else -> null
    }

    private fun textEntry(
        sessionId: AgentSessionId,
        id: String,
        wireRole: String?,
        text: String,
        createdAt: Long?,
        overrideChannel: AgentMessageChannel? = null,
    ) = AgentTranscriptEntry(
        id = id,
        sessionId = sessionId,
        turnId = null,
        role = when (wireRole) {
            "user" -> AgentTranscriptRole.USER
            "assistant" -> AgentTranscriptRole.AGENT
            else -> AgentTranscriptRole.SYSTEM
        },
        channel = overrideChannel ?: if (wireRole == "assistant") AgentMessageChannel.FINAL else null,
        text = text,
        createdAtEpochSeconds = createdAt,
    )
}

internal fun JsonObject.readableInput(): String? {
    string("command")?.takeIf(String::isNotBlank)?.let { return it }
    if (isEmpty()) return null
    return entries.joinToString("\n") { (key, value) -> "$key: " + value.readableValue() }
}

private fun epochSeconds(value: String): Long? =
    runCatching { Instant.parse(value).epochSecond }.getOrNull()
