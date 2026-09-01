package dev.agentrelay.provider.clinecli

import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSession
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import java.time.Instant
import kotlinx.serialization.json.JsonObject

internal val CLINE_PROVIDER_ID = AgentProviderId("cline.cli")

internal object ClineSessionMapper {
    fun fromHistory(row: JsonObject): AgentSession {
        val id = requireNotNull(row.string("sessionId") ?: row.string("session_id")) {
            "Cline history row is missing its session id"
        }
        val metadata = row.objectValue("metadata")
        val status = row.string("status")
        return AgentSession(
            id = AgentSessionId(id),
            providerId = CLINE_PROVIDER_ID,
            title = metadata?.string("title")?.takeIf(String::isNotBlank),
            preview = row.string("prompt").orEmpty(),
            workingDirectory = row.string("cwd") ?: row.string("workspaceRoot"),
            model = row.string("model"),
            createdAtEpochSeconds = epoch(row.string("startedAt") ?: row.string("started_at")),
            updatedAtEpochSeconds = epoch(
                row.string("updatedAt") ?: row.string("updated_at") ?: row.string("endedAt"),
            ),
            state = when (status) {
                "running", "active" -> AgentSessionState.RUNNING
                "failed", "error" -> AgentSessionState.FAILED
                "completed", "cancelled", "aborted" -> AgentSessionState.IDLE
                else -> AgentSessionState.UNKNOWN
            },
            canAcceptInput = status != "running" && status != "active",
            metadata = buildMap {
                (row.string("messagesPath") ?: row.string("messages_path"))?.let {
                    put("cline.messagesPath", it)
                }
                row.string("provider")?.let { put("cline.provider", it) }
                row.string("source")?.let { put("cline.source", it) }
            },
        )
    }

    fun withState(session: AgentSession, state: AgentSessionState): AgentSession = session.copy(
        state = state,
        canAcceptInput = state != AgentSessionState.RUNNING &&
            state != AgentSessionState.WAITING_FOR_APPROVAL,
    )

    private fun epoch(value: String?): Long? =
        value?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() }
}
