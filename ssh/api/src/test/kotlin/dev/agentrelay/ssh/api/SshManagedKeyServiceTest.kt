package dev.agentrelay.ssh.api

import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

class SshManagedKeyServiceTest {
    @Test
    fun ensureKeyCreatesOnePersistentPerProfileIdentityAndReusesIt() = runTest {
        val fixture = Fixture()

        val first = fixture.service.ensureKey(PROFILE.id)
        val second = fixture.service.ensureKey(PROFILE.id)

        assertEquals(first, second)
        assertEquals("profile.device-key.v1", first.keyId)
        assertEquals(listOf(first.keyId), fixture.agentKeys.created)
        assertEquals(first.keyId, fixture.profiles.profile(PROFILE.id)?.appManagedKeyId)
        assertEquals(101L, fixture.profiles.profile(PROFILE.id)?.updatedAtEpochMillis)
    }

    @Test
    fun installUsesCurrentAuthenticationAndAConstrainedIdempotentAuthorizedKeysCommand() = runTest {
        val fixture = Fixture()

        val result = fixture.service.installPublicKey(PROFILE.id)

        val route = fixture.connector.routes.single()
        assertIs<ResolvedSshAuthentication.Password>(route.destination.authentication)
        assertTrue(route.jumpHosts.isEmpty())
        val command = fixture.connection.commands.single()
        assertEquals("sh", command.program)
        assertEquals("-c", command.arguments.first())
        val script = command.arguments.last()
        assertTrue(script.contains("ecdsa-sha2-nistp256 AAAA"))
        assertTrue(script.contains("grep -qxF"))
        assertTrue(script.contains("chmod 700"))
        assertTrue(script.contains("chmod 600"))
        assertTrue(script.contains("[ -L"))
        assertFalse(script.contains("test comment"))
        assertFalse(script.contains("PRIVATE"))
        assertTrue(result.notice.contains("installed"))
        assertTrue(fixture.connection.closed)
    }

    @Test
    fun passwordlessProbeOverridesOnlyDestinationAuthenticationAndExecutesHeartbeat() = runTest {
        val fixture = Fixture()

        val result = fixture.service.verifyPasswordlessLogin(PROFILE.id)

        val destination = fixture.connector.routes.single().destination
        val authentication = assertIs<ResolvedSshAuthentication.AgentBacked>(
            destination.authentication,
        )
        assertEquals("profile.device-key.v1", authentication.keyId)
        assertEquals(1, fixture.connection.heartbeats)
        assertTrue(result.notice.contains("succeeded"))
        assertTrue(fixture.connection.closed)
    }

    @Test
    fun keyOperationsPreserveJumpAuthenticationAndOverrideOnlyTheProbeDestination() = runTest {
        val installFixture = Fixture(ROUTED_PROFILE, listOf(JUMP_PROFILE))

        installFixture.service.installPublicKey(ROUTED_PROFILE.id)

        val installRoute = installFixture.connector.routes.single()
        assertEquals(listOf(JUMP_PROFILE.id), installRoute.jumpHosts.map { it.profile.id })
        assertIs<ResolvedSshAuthentication.Password>(
            installRoute.jumpHosts.single().authentication,
        )
        assertIs<ResolvedSshAuthentication.Password>(installRoute.destination.authentication)

        val verifyFixture = Fixture(ROUTED_PROFILE, listOf(JUMP_PROFILE))

        verifyFixture.service.verifyPasswordlessLogin(ROUTED_PROFILE.id)

        val verifyRoute = verifyFixture.connector.routes.single()
        assertEquals(listOf(JUMP_PROFILE.id), verifyRoute.jumpHosts.map { it.profile.id })
        assertIs<ResolvedSshAuthentication.Password>(
            verifyRoute.jumpHosts.single().authentication,
        )
        val destinationAuthentication = assertIs<ResolvedSshAuthentication.AgentBacked>(
            verifyRoute.destination.authentication,
        )
        assertEquals(
            "routed-profile.device-key.v1",
            destinationAuthentication.keyId,
        )
    }

    @Test
    fun failedRemoteInstallationReturnsAStableRedactedFailure() = runTest {
        val fixture = Fixture(exitCode = 73)

        val failure = assertFailsWith<SshConnectionException> {
            fixture.service.installPublicKey(PROFILE.id)
        }

        assertEquals(SshFailureCategory.REMOTE_PROCESS_EXIT, failure.failure.category)
        assertEquals("SSH_PUBLIC_KEY_INSTALL_FAILED", failure.failure.code)
        assertFalse(failure.failure.actionableMessage.contains(PROFILE.endpoint.host))
        assertTrue(fixture.connection.closed)
    }

    @Test
    fun installTimeoutBecomesAStableRecoverableFailure() = runTest {
        val fixture = Fixture(commandStalls = true)

        val failure = assertFailsWith<SshConnectionException> {
            fixture.service.installPublicKey(PROFILE.id)
        }

        assertEquals(SshFailureCategory.NETWORK, failure.failure.category)
        assertEquals("SSH_KEY_OPERATION_TIMEOUT", failure.failure.code)
        assertEquals(
            "The SSH key operation timed out. Check the network connection and retry.",
            failure.failure.actionableMessage,
        )
        assertFalse(failure.failure.actionableMessage.contains(PROFILE.endpoint.host))
        assertTrue(failure.failure.recoverable)
        assertTrue(fixture.connection.closed)
    }

    @Test
    fun passwordlessProbeTimeoutBecomesAStableRecoverableFailure() = runTest {
        val fixture = Fixture(heartbeatTimesOut = true)

        val failure = assertFailsWith<SshConnectionException> {
            fixture.service.verifyPasswordlessLogin(PROFILE.id)
        }

        assertEquals(SshFailureCategory.NETWORK, failure.failure.category)
        assertEquals("SSH_KEY_OPERATION_TIMEOUT", failure.failure.code)
        assertEquals(
            "The SSH key operation timed out. Check the network connection and retry.",
            failure.failure.actionableMessage,
        )
        assertTrue(failure.failure.recoverable)
        assertTrue(fixture.connection.closed)
    }

    @Test
    fun callerTimeoutRemainsStructuredCancellation() = runTest {
        val fixture = Fixture(
            commandStalls = true,
            operationTimeout = 20.seconds,
        )

        assertFailsWith<TimeoutCancellationException> {
            withTimeout(1.milliseconds) {
                fixture.service.installPublicKey(PROFILE.id)
            }
        }

        assertTrue(fixture.connection.closed)
    }

    @Test
    fun installRejectsAKeyWhoseAdvertisedAlgorithmDoesNotMatchItsBlob() = runTest {
        val fixture = Fixture()
        fixture.agentKeys.generatedAlgorithm = "ssh-ed25519"

        assertFailsWith<IllegalArgumentException> {
            fixture.service.installPublicKey(PROFILE.id)
        }

        assertTrue(fixture.connector.routes.isEmpty())
    }

    private class Fixture(
        profile: SshProfile = PROFILE,
        additionalProfiles: List<SshProfile> = emptyList(),
        exitCode: Int = 0,
        commandStalls: Boolean = false,
        heartbeatTimesOut: Boolean = false,
        operationTimeout: Duration = 20.seconds,
    ) {
        val profiles = ProfileStore(additionalProfiles + profile)
        val agentKeys = AgentKeys()
        val connection = RecordingConnection(exitCode, commandStalls, heartbeatTimesOut)
        val connector = RecordingConnector(connection)
        val service = SshManagedKeyService(
            profiles = profiles,
            credentialStore = Credentials(),
            hostKeys = InMemorySshHostKeyStore(),
            agentKeys = agentKeys,
            connector = connector,
            clock = SshClock { 101L },
            operationTimeout = operationTimeout,
        )
    }

    private class ProfileStore(profiles: List<SshProfile>) : SshProfileStore {
        private val values = profiles.associateByTo(linkedMapOf(), SshProfile::id)

        override suspend fun profiles(): List<SshProfile> = values.values.toList()

        override suspend fun profile(id: SshProfileId): SshProfile? = values[id]

        override suspend fun save(profile: SshProfile) {
            values[profile.id] = profile
        }

        override suspend fun delete(id: SshProfileId) {
            values.remove(id)
        }
    }

    private class Credentials : SshCredentialStore {
        override suspend fun put(
            id: SshCredentialId,
            purpose: SshCredentialPurpose,
            secret: SensitiveBytes,
        ) = Unit

        override suspend fun get(
            id: SshCredentialId,
            purpose: SshCredentialPurpose,
        ): SensitiveBytes = SensitiveBytes.copyOf("password".encodeToByteArray())

        override suspend fun delete(id: SshCredentialId) = Unit
    }

    private class AgentKeys : SshAgentKeyManager {
        val created = mutableListOf<String>()
        private val keys = linkedMapOf<String, SshAgentPublicKey>()
        var generatedAlgorithm = "ecdsa-sha2-nistp256"

        override fun create(
            keyId: String,
            requireUserAuthentication: Boolean,
        ): SshAgentPublicKey {
            created += keyId
            return generatedKey(keyId).also { keys[keyId] = it }
        }

        override fun publicKey(keyId: String): SshAgentPublicKey? = keys[keyId]

        override fun delete(keyId: String): Boolean = keys.remove(keyId) != null

        private fun generatedKey(keyId: String): SshAgentPublicKey {
            val embeddedAlgorithm = "ecdsa-sha2-nistp256".encodeToByteArray()
            val blob = byteArrayOf(0, 0, 0, embeddedAlgorithm.size.toByte()) + embeddedAlgorithm
            return SshAgentPublicKey(
                keyId = keyId,
                algorithm = generatedAlgorithm,
                sha256Fingerprint = "SHA256:AAAAAAAAAAAAAAAAAAAAAA",
                openSshPublicKey =
                "$generatedAlgorithm ${Base64.getEncoder().encodeToString(blob)} test comment",
            )
        }
    }

    private class RecordingConnector(
        private val connection: RecordingConnection,
    ) : SshConnector {
        val routes = mutableListOf<SshConnectionRoute>()

        override suspend fun connect(
            route: SshConnectionRoute,
            phaseListener: SshConnectPhaseListener,
        ): SshTransportConnection {
            routes += route
            return connection
        }
    }

    private class RecordingConnection(
        private val exitCode: Int,
        private val commandStalls: Boolean,
        private val heartbeatTimesOut: Boolean,
    ) : SshTransportConnection {
        val commands = mutableListOf<RemoteCommand>()
        var heartbeats = 0
        var closed = false

        override val runtime: RemoteAgentRuntime = object : RemoteAgentRuntime {
            override val hostId: String = "profile"

            override suspend fun execute(
                command: RemoteCommand,
                timeout: Duration,
            ): RemoteCommandResult {
                if (commandStalls) {
                    withTimeout(timeout.inWholeMilliseconds) { awaitCancellation() }
                }
                commands += command
                return RemoteCommandResult(exitCode, "", "")
            }

            override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess =
                error("Not needed")
        }

        override val isConnected: Boolean
            get() = !closed

        override suspend fun heartbeat(): Duration {
            if (heartbeatTimesOut) {
                withTimeout(1.milliseconds) { awaitCancellation() }
            }
            heartbeats += 1
            return 1.milliseconds
        }

        override fun close() {
            closed = true
        }
    }

    companion object {
        private val PROFILE = SshProfile(
            id = SshProfileId("profile"),
            label = "Remote",
            endpoint = SshEndpoint("remote.example.test"),
            username = "developer",
            authentication = SshAuthentication.Password(SshCredentialId("password")),
            createdAtEpochMillis = 100L,
            updatedAtEpochMillis = 100L,
        )
        private val JUMP_PROFILE = PROFILE.copy(
            id = SshProfileId("jump-profile"),
            endpoint = SshEndpoint("jump.example.test"),
            authentication = SshAuthentication.Password(SshCredentialId("jump-password")),
        )
        private val ROUTED_PROFILE = PROFILE.copy(
            id = SshProfileId("routed-profile"),
            endpoint = SshEndpoint("routed.example.test"),
            authentication = SshAuthentication.Password(SshCredentialId("routed-password")),
            jumpHostProfileId = JUMP_PROFILE.id,
        )
    }
}
