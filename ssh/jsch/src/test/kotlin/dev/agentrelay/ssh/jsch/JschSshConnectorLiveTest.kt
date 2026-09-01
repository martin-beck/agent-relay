package dev.agentrelay.ssh.jsch

import dev.agentrelay.ssh.api.ResolvedSshAuthentication
import dev.agentrelay.ssh.api.SensitiveBytes
import dev.agentrelay.ssh.api.SshAuthentication
import dev.agentrelay.ssh.api.SshConnectionException
import dev.agentrelay.ssh.api.SshFailureCategory
import dev.agentrelay.ssh.api.SshCredentialId
import dev.agentrelay.ssh.api.SshEndpoint
import dev.agentrelay.ssh.api.SshHostKeyApprovalRequiredException
import dev.agentrelay.ssh.api.SshHostKeyDisposition
import dev.agentrelay.ssh.api.SshProfile
import dev.agentrelay.ssh.api.SshProfileId
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
                    profile = PROFILE,
                    authentication = ResolvedSshAuthentication.Password(password),
                    trustedHostKeys = emptyList(),
                )
            }
            assertEquals(SshHostKeyDisposition.UNKNOWN, failure.challenge.disposition)
            assertEquals(SshEndpoint("localhost"), failure.challenge.candidate.endpoint)
            assertEquals("ssh-ed25519", failure.challenge.candidate.algorithm)
            val authenticationFailure = assertFailsWith<SshConnectionException> {
                connector.connect(
                    profile = PROFILE,
                    authentication = ResolvedSshAuthentication.Password(password),
                    trustedHostKeys = listOf(failure.challenge.candidate),
                )
            }
            assertEquals(SshFailureCategory.AUTHENTICATION, authenticationFailure.failure.category)
            assertEquals(false, authenticationFailure.failure.recoverable)
            assertEquals("SSH_AUTHENTICATION_FAILED", authenticationFailure.failure.code)
        } finally {
            password.close()
        }
    }

    companion object {
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
