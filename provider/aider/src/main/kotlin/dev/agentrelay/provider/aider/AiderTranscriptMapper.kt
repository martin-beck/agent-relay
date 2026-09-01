package dev.agentrelay.provider.aider

import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentTranscriptEntry
import dev.agentrelay.provider.api.AgentTranscriptRole
import kotlinx.serialization.json.JsonArray

internal object AiderTranscriptMapper {
    fun fromRows(
        sessionId: AgentSessionId,
        rows: JsonArray,
    ): List<AgentTranscriptEntry> =
        rows.mapIndexedNotNull { index, element ->
            val row = element.aiderObject() ?: return@mapIndexedNotNull null
            val text = row.string("text")?.trim()?.takeIf(String::isNotEmpty)
                ?: return@mapIndexedNotNull null
            val role = when (row.string("role")) {
                "user" -> AgentTranscriptRole.USER
                "assistant" -> AgentTranscriptRole.AGENT
                "tool" -> AgentTranscriptRole.TOOL
                else -> AgentTranscriptRole.SYSTEM
            }
            AgentTranscriptEntry(
                id = row.string("id") ?: "aider-history-$index",
                sessionId = sessionId,
                turnId = null,
                role = role,
                channel = when (role) {
                    AgentTranscriptRole.AGENT -> AgentMessageChannel.FINAL
                    AgentTranscriptRole.SYSTEM -> AgentMessageChannel.SYSTEM
                    else -> null
                },
                text = text,
                createdAtEpochSeconds = row.long("createdAt"),
            )
        }
}
