/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.ssh.jsch

import java.nio.file.Files
import java.nio.file.Path
import java.util.Arrays
import dev.agentrelay.ssh.api.ResolvedSshHost
import dev.agentrelay.ssh.api.ResolvedSshAuthentication
import dev.agentrelay.ssh.api.SensitiveBytes
import dev.agentrelay.ssh.api.SshAuthentication
import dev.agentrelay.ssh.api.SshConnectionRoute
import dev.agentrelay.ssh.api.SshConnectionException
import dev.agentrelay.ssh.api.SshFailureCategory
import dev.agentrelay.ssh.api.SshCredentialId
import dev.agentrelay.ssh.api.SshEndpoint
import dev.agentrelay.ssh.api.SshHostKey
import dev.agentrelay.ssh.api.SshHostKeyApprovalRequiredException
import dev.agentrelay.ssh.api.SshHostKeyDisposition
import dev.agentrelay.ssh.api.SshProfile
import dev.agentrelay.ssh.api.SshProfileId
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class JschSshConnectorLiveTest {
    @Test
    fun loopbackSshdStopsAtExplicitFirstUseTrustBeforeAuthentication() = runTest {
        assumeTrue(System.getenv("AGENT_RELAY_LIVE_SSH") == "1")
        val connector = JschSshConnector(
            connectTimeout = 5.seconds,
            channelConnectTimeout = 5.seconds,
            serverAliveInterval = 5.seconds,
        )
        val password = SensitiveBytes.copyOf("deliberately-invalid".encodeToByteArray())

        try {
            val failure = assertFailsWith<SshHostKeyApprovalRequiredException> {
                connector.connect(
                    route = route(password, emptyList()),
                )
            }
            assertEquals(SshHostKeyDisposition.UNKNOWN, failure.challenge.disposition)
            assertEquals(SshEndpoint("localhost"), failure.challenge.candidate.endpoint)
            assertEquals("ssh-ed25519", failure.challenge.candidate.algorithm)
            val authenticationFailure = assertFailsWith<SshConnectionException> {
                connector.connect(
                    route = route(password, listOf(failure.challenge.candidate)),
                )
            }
            assertEquals(SshFailureCategory.AUTHENTICATION, authenticationFailure.failure.category)
            assertEquals(false, authenticationFailure.failure.recoverable)
            assertEquals("SSH_AUTHENTICATION_FAILED", authenticationFailure.failure.code)
        } finally {
            password.close()
        }
    }

    @Test
    fun loopbackSshdAcceptsPasswordAfterFirstUseTrust() = runTest {
        assumeTrue(System.getenv("AGENT_RELAY_LIVE_SSH_PASSWORD") != null)
        val password = requiredEnvironment("AGENT_RELAY_LIVE_SSH_PASSWORD").encodeToByteArray()
        val profile = PROFILE.copy(
            username = requiredEnvironment("AGENT_RELAY_LIVE_SSH_USER"),
            endpoint = SshEndpoint(
                host = requiredEnvironment("AGENT_RELAY_LIVE_SSH_HOST"),
                port = requiredEnvironment("AGENT_RELAY_LIVE_SSH_PORT").toInt(),
            ),
        )
        val connector = JschSshConnector(
            connectTimeout = 5.seconds,
            channelConnectTimeout = 5.seconds,
            serverAliveInterval = 5.seconds,
        )
        try {
            val initialRoute = SshConnectionRoute(
                ResolvedSshHost(
                    profile,
                    ResolvedSshAuthentication.Password(SensitiveBytes.copyOf(password)),
                    emptyList(),
                ),
            )
            val challenge = assertFailsWith<SshHostKeyApprovalRequiredException> {
                connector.connect(initialRoute)
            }.challenge
            initialRoute.close()

            val trustedRoute = SshConnectionRoute(
                ResolvedSshHost(
                    profile,
                    ResolvedSshAuthentication.Password(SensitiveBytes.copyOf(password)),
                    listOf(challenge.candidate),
                ),
            )
            try {
                val connection = connector.connect(trustedRoute)
                try {
                    assertTrue(connection.isConnected)
                    assertTrue(connection.heartbeat().isPositive())
                } finally {
                    connection.close()
                }
            } finally {
                trustedRoute.close()
            }
        } finally {
            Arrays.fill(password, 0)
        }
    }

    @Test
    fun loopbackTwoHopRouteAuthenticatesAndExecutesThroughDirectTcpip() = runTest {
        assumeTrue(System.getenv("AGENT_RELAY_LIVE_SSH_JUMP") == "1")
        val privateKey = Files.readAllBytes(Path.of(requiredEnvironment("AGENT_RELAY_LIVE_SSH_KEY")))
        val jumpHost = liveProfile(
            id = "loopback-jump-live",
            prefix = "AGENT_RELAY_LIVE_SSH_JUMP",
        )
        val destination = liveProfile(
            id = "loopback-destination-live",
            prefix = "AGENT_RELAY_LIVE_SSH_DESTINATION",
        ).copy(jumpHostProfileId = jumpHost.id)
        val connector = JschSshConnector(
            connectTimeout = 5.seconds,
            channelConnectTimeout = 5.seconds,
            serverAliveInterval = 5.seconds,
        )
        fun route(trustedKeys: List<SshHostKey>) = SshConnectionRoute(
            destination = resolved(destination, privateKey, trustedKeys),
            jumpHosts = listOf(resolved(jumpHost, privateKey, trustedKeys)),
        )

        try {
            val jumpChallenge = assertFailsWith<SshHostKeyApprovalRequiredException> {
                connector.connectAndCloseRoute(route(emptyList()))
            }.challenge
            assertEquals(jumpHost.endpoint, jumpChallenge.candidate.endpoint)

            val destinationChallenge = assertFailsWith<SshHostKeyApprovalRequiredException> {
                connector.connectAndCloseRoute(route(listOf(jumpChallenge.candidate)))
            }.challenge
            assertEquals(destination.endpoint, destinationChallenge.candidate.endpoint)

            val connection = connector.connectAndCloseRoute(
                route(listOf(jumpChallenge.candidate, destinationChallenge.candidate)),
            )
            try {
                assertTrue(connection.isConnected)
                assertTrue(connection.heartbeat().isPositive())
            } finally {
                connection.close()
            }
        } finally {
            Arrays.fill(privateKey, 0)
        }
    }

    companion object {
        private suspend fun JschSshConnector.connectAndCloseRoute(
            route: SshConnectionRoute,
        ) = try {
            connect(route)
        } finally {
            route.close()
        }

        private fun resolved(
            profile: SshProfile,
            privateKey: ByteArray,
            trustedKeys: List<SshHostKey>,
        ) = ResolvedSshHost(
            profile = profile,
            authentication = ResolvedSshAuthentication.ImportedKey(
                privateKey = SensitiveBytes.copyOf(privateKey),
                passphrase = null,
            ),
            trustedHostKeys = trustedKeys.filter { it.endpoint == profile.endpoint },
        )

        private fun liveProfile(
            id: String,
            prefix: String,
        ) = SshProfile(
            id = SshProfileId(id),
            label = id,
            endpoint = SshEndpoint(
                host = requiredEnvironment("${prefix}_HOST"),
                port = requiredEnvironment("${prefix}_PORT").toInt(),
            ),
            username = requiredEnvironment("AGENT_RELAY_LIVE_SSH_USER"),
            authentication = SshAuthentication.ImportedKey(SshCredentialId("unused")),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )

        private fun requiredEnvironment(name: String): String =
            checkNotNull(System.getenv(name)) { "Missing required live-test environment variable" }

        private fun route(
            password: SensitiveBytes,
            trustedHostKeys: List<SshHostKey>,
        ) = SshConnectionRoute(
            ResolvedSshHost(
                PROFILE,
                ResolvedSshAuthentication.Password(password),
                trustedHostKeys,
            ),
        )

        private val PROFILE = SshProfile(
            id = SshProfileId("loopback-live"),
            label = "Loopback",
            endpoint = SshEndpoint("localhost"),
            username = System.getProperty("user.name"),
            authentication = SshAuthentication.Password(SshCredentialId("unused")),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
    }
}
