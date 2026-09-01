package dev.agentrelay.provider.clinecli

import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentSession
import dev.agentrelay.provider.api.StartSessionOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

internal interface ClineClient {
    val sessionId: String
    val currentModel: String?
    val calls: Flow<ClineAcpCall>

    suspend fun prompt(text: String): JsonObject

    suspend fun cancel()

    suspend fun respondToPermission(
        call: ClineAcpCall,
        decision: AgentApprovalDecision,
        optionIds: Map<AgentApprovalDecision, String>,
    )

    suspend fun rejectUnsupported(call: ClineAcpCall)

    suspend fun close()
}

internal interface ClineClientStarter {
    suspend fun startNew(options: StartSessionOptions): ClineClient

    suspend fun resume(session: AgentSession): ClineClient
}
