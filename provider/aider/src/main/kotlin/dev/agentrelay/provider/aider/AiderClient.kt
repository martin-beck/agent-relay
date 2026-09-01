package dev.agentrelay.provider.aider

import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.provider.api.AgentSession
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.StartSessionOptions

internal data class AiderFileResult(
    val remotePath: String,
    val kind: AgentFileChangeKind,
)

internal data class AiderPromptResult(
    val text: String,
    val files: List<AiderFileResult>,
)

internal interface AiderClient {
    val sessionId: AgentSessionId
    val currentModel: String?
    val stateDirectory: String
    val chatHistoryPath: String
    val filesJson: String

    suspend fun prompt(text: String): AiderPromptResult

    suspend fun close()
}

internal interface AiderClientStarter {
    suspend fun startNew(
        sessionId: AgentSessionId,
        options: StartSessionOptions,
    ): AiderClient

    suspend fun resume(session: AgentSession): AiderClient
}
