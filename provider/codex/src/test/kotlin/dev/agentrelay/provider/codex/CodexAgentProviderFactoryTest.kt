/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.codex

import dev.agentrelay.provider.api.AgentCapability
import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CodexAgentProviderFactoryTest {
    private val factory = CodexAgentProviderFactory()

    @Test
    fun descriptorAdvertisesOnlyImplementedCapabilities() {
        assertEquals("openai.codex", factory.descriptor.id.value)
        assertTrue(AgentCapability.SESSION_HISTORY in factory.descriptor.capabilities)
        assertTrue(AgentCapability.APPROVALS in factory.descriptor.capabilities)
        assertEquals(false, AgentCapability.SESSION_FORK in factory.descriptor.capabilities)
    }

    @Test
    fun probeRecognizesAuthenticatedAppServerInstallation() = runTest {
        val runtime = FakeRuntime { command ->
            when {
                command.program == "sh" -> result(stdout = "/home/test/.local/bin/codex\n")
                command.arguments == listOf("--version") -> result(stdout = "codex-cli 0.151.0\n")
                command.arguments == listOf("app-server", "--help") -> result(stdout = "Usage: codex app-server\n")
                command.arguments == listOf("login", "status") -> result(stdout = "Logged in using ChatGPT\n")
                else -> error("Unexpected command $command")
            }
        }

        val readiness = assertIs<ProviderReadiness.Ready>(factory.probe(runtime))

        assertEquals("codex-cli 0.151.0", readiness.version)
        assertTrue(readiness.details.orEmpty().contains("Logged in"))
    }

    @Test
    fun probeDistinguishesMissingBinaryAndMissingAuthentication() = runTest {
        val missing = FakeRuntime { result(exitCode = 1) }
        assertIs<ProviderReadiness.Missing>(factory.probe(missing))

        val loggedOut = FakeRuntime { command ->
            when {
                command.program == "sh" -> result(stdout = "/opt/codex\n")
                command.arguments == listOf("--version") -> result(stdout = "codex-cli 0.151.0\n")
                command.arguments == listOf("app-server", "--help") -> result()
                command.arguments == listOf("login", "status") -> result(
                    exitCode = 1,
                    stderr = "Not logged in",
                )
                else -> error("Unexpected command $command")
            }
        }
        assertIs<ProviderReadiness.NeedsAuthentication>(factory.probe(loggedOut))
    }

    private class FakeRuntime(
        private val executeHandler: suspend (RemoteCommand) -> RemoteCommandResult,
    ) : RemoteAgentRuntime {
        override val hostId = "test-host"

        override suspend fun execute(
            command: RemoteCommand,
            timeout: kotlin.time.Duration,
        ): RemoteCommandResult = executeHandler(command)

        override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess =
            error("Not used by probe tests")
    }

    private companion object {
        fun result(
            exitCode: Int = 0,
            stdout: String = "",
            stderr: String = "",
        ) = RemoteCommandResult(exitCode, stdout, stderr)
    }
}
