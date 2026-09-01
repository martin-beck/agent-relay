package dev.agentrelay.ssh.api

import dev.agentrelay.connection.api.ConnectionCapability
import dev.agentrelay.connection.api.ConnectionChallengeId
import dev.agentrelay.connection.api.ConnectionDiagnosticSnapshot
import dev.agentrelay.connection.api.ConnectionDisconnectReason
import dev.agentrelay.connection.api.ConnectionFailure
import dev.agentrelay.connection.api.ConnectionFailureCategory
import dev.agentrelay.connection.api.ConnectionIdentityChallenge
import dev.agentrelay.connection.api.ConnectionIdentityDecision
import dev.agentrelay.connection.api.ConnectionIdentityDisposition
import dev.agentrelay.connection.api.ConnectionPhase
import dev.agentrelay.connection.api.ConnectionProfileEditor
import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProfileManager
import dev.agentrelay.connection.api.ConnectionProfileSaveResult
import dev.agentrelay.connection.api.ConnectionProfileSummary
import dev.agentrelay.connection.api.ConnectionProfileUpdate
import dev.agentrelay.connection.api.ConnectionProvider
import dev.agentrelay.connection.api.ConnectionProviderDescriptor
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.connection.api.ConnectionState
import dev.agentrelay.connection.api.ManagedConnection
import dev.agentrelay.provider.api.RemoteAgentRuntime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class SshConnectionProvider(
    private val profileStore: SshProfileStore,
    private val manager: SshConnectionManager,
    private val delegateProfileManager: ConnectionProfileManager,
    stateDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ConnectionProvider {
    override val profileManager: ConnectionProfileManager = object : ConnectionProfileManager {
        override suspend fun editor(profileId: ConnectionProfileId?): ConnectionProfileEditor =
            delegateProfileManager.editor(profileId)

        override suspend fun save(
            update: ConnectionProfileUpdate,
        ): ConnectionProfileSaveResult {
            val result = delegateProfileManager.save(update)
            update.profileId?.let { disconnectAndForget(it) }
            return result
        }

        override suspend fun delete(profileId: ConnectionProfileId) {
            disconnectAndForget(profileId)
            delegateProfileManager.delete(profileId)
        }
    }

    override val descriptor = ConnectionProviderDescriptor(
        id = ID,
        displayName = "Secure Shell",
        providerVersion = "1.0.0",
        capabilities = setOf(
            ConnectionCapability.PROFILE_MANAGEMENT,
            ConnectionCapability.SECRET_AUTHENTICATION,
            ConnectionCapability.SERVER_IDENTITY_VERIFICATION,
            ConnectionCapability.HEARTBEAT,
            ConnectionCapability.AUTOMATIC_RECONNECT,
            ConnectionCapability.MULTIPLEXED_PROCESSES,
            ConnectionCapability.BACKGROUND_RECOVERY,
        ),
    )

    private val scope = CoroutineScope(SupervisorJob() + stateDispatcher)
    private val connections = ConcurrentHashMap<ConnectionProfileId, SshManagedConnection>()

    override suspend fun profiles(): List<ConnectionProfileSummary> = profileStore.profiles().map {
        it.toSummary()
    }.sortedBy { it.label }

    override fun connection(profileId: ConnectionProfileId): ManagedConnection =
        connections.computeIfAbsent(profileId) {
            SshManagedConnection(manager.session(SshProfileId(it.value)))
        }

    override fun close() {
        connections.clear()
        scope.cancel()
        manager.close()
    }

    private suspend fun disconnectAndForget(profileId: ConnectionProfileId) {
        connections.remove(profileId)?.disconnect()
    }

    private inner class SshManagedConnection(
        private val delegate: SshManagedSession,
    ) : ManagedConnection {
        override val providerId: ConnectionProviderId = ID
        override val profileId = ConnectionProfileId(delegate.profileId.value)
        override val state: StateFlow<ConnectionState> = delegate.state
            .map { it.toConnectionState() }
            .stateIn(
                scope = scope,
                started = SharingStarted.Eagerly,
                initialValue = delegate.state.value.toConnectionState(),
            )

        override fun runtimeOrNull(): RemoteAgentRuntime? = delegate.runtimeOrNull()

        override fun connect() = delegate.connect()

        override suspend fun resolveIdentityChallenge(
            challengeId: ConnectionChallengeId,
            decision: ConnectionIdentityDecision,
        ): Boolean {
            val challenge = (
                delegate.state.value as? SshConnectionState.AwaitingHostKeyTrust
                )?.challenge ?: return false
            if (challenge.toConnectionChallenge().id != challengeId) {
                return false
            }
            return when (decision) {
                ConnectionIdentityDecision.TRUST_FIRST_USE -> delegate.approveHostKey(challenge)
                ConnectionIdentityDecision.REPLACE_CHANGED -> delegate.replaceChangedHostKey(challenge)
                ConnectionIdentityDecision.REJECT -> {
                    delegate.disconnect()
                    true
                }
            }
        }

        override suspend fun disconnect() = delegate.disconnect()

        override suspend fun suspendForBackground() = delegate.suspendForBackground()

        override fun resumeFromBackground() = delegate.resumeFromBackground()

        override fun diagnosticSnapshot(): ConnectionDiagnosticSnapshot {
            val snapshot = delegate.diagnosticSnapshot()
            return ConnectionDiagnosticSnapshot(
                providerId = providerId,
                profileId = profileId,
                target = snapshot.endpoint,
                state = snapshot.state.toConnectionState(),
            )
        }
    }

    private fun SshProfile.toSummary() = ConnectionProfileSummary(
        id = ConnectionProfileId(id.value),
        providerId = ID,
        label = label,
        target = endpoint.displayName,
        authenticationLabel = when (authentication) {
            is SshAuthentication.Password -> "Password"
            is SshAuthentication.ImportedKey -> "Imported key"
            is SshAuthentication.AgentBacked -> "Agent-backed key"
        },
    )

    private fun SshConnectionState.toConnectionState(): ConnectionState = when (this) {
        is SshConnectionState.Disconnected -> ConnectionState.Disconnected(
            reason = reason.toConnectionReason(),
            atEpochMillis = atEpochMillis,
        )

        is SshConnectionState.Connecting -> ConnectionState.Connecting(
            attempt = attempt,
            phase = phase.toConnectionPhase(),
            startedAtEpochMillis = startedAtEpochMillis,
        )

        is SshConnectionState.AwaitingHostKeyTrust -> ConnectionState.AwaitingIdentityTrust(
            challenge = challenge.toConnectionChallenge(),
            atEpochMillis = atEpochMillis,
        )

        is SshConnectionState.Connected -> ConnectionState.Connected(
            connectedAtEpochMillis = connectedAtEpochMillis,
            lastHeartbeatAtEpochMillis = lastHeartbeatAtEpochMillis,
            lastLatency = lastLatency,
        )

        is SshConnectionState.Reconnecting -> ConnectionState.Reconnecting(
            attempt = attempt,
            delay = delay,
            retryAtEpochMillis = retryAtEpochMillis,
            lastFailure = lastFailure.toConnectionFailure(),
        )

        is SshConnectionState.Failed -> ConnectionState.Failed(
            failure = failure.toConnectionFailure(),
            atEpochMillis = atEpochMillis,
        )
    }

    private fun SshHostKeyChallenge.toConnectionChallenge(): ConnectionIdentityChallenge =
        ConnectionIdentityChallenge(
            id = stableChallengeId(),
            endpoint = candidate.endpoint.displayName,
            algorithm = candidate.algorithm,
            sha256Fingerprint = candidate.sha256Fingerprint,
            disposition = when (disposition) {
                SshHostKeyDisposition.UNKNOWN -> ConnectionIdentityDisposition.UNKNOWN
                SshHostKeyDisposition.CHANGED -> ConnectionIdentityDisposition.CHANGED
            },
            previouslyTrustedFingerprints = trustedFingerprints,
        )

    private fun SshHostKeyChallenge.stableChallengeId(): ConnectionChallengeId {
        val evidence = buildString {
            append(candidate.endpoint.host)
            append(':')
            append(candidate.endpoint.port)
            append(':')
            append(candidate.algorithm)
            append(':')
            append(candidate.publicKeyBase64)
            append(':')
            trustedFingerprints.sorted().forEach {
                append(it)
                append(',')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(evidence.encodeToByteArray())
        val suffix = digest.take(16).joinToString("") { "%02x".format(it) }
        return ConnectionChallengeId("ssh-$suffix")
    }

    private fun SshConnectPhase.toConnectionPhase(): ConnectionPhase = when (this) {
        SshConnectPhase.OPENING_SOCKET -> ConnectionPhase.OPENING_TRANSPORT
        SshConnectPhase.VERIFYING_HOST_KEY -> ConnectionPhase.VERIFYING_SERVER_IDENTITY
        SshConnectPhase.AUTHENTICATING -> ConnectionPhase.AUTHENTICATING
    }

    private fun SshDisconnectReason.toConnectionReason(): ConnectionDisconnectReason = when (this) {
        SshDisconnectReason.NOT_CONNECTED -> ConnectionDisconnectReason.NOT_CONNECTED
        SshDisconnectReason.USER_REQUESTED -> ConnectionDisconnectReason.USER_REQUESTED
        SshDisconnectReason.AUTHENTICATION_FAILED -> ConnectionDisconnectReason.AUTHENTICATION_FAILED
        SshDisconnectReason.NETWORK_LOST -> ConnectionDisconnectReason.NETWORK_LOST
        SshDisconnectReason.HOST_KEY_REJECTED -> ConnectionDisconnectReason.SERVER_IDENTITY_REJECTED
        SshDisconnectReason.CREDENTIAL_UNAVAILABLE -> ConnectionDisconnectReason.CREDENTIAL_UNAVAILABLE
        SshDisconnectReason.ANDROID_BACKGROUND_SUSPENDED -> ConnectionDisconnectReason.BACKGROUND_SUSPENDED
        SshDisconnectReason.RETRY_LIMIT_REACHED -> ConnectionDisconnectReason.RETRY_LIMIT_REACHED
    }

    private fun SshFailure.toConnectionFailure() = ConnectionFailure(
        category = when (category) {
            SshFailureCategory.AUTHENTICATION -> ConnectionFailureCategory.AUTHENTICATION
            SshFailureCategory.NETWORK -> ConnectionFailureCategory.NETWORK
            SshFailureCategory.HOST_KEY -> ConnectionFailureCategory.SERVER_IDENTITY
            SshFailureCategory.CONFIGURATION -> ConnectionFailureCategory.CONFIGURATION
            SshFailureCategory.CREDENTIAL_UNAVAILABLE -> ConnectionFailureCategory.CREDENTIAL_UNAVAILABLE
            SshFailureCategory.REMOTE_PROCESS_EXIT -> ConnectionFailureCategory.REMOTE_PROCESS_EXIT
            SshFailureCategory.ANDROID_BACKGROUND_SUSPENSION -> ConnectionFailureCategory.BACKGROUND_SUSPENSION
            SshFailureCategory.UNKNOWN -> ConnectionFailureCategory.UNKNOWN
        },
        code = code,
        actionableMessage = actionableMessage,
        recoverable = recoverable,
    )

    companion object {
        val ID = ConnectionProviderId("ssh.secure-shell")
    }
}
