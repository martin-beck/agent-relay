/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.testing

import dev.agentrelay.provider.api.RemoteCommand
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ProviderProtocolReplaySuiteTest {
    private val providers = listOf(
        "codex.app-server",
        "cline.acp",
        "claude.streaming-json",
        "opencode.cli",
        "openharmony.opendesk",
        "continue.cli",
        "aider.cli",
    )

    @Test
    fun everyShippedProviderHasCanonicalReplayScenarios() = runTest {
        providers.forEach { provider ->
            val runtime = ScriptedRemoteAgentRuntime(
                listOf(
                    ScriptedCommand(
                        RemoteCommand("agent-relay-$provider", listOf("probe")),
                        stdout = listOf(ScriptedFrame("""{"provider":"$provider","ready":true}""")),
                    ),
                ),
            )
            val result = runtime.execute(RemoteCommand("agent-relay-$provider", listOf("probe")))
            assertTrue(result.successful)
            assertTrue(result.standardOutput.contains(""""ready":true"""))
            runtime.assertComplete()
        }
    }

    @Test
    fun replayPreservesSplitAndCoalescedFramesWithTiming() = runTest {
        val runtime = ScriptedRemoteAgentRuntime(
            listOf(
                ScriptedCommand(
                    RemoteCommand("codex", listOf("app-server")),
                    stdout = listOf(
                        ScriptedFrame("""{"id":1,""", 5.milliseconds),
                        ScriptedFrame(
                            """"result":{"ready":true}}
""",
                        ),
                    ),
                ),
            ),
        )
        val process = runtime.openProcess(RemoteCommand("codex", listOf("app-server")))
        assertEquals(
            listOf(
                """{"id":1,""",
                """"result":{"ready":true}}
""",
            ),
            process.standardOutputLines.toList(),
        )
        process.close()
        runtime.assertComplete()
    }

    @Test
    fun malformedAndUnexpectedReplayInputsFailClosed() = runTest {
        val runtime = ScriptedRemoteAgentRuntime(
            listOf(
                ScriptedCommand(RemoteCommand("aider", listOf("--message"))),
                ScriptedCommand(RemoteCommand("aider", listOf("--message"))),
            ),
        )
        assertFailsWith<IllegalStateException> {
            runtime.execute(RemoteCommand("aider", listOf("--wrong")))
        }
        assertFailsWith<IllegalStateException> { runtime.assertComplete() }
    }
}
