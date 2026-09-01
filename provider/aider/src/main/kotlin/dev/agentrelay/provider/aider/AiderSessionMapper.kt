package dev.agentrelay.provider.aider

import dev.agentrelay.provider.api.AgentSession
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import kotlinx.serialization.json.JsonObject

internal object AiderSessionMapper {
    fun fromState(row: JsonObject): AgentSession {
        val id = requireNotNull(row.string("id")) { "Aider state is missing its session id" }
        val stateDirectory = requireNotNull(row.string("stateDirectory")) {
            "Aider state is missing its state directory"
        }
        val chatHistoryPath = requireNotNull(row.string("chatHistoryPath")) {
            "Aider state is missing its chat history path"
        }
        return AgentSession(
            id = AgentSessionId(id),
            providerId = AIDER_PROVIDER_ID,
            title = row.string("title"),
            preview = row.string("preview").orEmpty(),
            workingDirectory = row.string("workingDirectory"),
            model = row.string("model"),
            createdAtEpochSeconds = row.long("createdAt"),
            updatedAtEpochSeconds = row.long("updatedAt"),
            state = AgentSessionState.IDLE,
            canAcceptInput = true,
            metadata = buildMap {
                put("aider.stateDirectory", stateDirectory)
                put("aider.chatHistoryPath", chatHistoryPath)
                row.string("files")?.let { put("aider.files", it) }
                row.string("config")?.let { put("aider.config", it) }
                row.objectValue("changedFiles")?.toString()?.let {
                    put("aider.changedFiles", it)
                }
            },
        )
    }

    fun withState(session: AgentSession, state: AgentSessionState): AgentSession =
        session.copy(
            state = state,
            canAcceptInput = state != AgentSessionState.RUNNING,
        )
}
