package dev.agentrelay.ssh.api

import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
@OptIn(ExperimentalCoroutinesApi::class)
class SshConnectionManagerTest {
    @Test
    fun unknownHostKeyBlocksUntilTheExactChallengeIsApproved() = runTest {
        val hostKeys = InMemorySshHostKeyStore()
        val candidate = hostKey("SHA256:AAAAAAAAAAAAAAAAAAAAAA")
        val connector = TrustCheckingConnector(candidate)
        val manager = manager(connector, hostKeys)
        val session = manager.session(PROFILE.id)

        session.connect()
        runCurrent()

        val waiting = assertIs<SshConnectionState.AwaitingHostKeyTrust>(session.state.value)
        assertEquals(SshHostKeyDisposition.UNKNOWN, waiting.challenge.disposition)
        assertNull(session.runtimeOrNull())

        assertTrue(session.approveHostKey(waiting.challenge))
        runCurrent()

        assertIs<SshConnectionState.Connected>(session.state.value)
        assertTrue(session.runtimeOrNull() != null)
        assertEquals(listOf(candidate), hostKeys.trustedKeys(PROFILE.endpoint))
        session.disconnect()
        manager.close()
    }

    @Test
    fun changedHostKeyRequiresExplicitCompareAndSetReplacement() = runTest {
        val hostKeys = InMemorySshHostKeyStore()
        val original = hostKey("SHA256:AAAAAAAAAAAAAAAAAAAAAA")
        val candidate = hostKey("SHA256:BBBBBBBBBBBBBBBBBBBBBB")
            .copy(publicKeyBase64 = "R0hJSktMTU5PUFFSU1RVVg==")
        hostKeys.trustFirstUse(original)
        val connector = TrustCheckingConnector(candidate)
        val manager = manager(connector, hostKeys)
        val session = manager.session(PROFILE.id)

        session.connect()
        runCurrent()

        val waiting = assertIs<SshConnectionState.AwaitingHostKeyTrust>(session.state.value)
        assertEquals(SshHostKeyDisposition.CHANGED, waiting.challenge.disposition)
        assertFalse(session.approveHostKey(waiting.challenge))

        hostKeys.replace(
            candidate = original.copy(sha256Fingerprint = "SHA256:CCCCCCCCCCCCCCCCCCCCCC"),
            expectedFingerprints = setOf(original.sha256Fingerprint),
        )
        assertFalse(session.replaceChangedHostKey(waiting.challenge))

        hostKeys.replace(
            candidate = original,
            expectedFingerprints = setOf("SHA256:CCCCCCCCCCCCCCCCCCCCCC"),
        )
        assertTrue(session.replaceChangedHostKey(waiting.challenge))
        runCurrent()
        assertIs<SshConnectionState.Connected>(session.state.value)
        session.disconnect()
        manager.close()
    }

    @Test
    fun recoverableFailuresUseBoundedRetriesThenStop() = runTest {
        val connector = AlwaysFailingConnector(
            SshFailure(
                category = SshFailureCategory.NETWORK,
                code = "SSH_NETWORK_UNREACHABLE",
                actionableMessage = "The SSH host is unreachable.",
                recoverable = true,
            ),
        )
        val sleeps = mutableListOf<Duration>()
        val manager = manager(
            connector = connector,
            hostKeys = InMemorySshHostKeyStore(),
            reconnectPolicy = SshReconnectPolicy(
                initialDelay = 100.milliseconds,
                maximumDelay = 1.hours,
                jitterRatio = 0.0,
                retryLimit = 2,
            ),
            sleeper = SshDelay { sleeps += it },
        )
        val session = manager.session(PROFILE.id)

        session.connect()
        runCurrent()

        val failed = assertIs<SshConnectionState.Failed>(session.state.value)
        assertEquals("SSH_RETRY_LIMIT_REACHED", failed.failure.code)
        assertEquals(3, connector.attempts)
        assertEquals(listOf(100.milliseconds, 200.milliseconds), sleeps)
        manager.close()
    }

    @Test
    fun authenticationFailuresDoNotReconnect() = runTest {
        val connector = AlwaysFailingConnector(
            SshFailure(
                category = SshFailureCategory.AUTHENTICATION,
                code = "SSH_AUTHENTICATION_FAILED",
                actionableMessage = "Authentication failed.",
                recoverable = false,
            ),
        )
        val sleeps = mutableListOf<Duration>()
        val manager = manager(
            connector = connector,
            hostKeys = InMemorySshHostKeyStore(),
            sleeper = SshDelay { sleeps += it },
        )
        val session = manager.session(PROFILE.id)

        session.connect()
        runCurrent()

        val failed = assertIs<SshConnectionState.Failed>(session.state.value)
        assertEquals(SshFailureCategory.AUTHENTICATION, failed.failure.category)
        assertEquals(1, connector.attempts)
        assertTrue(sleeps.isEmpty())
        manager.close()
    }

    @Test
    fun profilesMaintainIndependentMultiplexedConnections() = runTest {
        val secondProfile = PROFILE.copy(
            id = SshProfileId("profile-2"),
            endpoint = SshEndpoint("second.example.test"),
        )
        val connector = AlwaysConnectedConnector()
        val manager = manager(
            connector = connector,
            hostKeys = InMemorySshHostKeyStore(),
            profiles = listOf(PROFILE, secondProfile),
        )
        val first = manager.session(PROFILE.id)
        val second = manager.session(secondProfile.id)

        first.connect()
        second.connect()
        runCurrent()

        assertIs<SshConnectionState.Connected>(first.state.value)
        assertIs<SshConnectionState.Connected>(second.state.value)
        assertEquals(setOf(PROFILE.id, secondProfile.id), connector.profileIds.toSet())
        assertEquals(2, manager.activeSessions().size)

        first.disconnect()
        assertIs<SshConnectionState.Disconnected>(first.state.value)
        assertIs<SshConnectionState.Connected>(second.state.value)
        second.disconnect()
        manager.close()
    }

    @Test
    fun backgroundSuspensionIsDistinctAndResumable() = runTest {
        val connector = AlwaysConnectedConnector()
        val manager = manager(connector, InMemorySshHostKeyStore())
        val session = manager.session(PROFILE.id)

        session.connect()
        runCurrent()
        session.suspendForBackground()

        val suspended = assertIs<SshConnectionState.Disconnected>(session.state.value)
        assertEquals(SshDisconnectReason.ANDROID_BACKGROUND_SUSPENDED, suspended.reason)

        session.resumeFromBackground()
        runCurrent()
        assertIs<SshConnectionState.Connected>(session.state.value)
        assertEquals(2, connector.profileIds.size)
        session.disconnect()
        manager.close()
    }

    private fun TestScope.manager(
        connector: SshConnector,
        hostKeys: SshHostKeyStore,
        reconnectPolicy: SshReconnectPolicy = SshReconnectPolicy(),
        sleeper: SshDelay = SshDelay { awaitCancellation() },
        profiles: List<SshProfile> = listOf(PROFILE),
    ) = SshConnectionManager(
        profileStore = FakeProfileStore(profiles),
        credentialStore = FakeCredentialStore(),
        hostKeyStore = hostKeys,
        connector = connector,
        reconnectPolicy = reconnectPolicy,
        heartbeatInterval = 1.hours,
        dispatcher = StandardTestDispatcher(testScheduler),
        clock = SshClock { testScheduler.currentTime },
        sleeper = sleeper,
        random = SshRandom { 0.5 },
    )

    private class FakeProfileStore(profiles: List<SshProfile>) : SshProfileStore {
        private val values = profiles.associateBy { it.id }

        override suspend fun profiles(): List<SshProfile> = values.values.toList()

        override suspend fun profile(id: SshProfileId): SshProfile? = values[id]

        override suspend fun save(profile: SshProfile) = error("Not needed")

        override suspend fun delete(id: SshProfileId) = error("Not needed")
    }

    private class FakeCredentialStore : SshCredentialStore {
        override suspend fun put(
            id: SshCredentialId,
            purpose: SshCredentialPurpose,
            secret: SensitiveBytes,
        ) = Unit

        override suspend fun get(
            id: SshCredentialId,
            purpose: SshCredentialPurpose,
        ) = SensitiveBytes.copyOf("secret".encodeToByteArray())

        override suspend fun delete(id: SshCredentialId) = Unit
    }

    private class TrustCheckingConnector(private val candidate: SshHostKey) : SshConnector {
        override suspend fun connect(
            profile: SshProfile,
            authentication: ResolvedSshAuthentication,
            trustedHostKeys: List<SshHostKey>,
            phaseListener: SshConnectPhaseListener,
        ): SshTransportConnection {
            val exact = trustedHostKeys.any {
                it.algorithm == candidate.algorithm && it.publicKeyBase64 == candidate.publicKeyBase64
            }
            if (!exact) {
                throw SshHostKeyApprovalRequiredException(
                    SshHostKeyChallenge(
                        candidate = candidate,
                        disposition = if (trustedHostKeys.isEmpty()) {
                            SshHostKeyDisposition.UNKNOWN
                        } else {
                            SshHostKeyDisposition.CHANGED
                        },
                        trustedFingerprints = trustedHostKeys.map { it.sha256Fingerprint },
                    ),
                )
            }
            return FakeConnection()
        }
    }

    private class AlwaysConnectedConnector : SshConnector {
        val profileIds = mutableListOf<SshProfileId>()

        override suspend fun connect(
            profile: SshProfile,
            authentication: ResolvedSshAuthentication,
            trustedHostKeys: List<SshHostKey>,
            phaseListener: SshConnectPhaseListener,
        ): SshTransportConnection {
            profileIds += profile.id
            phaseListener.onPhase(SshConnectPhase.AUTHENTICATING)
            return FakeConnection()
        }
    }

    private class AlwaysFailingConnector(private val failure: SshFailure) : SshConnector {
        var attempts = 0

        override suspend fun connect(
            profile: SshProfile,
            authentication: ResolvedSshAuthentication,
            trustedHostKeys: List<SshHostKey>,
            phaseListener: SshConnectPhaseListener,
        ): SshTransportConnection {
            attempts += 1
            throw SshConnectionException(failure)
        }
    }

    private class FakeConnection : SshTransportConnection {
        private var connected = true
        override val runtime: RemoteAgentRuntime = object : RemoteAgentRuntime {
            override val hostId: String = "fake"

            override suspend fun execute(
                command: RemoteCommand,
                timeout: Duration,
            ): RemoteCommandResult = error("Not needed")

            override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess = error("Not needed")
        }
        override val isConnected: Boolean
            get() = connected

        override suspend fun heartbeat(): Duration = 1.milliseconds

        override fun close() {
            connected = false
        }
    }

    companion object {
        private val PROFILE = SshModelsTest.profile(
            SshAuthentication.Password(SshCredentialId("password")),
        )

        private fun hostKey(fingerprint: String) = SshModelsTest.hostKey(fingerprint)
    }
}
