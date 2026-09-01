package dev.agentrelay.ssh.api

import dev.agentrelay.provider.api.RemoteAgentRuntime
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

enum class SshConnectPhase {
    OPENING_SOCKET,
    VERIFYING_HOST_KEY,
    AUTHENTICATING,
}

fun interface SshConnectPhaseListener {
    fun onPhase(phase: SshConnectPhase)
}

interface SshTransportConnection : AutoCloseable {
    val runtime: RemoteAgentRuntime
    val isConnected: Boolean

    suspend fun heartbeat(): Duration

    override fun close()
}

interface SshConnector {
    suspend fun connect(
        profile: SshProfile,
        authentication: ResolvedSshAuthentication,
        trustedHostKeys: List<SshHostKey>,
        phaseListener: SshConnectPhaseListener = SshConnectPhaseListener {},
    ): SshTransportConnection
}

class SshHostKeyApprovalRequiredException(val challenge: SshHostKeyChallenge) :
    Exception(
        when (challenge.disposition) {
            SshHostKeyDisposition.UNKNOWN -> "SSH host key has not been trusted"
            SshHostKeyDisposition.CHANGED -> "SSH host key changed"
        },
    )

enum class SshFailureCategory {
    AUTHENTICATION,
    NETWORK,
    HOST_KEY,
    CONFIGURATION,
    CREDENTIAL_UNAVAILABLE,
    REMOTE_PROCESS_EXIT,
    ANDROID_BACKGROUND_SUSPENSION,
    UNKNOWN,
}

data class SshFailure(
    val category: SshFailureCategory,
    val code: String,
    val actionableMessage: String,
    val recoverable: Boolean,
) {
    init {
        require(code.matches(Regex("[A-Z][A-Z0-9_]{2,63}"))) { "Failure code must be stable and redacted" }
        require(actionableMessage.isNotBlank()) { "Failure message must not be blank" }
    }
}

open class SshConnectionException(
    val failure: SshFailure,
    cause: Throwable? = null,
) : Exception(failure.actionableMessage, cause)

enum class SshDisconnectReason {
    NOT_CONNECTED,
    USER_REQUESTED,
    AUTHENTICATION_FAILED,
    NETWORK_LOST,
    HOST_KEY_REJECTED,
    CREDENTIAL_UNAVAILABLE,
    ANDROID_BACKGROUND_SUSPENDED,
    RETRY_LIMIT_REACHED,
}

sealed interface SshConnectionState {
    data class Disconnected(
        val reason: SshDisconnectReason,
        val atEpochMillis: Long,
    ) : SshConnectionState

    data class Connecting(
        val attempt: Int,
        val phase: SshConnectPhase,
        val startedAtEpochMillis: Long,
    ) : SshConnectionState

    data class AwaitingHostKeyTrust(
        val challenge: SshHostKeyChallenge,
        val atEpochMillis: Long,
    ) : SshConnectionState

    data class Connected(
        val connectedAtEpochMillis: Long,
        val lastHeartbeatAtEpochMillis: Long?,
        val lastLatency: Duration?,
    ) : SshConnectionState

    data class Reconnecting(
        val attempt: Int,
        val delay: Duration,
        val retryAtEpochMillis: Long,
        val lastFailure: SshFailure,
    ) : SshConnectionState

    data class Failed(
        val failure: SshFailure,
        val atEpochMillis: Long,
    ) : SshConnectionState
}

data class SshSessionSnapshot(
    val profileId: SshProfileId,
    val endpoint: String,
    val state: SshConnectionState,
)

interface SshManagedSession {
    val profileId: SshProfileId
    val state: StateFlow<SshConnectionState>

    fun runtimeOrNull(): RemoteAgentRuntime?

    fun connect()

    suspend fun approveHostKey(challenge: SshHostKeyChallenge): Boolean

    suspend fun replaceChangedHostKey(challenge: SshHostKeyChallenge): Boolean

    suspend fun disconnect()

    suspend fun suspendForBackground()

    fun resumeFromBackground()

    fun diagnosticSnapshot(): SshSessionSnapshot
}

fun Throwable.toSshFailure(): SshFailure = when (this) {
    is SshConnectionException -> failure
    is MissingSshCredentialException -> SshFailure(
        category = SshFailureCategory.CREDENTIAL_UNAVAILABLE,
        code = "SSH_CREDENTIAL_UNAVAILABLE",
        actionableMessage = "The saved SSH credential is unavailable. Re-enter it and try again.",
        recoverable = false,
    )

    else -> SshFailure(
        category = SshFailureCategory.UNKNOWN,
        code = "SSH_UNEXPECTED_FAILURE",
        actionableMessage = "The SSH connection failed unexpectedly. Review the redacted diagnostics and retry.",
        recoverable = true,
    )
}
