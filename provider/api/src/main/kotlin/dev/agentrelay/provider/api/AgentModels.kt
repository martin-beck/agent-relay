package dev.agentrelay.provider.api

@JvmInline
value class AgentProviderId(val value: String) {
    init {
        require(value.matches(Regex("[a-z][a-z0-9.-]{1,63}"))) {
            "Provider id must be a stable lowercase identifier"
        }
    }

    override fun toString(): String = value
}

@JvmInline
value class AgentSessionId(val value: String) {
    init {
        require(value.isNotBlank()) { "Session id must not be blank" }
    }

    override fun toString(): String = value
}

@JvmInline
value class AgentTurnId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class AgentApprovalId(val value: String) {
    override fun toString(): String = value
}

enum class AgentCapability {
    SESSION_DISCOVERY,
    SESSION_START,
    SESSION_RESUME,
    SESSION_HISTORY,
    LIVE_STREAMING,
    ACTIVE_TURN_STEERING,
    TURN_INTERRUPT,
    APPROVALS,
    FILE_CHANGES,
    SESSION_FORK,
}

data class AgentProviderDescriptor(
    val id: AgentProviderId,
    val displayName: String,
    val providerVersion: String,
    val apiVersion: Int = AGENT_PROVIDER_API_VERSION,
    val capabilities: Set<AgentCapability>,
)

const val AGENT_PROVIDER_API_VERSION = 1

sealed interface ProviderReadiness {
    data class Ready(val version: String, val authenticatedAs: String? = null, val details: String? = null) :
        ProviderReadiness

    data class Missing(val installHint: String) : ProviderReadiness

    data class NeedsAuthentication(val version: String?, val loginHint: String) : ProviderReadiness

    data class Incompatible(val version: String?, val reason: String) : ProviderReadiness

    data class Failed(val reason: String, val recoverable: Boolean) : ProviderReadiness
}

enum class AgentSessionState {
    NOT_LOADED,
    IDLE,
    RUNNING,
    WAITING_FOR_APPROVAL,
    FAILED,
    UNKNOWN,
}

data class AgentSession(
    val id: AgentSessionId,
    val providerId: AgentProviderId,
    val title: String?,
    val preview: String,
    val workingDirectory: String?,
    val model: String?,
    val createdAtEpochSeconds: Long?,
    val updatedAtEpochSeconds: Long?,
    val state: AgentSessionState,
    val canAcceptInput: Boolean,
    val metadata: Map<String, String> = emptyMap(),
)

data class StartSessionOptions(
    val workingDirectory: String? = null,
    val model: String? = null,
    val providerOptions: Map<String, String> = emptyMap(),
)

enum class AgentMessageChannel {
    COMMENTARY,
    FINAL,
    PLAN,
    REASONING_SUMMARY,
    SYSTEM,
}

enum class AgentTranscriptRole {
    USER,
    AGENT,
    TOOL,
    SYSTEM,
}

data class AgentTranscriptEntry(
    val id: String,
    val sessionId: AgentSessionId,
    val turnId: AgentTurnId?,
    val role: AgentTranscriptRole,
    val channel: AgentMessageChannel?,
    val text: String,
    val createdAtEpochSeconds: Long? = null,
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank()) { "Transcript entry id must not be blank" }
    }
}

enum class AgentToolStatus {
    STARTED,
    COMPLETED,
    FAILED,
    DECLINED,
}

enum class AgentApprovalType {
    COMMAND,
    FILE_CHANGE,
    USER_INPUT,
    PERMISSION,
    EXTERNAL_TOOL,
}

enum class AgentApprovalDecision {
    APPROVE_ONCE,
    APPROVE_FOR_SESSION,
    SUBMIT,
    DECLINE,
    CANCEL,
}

data class AgentQuestionOption(val label: String, val description: String? = null)

data class AgentQuestion(
    val id: String,
    val header: String?,
    val prompt: String,
    val options: List<AgentQuestionOption> = emptyList(),
    val allowsOther: Boolean = true,
    val allowsMultiple: Boolean = false,
)

data class AgentApproval(
    val id: AgentApprovalId,
    val sessionId: AgentSessionId,
    val turnId: AgentTurnId?,
    val type: AgentApprovalType,
    val title: String,
    val description: String?,
    val command: String? = null,
    val workingDirectory: String? = null,
    val questions: List<AgentQuestion> = emptyList(),
    val availableDecisions: Set<AgentApprovalDecision> = AgentApprovalDecision.entries.toSet(),
)

enum class AgentFileChangeKind {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED,
    UNKNOWN,
}

data class AgentChangedFile(
    val remotePath: String,
    val kind: AgentFileChangeKind,
    val oldRemotePath: String? = null,
    val turnId: AgentTurnId? = null,
)

sealed interface AgentEvent {
    val sessionId: AgentSessionId

    data class TextDelta(
        override val sessionId: AgentSessionId,
        val turnId: AgentTurnId?,
        val itemId: String?,
        val channel: AgentMessageChannel,
        val text: String,
    ) : AgentEvent

    data class MessageCompleted(
        override val sessionId: AgentSessionId,
        val turnId: AgentTurnId?,
        val itemId: String?,
        val channel: AgentMessageChannel,
        val text: String,
    ) : AgentEvent

    data class ToolChanged(
        override val sessionId: AgentSessionId,
        val turnId: AgentTurnId?,
        val itemId: String,
        val toolName: String,
        val summary: String?,
        val status: AgentToolStatus,
    ) : AgentEvent

    data class ApprovalRequested(override val sessionId: AgentSessionId, val approval: AgentApproval) : AgentEvent

    data class FileChanged(override val sessionId: AgentSessionId, val file: AgentChangedFile) : AgentEvent

    data class TurnCompleted(
        override val sessionId: AgentSessionId,
        val turnId: AgentTurnId?,
        val successful: Boolean,
        val errorMessage: String?,
    ) : AgentEvent

    data class SessionStateChanged(override val sessionId: AgentSessionId, val state: AgentSessionState) : AgentEvent

    data class Error(override val sessionId: AgentSessionId, val message: String, val recoverable: Boolean) : AgentEvent
}
