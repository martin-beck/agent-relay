package dev.agentrelay.provider.clinecli

import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentToolStatus
import dev.agentrelay.provider.api.AgentTranscriptEntry
import dev.agentrelay.provider.api.AgentTranscriptRole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object ClineTranscriptMapper {
    fun fromDocument(sessionId: AgentSessionId, document: JsonObject): List<AgentTranscriptEntry> =
        document.arrayValue("messages").orEmpty().flatMapIndexed { rowIndex, element ->
            val row = element.clineObject() ?: return@flatMapIndexed emptyList()
            val role = row.string("role")
            val rowId = row.string("id") ?: "cline:$rowIndex"
            val createdAt = row.long("ts")?.let { it / 1_000 }
            when (val content = row["content"]) {
                is JsonPrimitive -> listOf(
                    textEntry(sessionId, rowId, role, content.content, createdAt),
                ).filter { it.text.isNotBlank() }
                is JsonArray -> content.mapIndexedNotNull { index, item ->
                    item.clineObject()?.toEntry(sessionId, rowId, role, createdAt, index)
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
        "text" -> textEntry(
            sessionId,
            "$rowId:$index",
            wireRole,
            string("text").orEmpty(),
            createdAt,
        ).takeIf { it.text.isNotBlank() }
        "thinking" -> textEntry(
            sessionId,
            "$rowId:$index",
            wireRole,
            string("thinking").orEmpty(),
            createdAt,
            AgentMessageChannel.REASONING_SUMMARY,
        ).takeIf { it.text.isNotBlank() }
        "tool_use" -> {
            val tool = string("name") ?: "Cline tool"
            val input = objectValue("input")
            val kind = inferToolKind(tool)
            val path = input?.string("path")
                ?: input?.string("file_path")
                ?: input?.string("filePath")
            AgentTranscriptEntry(
                id = string("id") ?: "$rowId:$index",
                sessionId = sessionId,
                turnId = null,
                role = AgentTranscriptRole.TOOL,
                channel = AgentMessageChannel.SYSTEM,
                text = listOfNotNull(tool, input?.readableInput()).joinToString("\n\n"),
                createdAtEpochSeconds = createdAt,
                metadata = buildMap {
                    put("cline.tool", tool)
                    put("cline.toolKind", kind)
                    put("cline.toolStatus", AgentToolStatus.STARTED.name)
                    path?.let { put("cline.filePath", it) }
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
                put(
                    "cline.toolStatus",
                    if (boolean("is_error") == true) {
                        AgentToolStatus.FAILED.name
                    } else {
                        AgentToolStatus.COMPLETED.name
                    },
                )
                string("tool_use_id")?.let { put("cline.toolUseId", it) }
            },
        )
        else -> null
    }

    private fun textEntry(
        sessionId: AgentSessionId,
        id: String,
        role: String?,
        text: String,
        createdAt: Long?,
        overrideChannel: AgentMessageChannel? = null,
    ) = AgentTranscriptEntry(
        id = id,
        sessionId = sessionId,
        turnId = null,
        role = when (role) {
            "user" -> AgentTranscriptRole.USER
            "assistant" -> AgentTranscriptRole.AGENT
            else -> AgentTranscriptRole.SYSTEM
        },
        channel = overrideChannel ?: if (role == "assistant") AgentMessageChannel.FINAL else null,
        text = text,
        createdAtEpochSeconds = createdAt,
    )

    private fun inferToolKind(tool: String): String = when {
        tool.contains("delete", ignoreCase = true) -> "delete"
        tool.contains("move", ignoreCase = true) ||
            tool.contains("rename", ignoreCase = true) -> "move"
        tool.contains("write", ignoreCase = true) ||
            tool.contains("edit", ignoreCase = true) ||
            tool.contains("replace", ignoreCase = true) ||
            tool.contains("patch", ignoreCase = true) -> "edit"
        else -> "other"
    }
}

internal fun JsonObject.readableInput(): String? {
    string("command")?.takeIf(String::isNotBlank)?.let { return it }
    if (isEmpty()) return null
    return entries.joinToString("\n") { (key, value) -> "$key: " + value.readableValue() }
}
