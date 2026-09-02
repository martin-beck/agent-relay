package dev.agentrelay.provider.opendesk

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

class OpenDeskAgentProviderFactoryTest {
    private val factory = OpenDeskAgentProviderFactory()

    @Test
    fun descriptorAdvertisesOnlyImplementedServerCapabilities() {
        assertEquals("openharmony.opendesk", factory.descriptor.id.value)
        assertEquals(
            setOf(
                AgentCapability.SESSION_DISCOVERY,
                AgentCapability.SESSION_START,
                AgentCapability.SESSION_RESUME,
                AgentCapability.SESSION_HISTORY,
                AgentCapability.LIVE_STREAMING,
                AgentCapability.TURN_INTERRUPT,
                AgentCapability.APPROVALS,
            ),
            factory.descriptor.capabilities,
        )
        assertTrue(AgentCapability.FILE_CHANGES !in factory.descriptor.capabilities)
        assertTrue(AgentCapability.ACTIVE_TURN_STEERING !in factory.descriptor.capabilities)
    }

    @Test
    fun probeFindsNvmInstallAndSuppliesItsNodeRuntime() = runTest {
        val executable = "/home/test/.nvm/versions/node/v26.3.0/bin/opendesk"
        val runtime = FakeRuntime(
            RemoteCommandResult(0, "$executable\n", ""),
            RemoteCommandResult(0, "0.3.5\n", ""),
            RemoteCommandResult(0, "--mode <mode> opencode acp\n", ""),
        )

        assertEquals(
            ProviderReadiness.Ready(
                version = "0.3.5",
                details =
                "Authenticated loopback server API available; model access is checked when a session runs",
            ),
            factory.probe(runtime),
        )
        assertEquals(executable, runtime.commands[1].program)
        assertTrue(
            runtime.commands[1].environment.getValue("PATH")
                .startsWith("/home/test/.nvm/versions/node/v26.3.0/bin:"),
        )
    }

    @Test
    fun probeReportsMissingAndIncompatibleInstalls() = runTest {
        assertIs<ProviderReadiness.Missing>(
            factory.probe(FakeRuntime(RemoteCommandResult(1, "", ""))),
        )

        val incompatible = FakeRuntime(
            RemoteCommandResult(0, "/usr/bin/opendesk\n", ""),
            RemoteCommandResult(0, "0.2.0\n", ""),
            RemoteCommandResult(0, "serve without compatible mode\n", ""),
        )
        assertIs<ProviderReadiness.Incompatible>(factory.probe(incompatible))
    }

    private class FakeRuntime(vararg results: RemoteCommandResult) : RemoteAgentRuntime {
        private val results = ArrayDeque(results.toList())
        val commands = mutableListOf<RemoteCommand>()
        override val hostId: String = "test-host"

        override suspend fun execute(
            command: RemoteCommand,
            timeout: Duration,
        ): RemoteCommandResult {
            commands += command
            return results.removeFirst()
        }

        override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess =
            error("Not used")
    }
}
