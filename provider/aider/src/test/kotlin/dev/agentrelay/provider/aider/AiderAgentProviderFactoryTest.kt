package dev.agentrelay.provider.aider

import dev.agentrelay.provider.api.AgentCapability
import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest

class AiderAgentProviderFactoryTest {
    @Test
    fun descriptorAdvertisesOnlyImplementedSafeCapabilities() {
        val descriptor = AiderAgentProviderFactory().descriptor
        assertEquals("aider.cli", descriptor.id.value)
        assertEquals(
            setOf(
                AgentCapability.SESSION_DISCOVERY,
                AgentCapability.SESSION_START,
                AgentCapability.SESSION_RESUME,
                AgentCapability.SESSION_HISTORY,
                AgentCapability.TURN_INTERRUPT,
                AgentCapability.FILE_CHANGES,
            ),
            descriptor.capabilities,
        )
        assertTrue(AgentCapability.APPROVALS !in descriptor.capabilities)
        assertTrue(AgentCapability.LIVE_STREAMING !in descriptor.capabilities)
    }

    @Test
    fun probeRequiresCliControlsAndCompatiblePythonApi() = runTest {
        val factory = AiderAgentProviderFactory()
        assertIs<ProviderReadiness.Missing>(factory.probe(FakeRuntime(missing = true)))

        val incompatible = factory.probe(FakeRuntime(compatible = false))
        assertIs<ProviderReadiness.Incompatible>(incompatible)

        val ready = factory.probe(FakeRuntime())
        assertIs<ProviderReadiness.Ready>(ready)
        assertEquals("aider 0.86.2", ready.version)
        assertTrue(ready.details!!.contains("confirmations are declined"))
    }

    private class FakeRuntime(
        private val missing: Boolean = false,
        private val compatible: Boolean = true,
    ) : RemoteAgentRuntime {
        override val hostId = "test"

        override suspend fun execute(
            command: RemoteCommand,
            timeout: Duration,
        ): RemoteCommandResult = when {
            command.program == "sh" -> result(
                if (missing) "" else "/home/test/.local/bin/aider\n",
            )
            command.program.endsWith("/aider") && command.arguments == listOf("--version") ->
                result("aider 0.86.2\n")
            command.program.endsWith("/aider") && command.arguments == listOf("--help") ->
                result(
                    "--restore-chat-history --no-auto-commits " +
                        "--no-suggest-shell-commands\n",
                )
            command.program == "python3" ->
                result("/home/test/.local/share/uv/tools/aider/bin/python\n")
            command.program.endsWith("/python") ->
                result(if (compatible) "compatible\n" else "", if (compatible) 0 else 1)
            else -> error("Unexpected command: $command")
        }

        override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess =
            error("Unexpected process: $command")

        private fun result(output: String, exitCode: Int = 0) =
            RemoteCommandResult(exitCode, output, "")
    }
}
