/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.openjiuwen

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

class OpenJiuwenAgentProviderFactoryTest {
    private val factory = OpenJiuwenAgentProviderFactory()

    @Test
    fun descriptorDoesNotAdvertiseUnimplementedCapabilities() {
        assertEquals("openjiuwen.core", factory.descriptor.id.value)
        assertEquals(emptySet(), factory.descriptor.capabilities)
    }

    @Test
    fun sdkPresenceIsReportedAsIncompatibleUntilBridgeIsPinned() = runTest {
        val readiness = factory.probe(
            FakeRuntime(RemoteCommandResult(0, "0.1.17.post1\n", "")),
        )
        assertEquals(
            ProviderReadiness.Incompatible(
                version = "0.1.17.post1",
                reason = "The SDK has no reviewed Agent Relay bridge; provider support remains disabled",
            ),
            readiness,
        )
    }

    @Test
    fun missingSdkIsReportedWithoutInvokingUserCode() = runTest {
        assertIs<ProviderReadiness.Missing>(
            factory.probe(FakeRuntime(RemoteCommandResult(1, "", "missing"))),
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
