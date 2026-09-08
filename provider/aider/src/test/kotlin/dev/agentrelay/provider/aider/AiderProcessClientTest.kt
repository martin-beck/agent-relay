/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.aider

import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import dev.agentrelay.provider.api.StartSessionOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class)
class AiderProcessClientTest {
    @Test
    fun startsSafeHelperKeepsPromptOffArgvAndMapsResult() = runTest {
        val process = FakeProcess()
        val runtime = FakeRuntime(process)
        val dispatcher = StandardTestDispatcher(testScheduler)
        val start = async {
            AiderProcessClient.startNew(
                runtime = runtime,
                interpreter = "/tools/aider/bin/python",
                stateRoot = "/home/test/.local/state/agent-relay/aider",
                sessionId = AgentSessionId("12345678-1234-1234-1234-123456789abc"),
                options = StartSessionOptions(
                    workingDirectory = "/work/repo",
                    model = "test/model",
                    providerOptions = mapOf("files" to "[\"src/Main.kt\"]"),
                ),
                dispatcher = dispatcher,
            )
        }
        runCurrent()
        process.output.emit("""{"type":"ready","model":"test/model"}""")
        val client = start.await()

        val command = runtime.opened.single()
        assertEquals("/tools/aider/bin/python", command.program)
        assertEquals("/work/repo", command.workingDirectory)
        assertTrue(command.arguments.contains(AIDER_HELPER_SCRIPT))
        assertTrue(command.arguments.contains("[\"src/Main.kt\"]"))
        assertFalse(command.arguments.any { it.contains("private prompt") })
        assertTrue(AIDER_HELPER_SCRIPT.contains("coder.io.yes = False"))
        assertTrue(AIDER_HELPER_SCRIPT.contains("\"--no-auto-commits\""))
        assertTrue(AIDER_HELPER_SCRIPT.contains("\"--no-suggest-shell-commands\""))

        val prompt = async { client.prompt("private prompt") }
        runCurrent()
        val request = Json.parseToJsonElement(process.writes.single()).jsonObject
        assertEquals("prompt", request["method"]!!.jsonPrimitive.content)
        assertEquals("private prompt", request["text"]!!.jsonPrimitive.content)
        process.output.emit(
            """{"id":1,"type":"result","text":"done","files":[""" +
                """{"path":"/work/repo/src/Main.kt","kind":"modified"}]}""",
        )
        val result = prompt.await()
        assertEquals("done", result.text)
        assertEquals(
            listOf(AiderFileResult("/work/repo/src/Main.kt", AgentFileChangeKind.MODIFIED)),
            result.files,
        )
        client.close()
        assertTrue(process.closed)
    }

    @Test
    fun malformedOutputAndExitFailPendingCalls() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val malformed = FakeProcess()
        val malformedPeer = AiderProcessPeer(
            malformed,
            dispatcher,
            startupTimeout = 1.seconds,
            requestTimeout = 1.seconds,
        )
        runCurrent()
        malformed.output.emit("not-json")
        runCurrent()
        val startupError = runCatching { malformedPeer.awaitReady() }.exceptionOrNull()
        assertIs<AiderProcessException>(startupError)

        val exited = FakeProcess()
        val exitedPeer = AiderProcessPeer(
            exited,
            dispatcher,
            startupTimeout = 1.seconds,
            requestTimeout = 1.seconds,
        )
        runCurrent()
        exited.output.emit("""{"type":"ready","model":"m"}""")
        exitedPeer.awaitReady()
        backgroundScope.launch {
            while (exited.writes.isEmpty()) yield()
            exited.mutableExit.value = 17
        }
        val error = runCatching {
            exitedPeer.request("prompt", "hello")
        }.exceptionOrNull()
        assertIs<AiderProcessException>(error)
        assertTrue(error.message!!.contains("17"))
    }

    private class FakeRuntime(
        private val process: RemoteDuplexProcess,
    ) : RemoteAgentRuntime {
        override val hostId = "test"
        val opened = mutableListOf<RemoteCommand>()

        override suspend fun execute(
            command: RemoteCommand,
            timeout: Duration,
        ): RemoteCommandResult = error("Unexpected execute: $command")

        override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess {
            opened += command
            return process
        }
    }

    private class FakeProcess : RemoteDuplexProcess {
        val output = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val error = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val mutableExit = MutableStateFlow<Int?>(null)
        val writes = mutableListOf<String>()
        var closed = false

        override val standardOutputLines: Flow<String> = output
        override val standardErrorLines: Flow<String> = error
        override val exitCode: StateFlow<Int?> = mutableExit

        override suspend fun writeLine(line: String) {
            writes += line
        }

        override suspend fun close() {
            closed = true
            mutableExit.value = 0
        }
    }
}
