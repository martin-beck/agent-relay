/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.continuecli

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

class ContinueAgentProviderFactoryTest {
    private val factory = ContinueAgentProviderFactory()

    @Test
    fun descriptorAdvertisesOnlyProvenServerCapabilities() {
        assertEquals("continue.cli", factory.descriptor.id.value)
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
    fun probeFindsWrapperAndRequiresHiddenServer() = runTest {
        val ready = FakeRuntime(
            RemoteCommandResult(0, "/home/test/.local/bin/cn\n", ""),
            RemoteCommandResult(0, "1.5.47\n", ""),
            RemoteCommandResult(0, "Start an HTTP server with /state and /message endpoints\n", ""),
        )
        assertEquals(
            ProviderReadiness.Ready(
                version = "1.5.47",
                details = "Loopback-safe server API available; model credentials are checked when connecting",
            ),
            factory.probe(ready),
        )

        assertIs<ProviderReadiness.Missing>(
            factory.probe(FakeRuntime(RemoteCommandResult(1, "", ""))),
        )

        val incompatible = FakeRuntime(
            RemoteCommandResult(0, "/usr/bin/cn\n", ""),
            RemoteCommandResult(0, "1.0.0\n", ""),
            RemoteCommandResult(1, "", "unknown command serve"),
        )
        assertIs<ProviderReadiness.Incompatible>(factory.probe(incompatible))
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
