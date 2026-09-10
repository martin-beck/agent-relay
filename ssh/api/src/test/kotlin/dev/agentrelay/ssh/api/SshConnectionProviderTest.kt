/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.ssh.api

import dev.agentrelay.connection.api.ConnectionCapability
import dev.agentrelay.connection.api.ConnectionProfileEditor
import dev.agentrelay.connection.api.ConnectionIdentityDecision
import dev.agentrelay.connection.api.ConnectionIdentityDisposition
import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProfileManager
import dev.agentrelay.connection.api.ConnectionProfileOperationId
import dev.agentrelay.connection.api.ConnectionProfileOperationResult
import dev.agentrelay.connection.api.ConnectionProfileSaveResult
import dev.agentrelay.connection.api.ConnectionProfileUpdate
import dev.agentrelay.connection.api.ConnectionProviderRegistry
import dev.agentrelay.connection.api.ConnectionState
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SshConnectionProviderTest {
    @Test
    fun sshImplementsTheGenericConnectionProviderContract() = runTest {
        val hostKeys = InMemorySshHostKeyStore()
        val connector = TrustConnector(HOST_KEY)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = SshConnectionManager(
            profileStore = ProfileStore,
            credentialStore = CredentialStore,
            hostKeyStore = hostKeys,
            connector = connector,
            heartbeatInterval = 1.hours,
            dispatcher = dispatcher,
            sleeper = SshDelay { awaitCancellation() },
        )
        val profileManager = SshConnectionProfileManager(
            profiles = ProfileStore,
            credentials = CredentialStore,
            hostKeys = hostKeys,
            agentKeys = NoAgentKeys,
        )
        val provider = SshConnectionProvider(
            profileStore = ProfileStore,
            manager = manager,
            delegateProfileManager = profileManager,
            stateDispatcher = dispatcher,
        )

        assertEquals(SshConnectionProvider.ID, provider.descriptor.id)
        assertTrue(ConnectionCapability.MULTIPLEXED_PROCESSES in provider.descriptor.capabilities)
        val summary = provider.profiles().single()
        assertEquals(ConnectionProfileId(PROFILE.id.value), summary.id)
        assertEquals("Password", summary.authenticationLabel)

        val connection = provider.connection(summary.id)
        connection.connect()
        runCurrent()
        val waiting = assertIs<ConnectionState.AwaitingIdentityTrust>(connection.state.value)
        assertEquals(ConnectionIdentityDisposition.UNKNOWN, waiting.challenge.disposition)
        assertTrue(
            connection.resolveIdentityChallenge(
                waiting.challenge.id,
                ConnectionIdentityDecision.TRUST_FIRST_USE,
            ),
        )
        runCurrent()
        val connected = connection.state.value
        assertIs<ConnectionState.Connected>(connected)
        assertTrue(connection.runtimeOrNull() != null)
        assertEquals(SshConnectionProvider.ID, connection.diagnosticSnapshot().providerId)
        connection.disconnect()
        provider.close()
    }

    @Test
    fun profileOperationsReachTheDelegateThroughTheProviderRegistry() = runTest {
        val hostKeys = InMemorySshHostKeyStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val manager = SshConnectionManager(
            profileStore = ProfileStore,
            credentialStore = CredentialStore,
            hostKeyStore = hostKeys,
            connector = TrustConnector(HOST_KEY),
            heartbeatInterval = 1.hours,
            dispatcher = dispatcher,
            sleeper = SshDelay { awaitCancellation() },
        )
        val delegate = RecordingProfileManager()
        val registry = ConnectionProviderRegistry(
            listOf(
                SshConnectionProvider(
                    profileStore = ProfileStore,
                    manager = manager,
                    delegateProfileManager = delegate,
                    stateDispatcher = dispatcher,
                ),
            ),
        )
        val profileId = ConnectionProfileId(PROFILE.id.value)
        val operationId = ConnectionProfileOperationId("verify-key-login")

        registry.use {
            val result = it.profileManager(SshConnectionProvider.ID)
                .performOperation(profileId, operationId)

            assertEquals("delegated", result.notice)
            assertEquals(profileId to operationId, delegate.lastOperation)
        }
    }

    private class RecordingProfileManager : ConnectionProfileManager {
        var lastOperation: Pair<ConnectionProfileId, ConnectionProfileOperationId>? = null
            private set

        override suspend fun editor(profileId: ConnectionProfileId?): ConnectionProfileEditor =
            error("Not needed")

        override suspend fun save(update: ConnectionProfileUpdate): ConnectionProfileSaveResult =
            error("Not needed")

        override suspend fun delete(profileId: ConnectionProfileId) = error("Not needed")

        override suspend fun performOperation(
            profileId: ConnectionProfileId,
            operationId: ConnectionProfileOperationId,
        ): ConnectionProfileOperationResult {
            lastOperation = profileId to operationId
            return ConnectionProfileOperationResult("delegated")
        }
    }

    private object ProfileStore : SshProfileStore {
        override suspend fun profiles(): List<SshProfile> = listOf(PROFILE)
        override suspend fun profile(id: SshProfileId): SshProfile? = PROFILE.takeIf { it.id == id }
        override suspend fun save(profile: SshProfile) = error("Not needed")
        override suspend fun delete(id: SshProfileId) = error("Not needed")
    }

    private object CredentialStore : SshCredentialStore {
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

    private object NoAgentKeys : SshAgentKeyManager {
        override fun create(keyId: String, requireUserAuthentication: Boolean): SshAgentPublicKey =
            error("Not needed")

        override fun publicKey(keyId: String): SshAgentPublicKey? = null
        override fun delete(keyId: String): Boolean = false
    }

    private class TrustConnector(private val candidate: SshHostKey) : SshConnector {
        override suspend fun connect(
            route: SshConnectionRoute,
            phaseListener: SshConnectPhaseListener,
        ): SshTransportConnection {
            val destination = route.destination
            if (destination.trustedHostKeys.none { it.publicKeyBase64 == candidate.publicKeyBase64 }) {
                throw SshHostKeyApprovalRequiredException(
                    SshHostKeyChallenge(
                        candidate = candidate,
                        disposition = SshHostKeyDisposition.UNKNOWN,
                        trustedFingerprints = emptyList(),
                    ),
                )
            }
            return object : SshTransportConnection {
                private var connected = true
                override val runtime: RemoteAgentRuntime = object : RemoteAgentRuntime {
                    override val hostId: String = destination.profile.id.value
                    override suspend fun execute(
                        command: RemoteCommand,
                        timeout: Duration,
                    ): RemoteCommandResult = error("Not needed")

                    override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess =
                        error("Not needed")
                }
                override val isConnected: Boolean
                    get() = connected

                override suspend fun heartbeat(): Duration = 1.milliseconds

                override fun close() {
                    connected = false
                }
            }
        }
    }

    companion object {
        private val PROFILE = SshProfile(
            id = SshProfileId("generic-provider"),
            label = "Remote development",
            endpoint = SshEndpoint("example.test"),
            username = "developer",
            authentication = SshAuthentication.Password(SshCredentialId("password")),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        private val HOST_KEY = SshHostKey(
            endpoint = PROFILE.endpoint,
            algorithm = "ssh-ed25519",
            publicKeyBase64 = "QUJDREVGR0hJSktMTU5PUA==",
            sha256Fingerprint = "SHA256:AAAAAAAAAAAAAAAAAAAAAA",
            trustedAtEpochMillis = 1L,
        )
    }
}
