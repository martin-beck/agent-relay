/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.clinecli

import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSession
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import dev.agentrelay.provider.api.StartSessionOptions
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClineAcpClientTest {
    @Test
    fun startsConfiguredSessionStreamsPromptsAndRoutesControls() = runTest {
        val process = FakeProcess()
        val runtime = FakeRuntime(process)
        val starting = async(start = CoroutineStart.UNDISPATCHED) {
            ClineAcpClient.startNew(
                runtime = runtime,
                executable = "/home/test/.local/bin/cline",
                options = StartSessionOptions(
                    workingDirectory = "/workspace with spaces",
                    model = "example-model",
                    providerOptions = mapOf(
                        "provider" to "ollama",
                        "mode" to "plan",
                    ),
                ),
                dispatcher = UnconfinedTestDispatcher(testScheduler),
            )
        }

        val initialize = process.lastRequest()
        assertEquals("initialize", initialize.string("method"))
        process.respond(initialize, """{"protocolVersion":1,"agentInfo":{"version":"3.0.60"}}""")
        runCurrent()

        val create = process.lastRequest()
        assertEquals("session/new", create.string("method"))
        assertEquals("/workspace with spaces", create.params().string("cwd"))
        process.respond(
            create,
            """
            {
              "sessionId":"session-new",
              "models":{"currentModelId":"","availableModels":[]},
              "modes":{"currentModeId":"act","availableModes":[]}
            }
            """.trimIndent(),
        )
        runCurrent()

        val setModel = process.lastRequest()
        assertEquals("session/set_model", setModel.string("method"))
        assertEquals("example-model", setModel.params().string("modelId"))
        process.respond(setModel, "{}")
        runCurrent()

        val setMode = process.lastRequest()
        assertEquals("session/set_mode", setMode.string("method"))
        assertEquals("plan", setMode.params().string("modeId"))
        process.respond(setMode, "{}")
        val client = starting.await()

        val command = runtime.opened.single()
        assertEquals("/home/test/.local/bin/cline", command.program)
        assertEquals("/workspace with spaces", command.workingDirectory)
        assertEquals(listOf("--acp", "--auto-approve", "false"), command.arguments)
        assertEquals("ollama", command.environment["CLINE_PROVIDER"])
        assertEquals("example-model", command.environment["CLINE_MODEL"])
        assertEquals("agent-relay-keyless-provider", command.environment["CLINE_API_KEY"])
        assertEquals("session-new", client.sessionId)

        val prompting = async(start = CoroutineStart.UNDISPATCHED) {
            client.prompt("secret prompt value")
        }
        val prompt = process.lastRequest()
        assertEquals("session/prompt", prompt.string("method"))
        assertFalse(command.arguments.joinToString(" ").contains("secret prompt value"))
        assertFalse(command.environment.values.any { it.contains("secret prompt value") })
        assertTrue(prompt.toString().contains("secret prompt value"))
        process.respond(prompt, """{"stopReason":"end_turn"}""")
        assertEquals("end_turn", prompting.await().string("stopReason"))

        client.cancel()
        val cancel = process.writes.last().asObject()
        assertEquals("session/cancel", cancel.string("method"))
        assertTrue("id" !in cancel)

        client.respondToPermission(
            ClineAcpCall(
                method = "session/request_permission",
                params = null,
                id = JsonPrimitive(81),
            ),
            AgentApprovalDecision.APPROVE_FOR_SESSION,
            mapOf(AgentApprovalDecision.APPROVE_FOR_SESSION to "allow_always"),
        )
        val permission = process.writes.last().asObject()
        assertEquals("allow_always", permission.result().objectValue("outcome")?.string("optionId"))

        client.close()
        assertTrue(process.closed)
    }

    @Test
    fun failedLoadClosesTheAcpProcess() = runTest {
        val process = FakeProcess()
        val runtime = FakeRuntime(process)
        val session = AgentSession(
            id = AgentSessionId("stored-session"),
            providerId = AgentProviderId("cline.cli"),
            title = null,
            preview = "",
            workingDirectory = "/workspace",
            model = "example-model",
            createdAtEpochSeconds = null,
            updatedAtEpochSeconds = null,
            state = AgentSessionState.IDLE,
            canAcceptInput = true,
            metadata = mapOf("cline.provider" to "ollama"),
        )
        val starting = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                ClineAcpClient.resume(
                    runtime,
                    "/home/test/.local/bin/cline",
                    session,
                    UnconfinedTestDispatcher(testScheduler),
                )
            }
        }

        val initialize = process.lastRequest()
        process.respond(initialize, """{"protocolVersion":1}""")
        runCurrent()
        val load = process.lastRequest()
        assertEquals("session/load", load.string("method"))
        process.respondError(load, -32603, "model must not be empty")

        val failure = starting.await().exceptionOrNull()
        assertIs<ClineAcpException>(failure)
        assertTrue(failure.message!!.contains("model"))
        assertTrue(process.closed)
    }

    @Test
    fun malformedRecordsAndProcessExitFailPendingRequests() = runTest {
        val process = FakeProcess()
        val peer = ClineAcpPeer(
            process,
            UnconfinedTestDispatcher(testScheduler),
        )
        val malformed = async(start = CoroutineStart.UNDISPATCHED) {
            peer.calls.first { it.method == "agent_relay/error" }
        }
        process.emit("not-json")
        assertTrue(malformed.await().params?.string("message")!!.contains("malformed"))

        val request = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { peer.request("session/prompt", json.parseToJsonElement("{}")) }
        }
        process.exitCode.value = 9
        runCurrent()
        val failure = request.await().exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure.message!!.contains("9"))
        peer.close()
    }

    private class FakeRuntime(private val process: FakeProcess) : RemoteAgentRuntime {
        override val hostId: String = "test-host"
        val opened = mutableListOf<RemoteCommand>()

        override suspend fun execute(
            command: RemoteCommand,
            timeout: Duration,
        ): RemoteCommandResult = error("Not used")

        override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess {
            opened += command
            return process
        }
    }

    private class FakeProcess : RemoteDuplexProcess {
        override val standardOutputLines = MutableSharedFlow<String>(extraBufferCapacity = 64)
        override val standardErrorLines = MutableSharedFlow<String>(extraBufferCapacity = 8)
        override val exitCode = MutableStateFlow<Int?>(null)
        val writes = mutableListOf<String>()
        var closed = false

        override suspend fun writeLine(line: String) {
            writes += line
        }

        override suspend fun close() {
            closed = true
        }

        suspend fun emit(line: String) {
            standardOutputLines.emit(line)
        }

        fun lastRequest(): JsonObject = writes.last().asObject()

        suspend fun respond(request: JsonObject, result: String) {
            emit(
                """{"jsonrpc":"2.0","id":${request["id"]},"result":$result}""",
            )
        }

        suspend fun respondError(request: JsonObject, code: Int, message: String) {
            emit(
                """
                {
                  "jsonrpc":"2.0",
                  "id":${request["id"]},
                  "error":{"code":$code,"message":"$message"}
                }
                """.trimIndent(),
            )
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun String.asObject(): JsonObject = json.parseToJsonElement(this).jsonObject

        fun JsonObject.params(): JsonObject = objectValue("params")!!

        fun JsonObject.result(): JsonObject = objectValue("result")!!
    }
}
