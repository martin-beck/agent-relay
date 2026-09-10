/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.openjiuwen

import dev.agentrelay.provider.api.AgentCapability
import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import dev.agentrelay.provider.testing.ScriptedCommand
import dev.agentrelay.provider.testing.ScriptedFrame
import dev.agentrelay.provider.testing.ScriptedRemoteAgentRuntime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Fail-closed conformance replay for the contract pinned in
 * `docs/contracts/openjiuwen-provider-v1.json`.
 *
 * The synthetic metadata responses model only Python package discovery at the
 * pinned openJiuwen agent-core revision. They are not captured transcripts and
 * do not model a gateway, session protocol, tool protocol, or approval protocol.
 */
class OpenJiuwenConformanceTest {
    private val factory = OpenJiuwenAgentProviderFactory()

    @Test
    fun `ordered metadata replay accepts only one bounded version record`() = runTest {
        val accepted = scriptedProbe(listOf(ScriptedFrame("0.1."), ScriptedFrame("17.post1\n")))

        assertEquals(
            ProviderReadiness.Incompatible(
                version = "0.1.17.post1",
                reason = DISABLED_REASON,
            ),
            accepted,
        )
    }

    @Test
    fun `malformed oversized duplicated reordered and truncated records fail closed`() = runTest {
        val hostileRecords = listOf(
            listOf(ScriptedFrame("0.1.17\n0.1.17\n")),
            listOf(ScriptedFrame("17.1.0\n0.1.17\n")),
            listOf(ScriptedFrame("0.1.")),
            listOf(ScriptedFrame("{\"sequence\":1,\"event\":\"delta\"}")),
            List(4) { ScriptedFrame("x".repeat(16 * 1024)) },
            listOf(ScriptedFrame("0.1.17\u0000credential")),
        )

        hostileRecords.forEach { frames ->
            assertIs<ProviderReadiness.Missing>(
                scriptedProbe(frames),
                "Unexpectedly accepted replay record",
            )
        }
    }

    @Test
    fun `unknown and conditional capabilities remain unavailable`() {
        assertEquals(emptySet(), factory.descriptor.capabilities)
        AgentCapability.entries.forEach { capability ->
            assertFalse(capability in factory.descriptor.capabilities)
        }
    }

    @Test
    fun `tools approvals replay reconnect and uncertain delivery cannot reach runtime io`() = runTest {
        val runtime = RecordingRuntime { _, _ -> error("execute must remain unreachable") }

        repeat(3) {
            assertFailsWith<IllegalStateException> { factory.connect(runtime) }
        }

        assertEquals(0, runtime.executeCalls)
        assertEquals(0, runtime.openProcessCalls)
    }

    @Test
    fun `failed probe and hostile output never disclose provider details`() = runTest {
        val protectedValues = listOf(
            "synthetic-secret-value",
            "private.example.invalid",
            "/private/workspace/path",
            "raw protected prompt",
        )
        val failure = factory.probe(
            RecordingRuntime { _, _ ->
                RemoteCommandResult(
                    exitCode = 1,
                    standardOutput = protectedValues.joinToString("\n"),
                    standardError = protectedValues.asReversed().joinToString("\n"),
                )
            },
        )

        val rendered = failure.toString()
        assertIs<ProviderReadiness.Missing>(failure)
        protectedValues.forEach { protected -> assertFalse(protected in rendered) }
    }

    @Test
    fun `probe uses a fixed credential free command and bounded deadline`() = runTest {
        assertEquals(40, PINNED_AGENT_CORE_REVISION.length)
        val runtime = RecordingRuntime { _, _ -> RemoteCommandResult(0, "0.1.17\n", "") }

        factory.probe(runtime)

        assertEquals(1, runtime.executeCalls)
        assertEquals(10.seconds, runtime.lastTimeout)
        assertEquals("python3", runtime.lastCommand?.program)
        assertEquals(emptyMap(), runtime.lastCommand?.environment)
        assertEquals(null, runtime.lastCommand?.workingDirectory)
        val arguments = requireNotNull(runtime.lastCommand).arguments
        assertEquals("-c", arguments.single { it == "-c" })
        assertTrue(arguments.joinToString(" ").contains("importlib.metadata.version('openjiuwen')"))
        assertFalse(arguments.joinToString(" ").contains("token", ignoreCase = true))
        assertEquals(0, runtime.openProcessCalls)
    }

    @Test
    fun `cancellation propagates without starting a process or retry`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val runtime = RecordingRuntime { _, _ ->
            entered.complete(Unit)
            awaitCancellation()
        }
        val probe = launch { factory.probe(runtime) }

        entered.await()
        probe.cancelAndJoin()

        assertTrue(probe.isCancelled)
        assertEquals(1, runtime.executeCalls)
        assertEquals(0, runtime.openProcessCalls)
    }

    @Test
    fun `repeated probes are independent and never promote sdk presence to ready`() = runTest {
        val results = ArrayDeque(
            listOf(
                RemoteCommandResult(1, "", "temporary failure"),
                RemoteCommandResult(0, "0.1.17.post1\n", ""),
            ),
        )
        val runtime = RecordingRuntime { _, _ -> results.removeFirst() }

        assertIs<ProviderReadiness.Missing>(factory.probe(runtime))
        assertIs<ProviderReadiness.Incompatible>(factory.probe(runtime))
        assertEquals(2, runtime.executeCalls)
        assertEquals(0, runtime.openProcessCalls)
    }

    private suspend fun scriptedProbe(frames: List<ScriptedFrame>): ProviderReadiness {
        val command = RemoteCommand("python3", listOf("-c", SDK_PROBE_SCRIPT))
        val runtime = ScriptedRemoteAgentRuntime(
            listOf(ScriptedCommand(command, stdout = frames)),
        )

        val readiness = factory.probe(runtime)

        runtime.assertComplete()
        assertEquals(listOf(command), runtime.commands())
        return readiness
    }

    private class RecordingRuntime(
        private val executeResult: suspend (RemoteCommand, Duration) -> RemoteCommandResult,
    ) : RemoteAgentRuntime {
        override val hostId = "synthetic-openjiuwen-host"
        var executeCalls = 0
            private set
        var openProcessCalls = 0
            private set
        var lastCommand: RemoteCommand? = null
            private set
        var lastTimeout: Duration? = null
            private set

        override suspend fun execute(
            command: RemoteCommand,
            timeout: Duration,
        ): RemoteCommandResult {
            executeCalls += 1
            lastCommand = command
            lastTimeout = timeout
            return executeResult(command, timeout)
        }

        override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess {
            openProcessCalls += 1
            error("OpenJiuwen must not open a bridge process")
        }
    }

    private companion object {
        const val PINNED_AGENT_CORE_REVISION = "1c22b9f273757f7625c044c0e3842ff1c9bbb0b1"
        const val SDK_PROBE_SCRIPT =
            "import importlib.metadata; " +
                "print(importlib.metadata.version('openjiuwen'))"
        const val DISABLED_REASON =
            "The SDK has no reviewed Agent Relay bridge; provider support remains disabled"
    }
}
