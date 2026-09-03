package dev.agentrelay.connection.api

import dev.agentrelay.provider.api.RemoteAgentRuntime
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

@JvmInline
value class ConnectionProviderId(val value: String) {
    init {
        require(value.matches(Regex("[a-z][a-z0-9.-]{1,63}"))) {
            "Connection provider id must be a stable lowercase identifier"
        }
    }

    override fun toString(): String = value
}

@JvmInline
value class ConnectionProfileId(val value: String) {
    init {
        require(value.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}"))) {
            "Connection profile id must be a stable identifier"
        }
    }

    override fun toString(): String = value
}

@JvmInline
value class ConnectionChallengeId(val value: String) {
    init {
        require(value.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}"))) {
            "Connection challenge id must be a stable identifier"
        }
    }

    override fun toString(): String = value
}

enum class ConnectionCapability {
    PROFILE_MANAGEMENT,
    SECRET_AUTHENTICATION,
    SERVER_IDENTITY_VERIFICATION,
    HEARTBEAT,
    AUTOMATIC_RECONNECT,
    MULTIPLEXED_PROCESSES,
    BACKGROUND_RECOVERY,
}

data class ConnectionProviderDescriptor(
    val id: ConnectionProviderId,
    val displayName: String,
    val providerVersion: String,
    val capabilities: Set<ConnectionCapability>,
) {
    init {
        require(displayName.isNotBlank()) { "Connection provider display name must not be blank" }
        require(providerVersion.isNotBlank()) { "Connection provider version must not be blank" }
    }
}

data class ConnectionProfileSummary(
    val id: ConnectionProfileId,
    val providerId: ConnectionProviderId,
    val label: String,
    val target: String,
    val authenticationLabel: String?,
) {
    init {
        require(label.isNotBlank()) { "Connection profile label must not be blank" }
        require(target.isNotBlank()) { "Connection target must not be blank" }
    }
}

enum class ConnectionPhase {
    PREPARING,
    OPENING_TRANSPORT,
    VERIFYING_SERVER_IDENTITY,
    AUTHENTICATING,
}

enum class ConnectionIdentityDisposition {
    UNKNOWN,
    CHANGED,
}

data class ConnectionIdentityChallenge(
    val id: ConnectionChallengeId,
    val endpoint: String,
    val algorithm: String,
    val sha256Fingerprint: String,
    val disposition: ConnectionIdentityDisposition,
    val previouslyTrustedFingerprints: List<String>,
) {
    init {
        require(endpoint.isNotBlank()) { "Identity challenge endpoint must not be blank" }
        require(algorithm.isNotBlank()) { "Identity challenge algorithm must not be blank" }
        require(sha256Fingerprint.startsWith("SHA256:")) { "Identity fingerprint must use SHA-256" }
        require(
            disposition == ConnectionIdentityDisposition.UNKNOWN ||
                previouslyTrustedFingerprints.isNotEmpty(),
        ) { "A changed identity must show the previously trusted fingerprint" }
    }
}

enum class ConnectionIdentityDecision {
    TRUST_FIRST_USE,
    REPLACE_CHANGED,
    REJECT,
}

enum class ConnectionFailureCategory {
    AUTHENTICATION,
    NETWORK,
    SERVER_IDENTITY,
    CONFIGURATION,
    CREDENTIAL_UNAVAILABLE,
    REMOTE_PROCESS_EXIT,
    BACKGROUND_SUSPENSION,
    PROVIDER_UNAVAILABLE,
    UNKNOWN,
}

enum class ConnectionFailureMessageKind {
    PROFILE_PREPARATION_FAILED,
}

sealed interface ConnectionFailureMessage {
    data class Generated(val kind: ConnectionFailureMessageKind) : ConnectionFailureMessage

    data class Verbatim(val text: String) : ConnectionFailureMessage {
        init {
            require(text.isNotBlank()) { "Failure message must not be blank" }
        }
    }
}

data class ConnectionFailure(
    val category: ConnectionFailureCategory,
    val code: String,
    val message: ConnectionFailureMessage,
    val recoverable: Boolean,
) {
    constructor(
        category: ConnectionFailureCategory,
        code: String,
        actionableMessage: String,
        recoverable: Boolean,
    ) : this(
        category = category,
        code = code,
        message = ConnectionFailureMessage.Verbatim(actionableMessage),
        recoverable = recoverable,
    )

    val actionableMessage: String
        get() = when (message) {
            is ConnectionFailureMessage.Generated -> code
            is ConnectionFailureMessage.Verbatim -> message.text
        }

    init {
        require(code.matches(Regex("[A-Z][A-Z0-9_]{2,63}"))) { "Failure code must be stable and redacted" }
    }
}

enum class ConnectionDisconnectReason {
    NOT_CONNECTED,
    USER_REQUESTED,
    AUTHENTICATION_FAILED,
    NETWORK_LOST,
    SERVER_IDENTITY_REJECTED,
    CREDENTIAL_UNAVAILABLE,
    BACKGROUND_SUSPENDED,
    RETRY_LIMIT_REACHED,
    PROVIDER_STOPPED,
}

sealed interface ConnectionState {
    data class Disconnected(
        val reason: ConnectionDisconnectReason,
        val atEpochMillis: Long,
    ) : ConnectionState

    data class Connecting(
        val attempt: Int,
        val phase: ConnectionPhase,
        val startedAtEpochMillis: Long,
    ) : ConnectionState

    data class AwaitingIdentityTrust(
        val challenge: ConnectionIdentityChallenge,
        val atEpochMillis: Long,
    ) : ConnectionState

    data class Connected(
        val connectedAtEpochMillis: Long,
        val lastHeartbeatAtEpochMillis: Long?,
        val lastLatency: Duration?,
    ) : ConnectionState

    data class Reconnecting(
        val attempt: Int,
        val delay: Duration,
        val retryAtEpochMillis: Long,
        val lastFailure: ConnectionFailure,
    ) : ConnectionState

    data class Failed(
        val failure: ConnectionFailure,
        val atEpochMillis: Long,
    ) : ConnectionState
}

data class ConnectionDiagnosticSnapshot(
    val providerId: ConnectionProviderId,
    val profileId: ConnectionProfileId,
    val target: String,
    val state: ConnectionState,
)

interface ManagedConnection {
    val providerId: ConnectionProviderId
    val profileId: ConnectionProfileId
    val state: StateFlow<ConnectionState>

    fun runtimeOrNull(): RemoteAgentRuntime?

    fun connect()

    suspend fun resolveIdentityChallenge(
        challengeId: ConnectionChallengeId,
        decision: ConnectionIdentityDecision,
    ): Boolean

    suspend fun disconnect()

    suspend fun suspendForBackground()

    fun resumeFromBackground()

    fun diagnosticSnapshot(): ConnectionDiagnosticSnapshot
}
