/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.testing

import dev.agentrelay.provider.api.RemoteCommand
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ScriptedRemoteRuntimeTest {
    @Test
    fun exactCommandIsConsumed() = runTest {
        val command = RemoteCommand("agent", listOf("--version"))
        val runtime = ScriptedRemoteAgentRuntime(
            listOf(ScriptedCommand(command, stdout = listOf(ScriptedFrame("1.0")))),
        )
        assertEquals("1.0", runtime.execute(command).standardOutput)
        runtime.assertComplete()
    }

    @Test
    fun duplexFramesWritesAndExitAreDeterministic() = runTest {
        val command = RemoteCommand("agent", listOf("serve"))
        val runtime = ScriptedRemoteAgentRuntime(
            listOf(
                ScriptedCommand(
                    command,
                    stdout = listOf(ScriptedFrame("one"), ScriptedFrame("two", 10.milliseconds)),
                    writes = listOf("input"),
                    closeExitCode = 130,
                ),
            ),
        )
        val process = runtime.openProcess(command)
        process.writeLine("input")
        process.close()
        assertEquals(listOf("one", "two"), process.standardOutputLines.toList())
        assertEquals(130, process.exitCode.value)
        runtime.assertComplete()
    }

    @Test
    fun unexpectedCommandAndLeftoverFailClosed() = runTest {
        val runtime = ScriptedRemoteAgentRuntime(listOf(ScriptedCommand(RemoteCommand("agent"))))
        assertFailsWith<IllegalStateException> { runtime.execute(RemoteCommand("other")) }
    }

    @Test
    fun frameSizeIsBounded() {
        assertFailsWith<IllegalArgumentException> { ScriptedFrame("x".repeat(16 * 1024 + 1)) }
    }
}
