package dev.agentrelay.provider.claude

import dev.agentrelay.provider.api.AgentCapability
import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ClaudeAgentProviderFactoryTest {
    private val factory = ClaudeAgentProviderFactory()

    @Test
    fun descriptorAdvertisesOnlyImplementedStreamCapabilities() {
        assertEquals("anthropic.claude-code", factory.descriptor.id.value)
        assertEquals(
            setOf(
                AgentCapability.SESSION_DISCOVERY,
                AgentCapability.SESSION_START,
                AgentCapability.SESSION_RESUME,
                AgentCapability.SESSION_HISTORY,
                AgentCapability.LIVE_STREAMING,
                AgentCapability.TURN_INTERRUPT,
                AgentCapability.APPROVALS,
                AgentCapability.FILE_CHANGES,
            ),
            factory.descriptor.capabilities,
        )
        assertTrue(AgentCapability.ACTIVE_TURN_STEERING !in factory.descriptor.capabilities)
        assertTrue(AgentCapability.SESSION_FORK !in factory.descriptor.capabilities)
    }

    @Test
    fun probeRequiresStreamProtocolAndAuthentication() = runTest {
        val ready = FakeRuntime(
            RemoteCommandResult(0, "/home/test/.local/bin/claude\n", ""),
            RemoteCommandResult(0, "2.1.236 (Claude Code)\n", ""),
            RemoteCommandResult(
                0,
                "--input-format stream-json --output-format stream-json --session-id --resume\n",
                "",
            ),
            RemoteCommandResult(
                0,
                """{"loggedIn":true,"authMethod":"oauth_token","apiProvider":"firstParty"}""",
                "",
            ),
        )
        assertEquals(
            ProviderReadiness.Ready(
                version = "2.1.236",
                authenticatedAs = "oauth_token",
                details = "Bidirectional stream-json control protocol available",
            ),
            factory.probe(ready),
        )

        assertIs<ProviderReadiness.Missing>(
            factory.probe(FakeRuntime(RemoteCommandResult(1, "", ""))),
        )
        assertIs<ProviderReadiness.Incompatible>(
            factory.probe(
                FakeRuntime(
                    RemoteCommandResult(0, "/usr/bin/claude\n", ""),
                    RemoteCommandResult(0, "1.0.0\n", ""),
                    RemoteCommandResult(0, "text output only\n", ""),
                ),
            ),
        )
        assertIs<ProviderReadiness.NeedsAuthentication>(
            factory.probe(
                FakeRuntime(
                    RemoteCommandResult(0, "/usr/bin/claude\n", ""),
                    RemoteCommandResult(0, "2.1.236\n", ""),
                    RemoteCommandResult(0, "stream-json --session-id --resume\n", ""),
                    RemoteCommandResult(0, """{"loggedIn":false}""", ""),
                ),
            ),
        )
    }

    private class FakeRuntime(vararg results: RemoteCommandResult) : RemoteAgentRuntime {
        private val results = ArrayDeque(results.toList())
        override val hostId: String = "test-host"

        override suspend fun execute(
            command: RemoteCommand,
            timeout: Duration,
        ): RemoteCommandResult = results.removeFirst()

        override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess =
            error("Not used")
    }
}
