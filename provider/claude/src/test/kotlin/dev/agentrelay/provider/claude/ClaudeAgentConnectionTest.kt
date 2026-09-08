/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.claude

import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentEvent
import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import dev.agentrelay.provider.api.StartSessionOptions
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClaudeAgentConnectionTest {
    @Test
    fun attachesHistoryRoutesApprovalsAndEmitsCompletedWork() = runTest {
        val runtime = FakeRuntime(listOf(discovery("session-1", "/workspace")))
        val client = FakeClaudeClient()
        val starter = RecordingStarter { _, _, _, _ -> client }
        val connection = ClaudeAgentConnection.create(
            descriptor = ClaudeAgentProviderFactory().descriptor,
            runtime = runtime,
            executable = "/home/test/.local/bin/claude",
            clientStarter = starter,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val attached = connection.attach(AgentSessionId("session-1"))
        assertEquals(AgentSessionState.IDLE, attached.state)
        assertEquals(true, starter.calls.single().resume)
        assertEquals("Fix parser", connection.transcript(attached.id).first().text)

        client.emit(
            """{"type":"system","subtype":"init","cwd":"/workspace","model":"claude-sonnet"}""",
        )
        runCurrent()
        assertEquals("claude-sonnet", connection.sessions.value.single().model)

        val requested = async(start = CoroutineStart.UNDISPATCHED) {
            connection.events.first { it is AgentEvent.ApprovalRequested }
        }
        client.emit(
            """
            {
              "type":"control_request",
              "request_id":"permission-1",
              "request":{
                "subtype":"can_use_tool",
                "tool_name":"Bash",
                "input":{"command":"./gradlew test","cwd":"/workspace"},
                "permission_suggestions":[{"type":"addRules","rules":[{"toolName":"Bash"}]}]
              }
            }
            """.trimIndent(),
        )
        val approval = assertIs<AgentEvent.ApprovalRequested>(requested.await()).approval
        assertEquals(AgentSessionState.WAITING_FOR_APPROVAL, connection.sessions.value.single().state)
        assertEquals("./gradlew test", approval.command)

        connection.respondToApproval(approval.id, AgentApprovalDecision.APPROVE_FOR_SESSION)
        assertEquals(listOf(PermissionResponse("permission-1", true, true)), client.permissions)
        assertEquals(AgentSessionState.RUNNING, connection.sessions.value.single().state)

        connection.sendInput(attached.id, "Try another approach")
        assertEquals(listOf("Try another approach"), client.userMessages)

        val liveFile = async(start = CoroutineStart.UNDISPATCHED) {
            connection.events.first { it is AgentEvent.FileChanged }
        }
        val completed = async(start = CoroutineStart.UNDISPATCHED) {
            connection.events.first { it is AgentEvent.TurnCompleted }
        }
        client.emit(
            """
            {
              "type":"assistant",
              "message":{
                "id":"message-1",
                "content":[
                  {"type":"text","text":"Done"},
                  {
                    "type":"tool_use",
                    "id":"tool-1",
                    "name":"Edit",
                    "input":{"file_path":"src/Live.kt","old_string":"a","new_string":"b"}
                  }
                ]
              }
            }
            """.trimIndent(),
        )
        client.emit("""{"type":"result","subtype":"success","is_error":false,"result":"Done"}""")

        val file = assertIs<AgentEvent.FileChanged>(liveFile.await()).file
        assertEquals("/workspace/src/Live.kt", file.remotePath)
        assertTrue(assertIs<AgentEvent.TurnCompleted>(completed.await()).successful)
        assertEquals(AgentSessionState.IDLE, connection.sessions.value.single().state)
        assertEquals(
            setOf("/workspace/src/Live.kt", "/workspace/src/Durable.kt"),
            connection.changedFiles(attached.id).map { it.remotePath }.toSet(),
        )
        assertTrue(
            connection.changedFiles(attached.id).all {
                it.kind == AgentFileChangeKind.MODIFIED
            },
        )

        connection.interrupt(attached.id)
        assertEquals(1, client.interruptions)
        assertEquals(AgentSessionState.IDLE, connection.sessions.value.single().state)

        connection.close()
        assertTrue(client.closed)
    }

    @Test
    fun keepsOneStreamPerSessionAndPassesStartOptions() = runTest {
        val runtime = FakeRuntime(
            listOf(
                discovery("session-1", "/one"),
                discovery("session-2", "/two"),
            ),
        )
        val starter = RecordingStarter { _, _, _, _ -> FakeClaudeClient() }
        val connection = ClaudeAgentConnection.create(
            descriptor = ClaudeAgentProviderFactory().descriptor,
            runtime = runtime,
            executable = "/home/test/.local/bin/claude",
            clientStarter = starter,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        listOf("session-1", "session-2", "session-1").map {
            async { connection.attach(AgentSessionId(it)) }
        }.awaitAll()
        assertEquals(2, starter.calls.size)
        assertEquals(setOf("/one", "/two"), starter.calls.mapNotNull { it.directory }.toSet())
        assertTrue(starter.calls.all { it.resume })

        val started = connection.startSession(
            StartSessionOptions(
                workingDirectory = "/new",
                model = "claude-opus",
                providerOptions = mapOf(
                    "permissionMode" to "plan",
                    "tools" to "Read",
                ),
            ),
        )
        val startCall = starter.calls.last()
        assertEquals(started.id, startCall.id)
        assertEquals("/new", startCall.directory)
        assertEquals("claude-opus", startCall.options.model)
        assertEquals("plan", startCall.options.providerOptions["permissionMode"])
        assertEquals(false, startCall.resume)
        assertEquals(3, starter.calls.size)

        connection.close()
        assertTrue(starter.clients.all(FakeClaudeClient::closed))
    }

    private data class StartCall(
        val id: AgentSessionId,
        val resume: Boolean,
        val directory: String?,
        val options: StartSessionOptions,
    )

    private data class PermissionResponse(
        val id: String,
        val allowed: Boolean,
        val remember: Boolean,
    )

    private class RecordingStarter(
        private val factory: (
            AgentSessionId,
            Boolean,
            String?,
            StartSessionOptions,
        ) -> FakeClaudeClient,
    ) : ClaudeClientStarter {
        val calls = mutableListOf<StartCall>()
        val clients = mutableListOf<FakeClaudeClient>()

        override suspend fun start(
            sessionId: AgentSessionId,
            resume: Boolean,
            workingDirectory: String?,
            options: StartSessionOptions,
        ): ClaudeClient {
            calls += StartCall(sessionId, resume, workingDirectory, options)
            return factory(sessionId, resume, workingDirectory, options).also(clients::add)
        }
    }

    private class FakeClaudeClient : ClaudeClient {
        private val mutableMessages = MutableSharedFlow<JsonObject>(extraBufferCapacity = 32)
        override val messages: Flow<JsonObject> = mutableMessages
        val userMessages = mutableListOf<String>()
        val permissions = mutableListOf<PermissionResponse>()
        var interruptions = 0
        var closed = false

        override suspend fun sendUserMessage(text: String) {
            userMessages += text
        }

        override suspend fun interrupt() {
            interruptions += 1
        }

        override suspend fun respondToPermission(
            requestId: String,
            allowed: Boolean,
            remember: Boolean,
        ) {
            permissions += PermissionResponse(requestId, allowed, remember)
        }

        override suspend fun close() {
            closed = true
        }

        suspend fun emit(value: String) {
            mutableMessages.emit(json.parseToJsonElement(value).jsonObject)
        }
    }

    private class FakeRuntime(private val discoveries: List<String>) : RemoteAgentRuntime {
        override val hostId: String = "test-host"

        override suspend fun execute(
            command: RemoteCommand,
            timeout: Duration,
        ): RemoteCommandResult {
            assertEquals("python3", command.program)
            return if (command.arguments.size == 2) {
                RemoteCommandResult(0, "[" + discoveries.joinToString(",") + "]", "")
            } else {
                RemoteCommandResult(0, transcript, "")
            }
        }

        override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess =
            error("Not used")
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun discovery(id: String, directory: String): String =
            """
            {
              "id":"$id",
              "file":"/home/test/.claude/projects/project/$id.jsonl",
              "cwd":"$directory",
              "firstText":"Fix parser",
              "lastText":"Done",
              "createdAt":1700000000,
              "updatedAt":1700000010,
              "model":"claude-sonnet"
            }
            """.trimIndent()

        val transcript =
            """
            [
              {
                "type":"user",
                "uuid":"user-1",
                "message":{"role":"user","content":[{"type":"text","text":"Fix parser"}]}
              },
              {
                "type":"assistant",
                "uuid":"assistant-1",
                "message":{
                  "role":"assistant",
                  "content":[{
                    "type":"tool_use",
                    "id":"tool-durable",
                    "name":"Edit",
                    "input":{"file_path":"/workspace/src/Durable.kt"}
                  }]
                }
              }
            ]
            """.trimIndent()
    }
}
