package dev.agentrelay.provider.opencode

import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentTranscriptEntry
import dev.agentrelay.provider.api.AgentTranscriptRole
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal object OpenCodeTranscriptMapper {
    fun fromJson(sessionId: AgentSessionId, messages: JsonArray): List<AgentTranscriptEntry> =
        messages.flatMap { messageElement ->
            val message = messageElement.objectOrNull() ?: return@flatMap emptyList()
            val info = message.objectValue("info") ?: return@flatMap emptyList()
            val messageId = info.string("id") ?: return@flatMap emptyList()
            val role = info.string("role")
            val createdAt = info.objectValue("time")?.long("created").millisecondsToEpochSeconds()
            message.arrayValue("parts").orEmpty().mapIndexedNotNull { index, partElement ->
                val part = partElement.objectOrNull() ?: return@mapIndexedNotNull null
                part.toEntry(sessionId, messageId, role, createdAt, index)
            }
        }

    private fun JsonObject.toEntry(
        sessionId: AgentSessionId,
        messageId: String,
        wireRole: String?,
        createdAt: Long?,
        index: Int,
    ): AgentTranscriptEntry? {
        val partType = string("type") ?: return null
        val id = string("id") ?: "$messageId:$index"
        return when (partType) {
            "text", "reasoning" -> {
                val text = string("text").orEmpty().trim()
                if (text.isEmpty()) {
                    null
                } else {
                    AgentTranscriptEntry(
                        id = id,
                        sessionId = sessionId,
                        turnId = null,
                        role = wireRole.toTranscriptRole(),
                        channel = when {
                            wireRole == "user" -> null
                            partType == "reasoning" -> AgentMessageChannel.REASONING_SUMMARY
                            else -> AgentMessageChannel.FINAL
                        },
                        text = text,
                        createdAtEpochSeconds = createdAt,
                        metadata = mapOf("opencode.partType" to partType),
                    )
                }
            }
            "tool" -> {
                val state = objectValue("state")
                val text = listOfNotNull(
                    state?.string("title") ?: string("tool"),
                    state?.objectValue("input")?.readableJson(),
                    state?.string("output"),
                    state?.string("error"),
                ).filter(String::isNotBlank).joinToString("\n\n")
                if (text.isBlank()) {
                    null
                } else {
                    AgentTranscriptEntry(
                        id = id,
                        sessionId = sessionId,
                        turnId = null,
                        role = AgentTranscriptRole.TOOL,
                        channel = AgentMessageChannel.SYSTEM,
                        text = text,
                        createdAtEpochSeconds = createdAt,
                        metadata = buildMap {
                            put("opencode.partType", partType)
                            string("tool")?.let { put("opencode.tool", it) }
                            state?.string("status")?.let { put("opencode.toolStatus", it) }
                        },
                    )
                }
            }
            else -> null
        }
    }
}

private fun String?.toTranscriptRole(): AgentTranscriptRole = when (this) {
    "user" -> AgentTranscriptRole.USER
    "assistant" -> AgentTranscriptRole.AGENT
    else -> AgentTranscriptRole.SYSTEM
}

private fun JsonObject.readableJson(): String? {
    val command = string("command")
    if (!command.isNullOrBlank()) {
        return command
    }
    if (isEmpty()) {
        return null
    }
    return entries.joinToString(separator = "\n") { (key, value) ->
        "$key: " + value.readableValue()
    }
}

private fun JsonElement.readableValue(): String = when (this) {
    is kotlinx.serialization.json.JsonPrimitive -> content
    else -> toString()
}
