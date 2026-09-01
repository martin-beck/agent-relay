package dev.agentrelay.connection.local

import dev.agentrelay.connection.api.ConnectionCapability
import dev.agentrelay.connection.api.ConnectionChallengeId
import dev.agentrelay.connection.api.ConnectionDiagnosticSnapshot
import dev.agentrelay.connection.api.ConnectionDisconnectReason
import dev.agentrelay.connection.api.ConnectionIdentityDecision
import dev.agentrelay.connection.api.ConnectionPhase
import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProfileSummary
import dev.agentrelay.connection.api.ConnectionProvider
import dev.agentrelay.connection.api.ConnectionProviderDescriptor
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.connection.api.ConnectionState
import dev.agentrelay.connection.api.ManagedConnection
import dev.agentrelay.provider.api.RemoteAgentRuntime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

fun interface LocalClock {
    fun epochMillis(): Long
}

class LocalConnectionProvider(
    private val workingRoot: File,
    private val targetLabel: String = "This device",
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: LocalClock = LocalClock(System::currentTimeMillis),
) : ConnectionProvider {
    override val descriptor = ConnectionProviderDescriptor(
        id = PROVIDER_ID,
        displayName = "Local",
        providerVersion = "1.0.0",
        capabilities = setOf(
            ConnectionCapability.MULTIPLEXED_PROCESSES,
            ConnectionCapability.BACKGROUND_RECOVERY,
        ),
    )

    private val managedConnection = LocalManagedConnection()
    private var closed = false

    init {
        require(targetLabel.isNotBlank()) { "Local connection target must not be blank" }
    }

    override suspend fun profiles(): List<ConnectionProfileSummary> = listOf(
        ConnectionProfileSummary(
            id = PROFILE_ID,
            providerId = PROVIDER_ID,
            label = targetLabel,
            target = targetLabel,
            authenticationLabel = null,
        ),
    )

    override fun connection(profileId: ConnectionProfileId): ManagedConnection {
        require(profileId == PROFILE_ID) { "Unknown local connection profile" }
        return managedConnection
    }

    override fun close() {
        synchronized(managedConnection.monitor) {
            if (closed) {
                return
            }
            closed = true
            managedConnection.closeRuntime()
            managedConnection.mutableState.value = ConnectionState.Disconnected(
                reason = ConnectionDisconnectReason.PROVIDER_STOPPED,
                atEpochMillis = clock.epochMillis(),
            )
        }
    }

    private inner class LocalManagedConnection : ManagedConnection {
        val monitor = Any()
        val mutableState = MutableStateFlow<ConnectionState>(
            ConnectionState.Disconnected(
                reason = ConnectionDisconnectReason.NOT_CONNECTED,
                atEpochMillis = clock.epochMillis(),
            ),
        )
        private var runtime: LocalProcessRuntime? = null

        override val providerId: ConnectionProviderId = PROVIDER_ID
        override val profileId: ConnectionProfileId = PROFILE_ID
        override val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

        override fun runtimeOrNull(): RemoteAgentRuntime? = synchronized(monitor) {
            runtime
        }

        override fun connect() {
            synchronized(monitor) {
                if (closed) {
                    mutableState.value = ConnectionState.Disconnected(
                        reason = ConnectionDisconnectReason.PROVIDER_STOPPED,
                        atEpochMillis = clock.epochMillis(),
                    )
                    return
                }
                if (runtime != null) {
                    return
                }
                mutableState.value = ConnectionState.Connecting(
                    attempt = 1,
                    phase = ConnectionPhase.PREPARING,
                    startedAtEpochMillis = clock.epochMillis(),
                )
                runtime = LocalProcessRuntime(
                    workingRoot = workingRoot,
                    dispatcher = dispatcher,
                )
                mutableState.value = ConnectionState.Connected(
                    connectedAtEpochMillis = clock.epochMillis(),
                    lastHeartbeatAtEpochMillis = null,
                    lastLatency = null,
                )
            }
        }

        override suspend fun resolveIdentityChallenge(
            challengeId: ConnectionChallengeId,
            decision: ConnectionIdentityDecision,
        ): Boolean = false

        override suspend fun disconnect() {
            transitionToDisconnected(ConnectionDisconnectReason.USER_REQUESTED)
        }

        override suspend fun suspendForBackground() {
            transitionToDisconnected(ConnectionDisconnectReason.BACKGROUND_SUSPENDED)
        }

        override fun resumeFromBackground() {
            val shouldResume = state.value.let {
                it is ConnectionState.Disconnected &&
                    it.reason == ConnectionDisconnectReason.BACKGROUND_SUSPENDED
            }
            if (shouldResume) {
                connect()
            }
        }

        override fun diagnosticSnapshot(): ConnectionDiagnosticSnapshot =
            ConnectionDiagnosticSnapshot(
                providerId = providerId,
                profileId = profileId,
                target = targetLabel,
                state = state.value,
            )

        fun closeRuntime() {
            runtime?.close()
            runtime = null
        }

        private fun transitionToDisconnected(reason: ConnectionDisconnectReason) {
            synchronized(monitor) {
                closeRuntime()
                mutableState.value = ConnectionState.Disconnected(
                    reason = reason,
                    atEpochMillis = clock.epochMillis(),
                )
            }
        }
    }

    companion object {
        val PROVIDER_ID = ConnectionProviderId("local.device")
        val PROFILE_ID = ConnectionProfileId("local")
    }
}
