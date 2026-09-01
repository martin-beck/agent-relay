package dev.agentrelay.connection.api

import dev.agentrelay.provider.api.RemoteAgentRuntime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ConnectionProviderRegistryTest {
    @Test
    fun unrelatedConnectionProvidersShareOneRegistryContract() = runTest {
        val ssh = provider(
            id = "ssh.secure-shell",
            name = "Secure Shell",
            profileId = "remote",
            target = "server.example.test",
        )
        val local = provider(
            id = "local.device",
            name = "This device",
            profileId = "local",
            target = "Android device",
        )
        val registry = ConnectionProviderRegistry(listOf(ssh, local))

        assertEquals(listOf("Secure Shell", "This device"), registry.descriptors().map { it.displayName })
        assertEquals(
            listOf("local.device", "ssh.secure-shell"),
            registry.profiles().map { it.providerId.value },
        )
        assertEquals(local, registry.provider(ConnectionProviderId("local.device")))
        registry.close()
        assertEquals(1, ssh.closeCount)
        assertEquals(1, local.closeCount)
    }

    @Test
    fun duplicateProviderIdsAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            ConnectionProviderRegistry(
                listOf(
                    provider("local.device", "First", "first", "one"),
                    provider("local.device", "Second", "second", "two"),
                ),
            )
        }
    }

    private fun provider(
        id: String,
        name: String,
        profileId: String,
        target: String,
    ) = FakeProvider(
        descriptor = ConnectionProviderDescriptor(
            id = ConnectionProviderId(id),
            displayName = name,
            providerVersion = "1.0.0",
            capabilities = emptySet(),
        ),
        summary = ConnectionProfileSummary(
            id = ConnectionProfileId(profileId),
            providerId = ConnectionProviderId(id),
            label = name,
            target = target,
            authenticationLabel = null,
        ),
    )

    private class FakeProvider(
        override val descriptor: ConnectionProviderDescriptor,
        private val summary: ConnectionProfileSummary,
    ) : ConnectionProvider {
        var closeCount = 0

        override suspend fun profiles(): List<ConnectionProfileSummary> = listOf(summary)

        override fun connection(profileId: ConnectionProfileId): ManagedConnection =
            object : ManagedConnection {
                override val providerId = descriptor.id
                override val profileId = profileId
                override val state: StateFlow<ConnectionState> = MutableStateFlow(
                    ConnectionState.Disconnected(ConnectionDisconnectReason.NOT_CONNECTED, 0L),
                )

                override fun runtimeOrNull(): RemoteAgentRuntime? = null
                override fun connect() = Unit
                override suspend fun resolveIdentityChallenge(
                    challengeId: ConnectionChallengeId,
                    decision: ConnectionIdentityDecision,
                ): Boolean = false

                override suspend fun disconnect() = Unit
                override suspend fun suspendForBackground() = Unit
                override fun resumeFromBackground() = Unit
                override fun diagnosticSnapshot() = ConnectionDiagnosticSnapshot(
                    descriptor.id,
                    profileId,
                    summary.target,
                    state.value,
                )
            }

        override fun close() {
            closeCount += 1
        }
    }
}
