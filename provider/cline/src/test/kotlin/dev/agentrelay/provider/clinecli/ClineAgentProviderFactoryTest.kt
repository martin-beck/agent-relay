/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.clinecli

import dev.agentrelay.provider.api.AgentCapability
import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ClineAgentProviderFactoryTest {
    private val factory = ClineAgentProviderFactory()

    @Test
    fun descriptorAdvertisesOnlyImplementedAcpCapabilities() {
        assertEquals("cline.cli", factory.descriptor.id.value)
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
    }

    @Test
    fun probeRequiresAcpAndJsonHistory() = runTest {
        val ready = FakeRuntime(
            executable = "/home/test/.local/bin/cline",
            help = "--acp --auto-approve history --id",
            history = """[{"sessionId":"one","provider":"ollama"}]""",
        )
        val readiness = assertIs<ProviderReadiness.Ready>(factory.probe(ready))
        assertEquals("3.0.60", readiness.version)
        assertEquals(null, readiness.authenticatedAs)
        assertEquals(true, readiness.details?.contains("latest stored provider is ollama"))

        val missing = FakeRuntime(executable = null)
        assertIs<ProviderReadiness.Missing>(factory.probe(missing))

        val incompatible = FakeRuntime(
            executable = "/usr/bin/cline",
            help = "history --id",
            history = "[]",
        )
        assertIs<ProviderReadiness.Incompatible>(factory.probe(incompatible))

        val malformed = FakeRuntime(
            executable = "/usr/bin/cline",
            help = "--acp --auto-approve history",
            history = "{}",
        )
        assertIs<ProviderReadiness.Incompatible>(factory.probe(malformed))
    }

    private class FakeRuntime(
        private val executable: String?,
        private val help: String = "",
        private val history: String = "[]",
    ) : RemoteAgentRuntime {
        override val hostId: String = "test-host"

        override suspend fun execute(
            command: RemoteCommand,
            timeout: Duration,
        ): RemoteCommandResult = when {
            command.program == "sh" -> RemoteCommandResult(
                0,
                executable?.plus("\n").orEmpty(),
                "",
            )
            command.arguments == listOf("--version") ->
                RemoteCommandResult(0, "3.0.60\n", "")
            command.arguments == listOf("--help") ->
                RemoteCommandResult(0, help, "")
            command.arguments == listOf("history", "--limit", "1", "--json") ->
                RemoteCommandResult(0, history, "")
            else -> error("Unexpected command: $command")
        }

        override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess =
            error("Not used")
    }
}
