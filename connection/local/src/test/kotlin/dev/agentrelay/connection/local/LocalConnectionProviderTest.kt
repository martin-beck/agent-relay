package dev.agentrelay.connection.local

import dev.agentrelay.connection.api.ConnectionCapability
import dev.agentrelay.connection.api.ConnectionChallengeId
import dev.agentrelay.connection.api.ConnectionDisconnectReason
import dev.agentrelay.connection.api.ConnectionIdentityDecision
import dev.agentrelay.connection.api.ConnectionState
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalConnectionProviderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun localProviderAdvertisesOnlyLocalCapabilities() = runTest {
        val provider = LocalConnectionProvider(
            workingRoot = temporaryFolder.root,
            clock = LocalClock { 42L },
        )

        assertEquals("local.device", provider.descriptor.id.value)
        assertEquals(
            setOf(
                ConnectionCapability.MULTIPLEXED_PROCESSES,
                ConnectionCapability.BACKGROUND_RECOVERY,
            ),
            provider.descriptor.capabilities,
        )
        assertEquals("This device", provider.profiles().single().target)

        val connection = provider.connection(LocalConnectionProvider.PROFILE_ID)
        connection.connect()
        assertIs<ConnectionState.Connected>(connection.state.value)
        assertNotNull(connection.runtimeOrNull())

        assertTrue(
            !connection.resolveIdentityChallenge(
                ConnectionChallengeId("unused"),
                ConnectionIdentityDecision.TRUST_FIRST_USE,
            ),
        )
        connection.disconnect()
        assertEquals(
            ConnectionDisconnectReason.USER_REQUESTED,
            assertIs<ConnectionState.Disconnected>(connection.state.value).reason,
        )
        assertNull(connection.runtimeOrNull())
        provider.close()
    }

    @Test
    fun backgroundSuspensionClosesProcessesAndCanReconnect() = runTest {
        val provider = LocalConnectionProvider(temporaryFolder.root)
        val connection = provider.connection(LocalConnectionProvider.PROFILE_ID)
        connection.connect()
        val firstRuntime = connection.runtimeOrNull()

        connection.suspendForBackground()
        assertEquals(
            ConnectionDisconnectReason.BACKGROUND_SUSPENDED,
            assertIs<ConnectionState.Disconnected>(connection.state.value).reason,
        )
        assertNull(connection.runtimeOrNull())

        connection.resumeFromBackground()
        assertIs<ConnectionState.Connected>(connection.state.value)
        assertNotNull(connection.runtimeOrNull())
        assertTrue(firstRuntime !== connection.runtimeOrNull())
        provider.close()
        assertEquals(
            ConnectionDisconnectReason.PROVIDER_STOPPED,
            assertIs<ConnectionState.Disconnected>(connection.state.value).reason,
        )
    }
}
