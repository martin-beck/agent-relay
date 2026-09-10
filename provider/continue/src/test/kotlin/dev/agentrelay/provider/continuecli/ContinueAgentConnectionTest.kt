/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.continuecli

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContinueAgentConnectionTest {
    @Test
    fun attachesHistoryRoutesPermissionsAndEmitsCompletedWork() = runTest {
        val runtime = FakeRuntime(listOf(listing("session-1", "/workspace")))
        val client = FakeContinueClient("session-1", "/workspace", pending = true)
        val starter = RecordingStarter { _, _, _ -> client }
        val connection = ContinueAgentConnection.create(
            descriptor = ContinueAgentProviderFactory().descriptor,
            runtime = runtime,
            executable = "/home/test/.local/bin/cn",
            clientStarter = starter,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val requested = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            connection.events.first { it is AgentEvent.ApprovalRequested }
        }

        val attached = connection.attach(AgentSessionId("session-1"))
        assertEquals(AgentSessionState.WAITING_FOR_APPROVAL, attached.state)
        assertEquals("Run tests", connection.transcript(attached.id).single().text)
        val approval = assertIs<AgentEvent.ApprovalRequested>(requested.await()).approval
        assertEquals("./gradlew test", approval.command)

        connection.respondToApproval(approval.id, AgentApprovalDecision.DECLINE)
        assertEquals(listOf("permission-1" to false), client.permissionResponses)
        assertEquals(AgentSessionState.RUNNING, connection.sessions.value.single().state)

        connection.sendInput(attached.id, "Try another approach")
        assertEquals(listOf("Try another approach"), client.messages)
        val completed = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            connection.events.first { it is AgentEvent.MessageCompleted }
        }
        val turnCompleted = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            connection.events.first { it is AgentEvent.TurnCompleted }
        }
        client.completeWith("BUILD SUCCESSFUL")
        connection.interrupt(attached.id)

        assertEquals("BUILD SUCCESSFUL", assertIs<AgentEvent.MessageCompleted>(completed.await()).text)
        assertTrue(assertIs<AgentEvent.TurnCompleted>(turnCompleted.await()).successful)
        assertEquals(AgentSessionState.IDLE, connection.sessions.value.single().state)
        val changed = connection.changedFiles(attached.id).single()
        assertEquals("/workspace/src/Main.kt", changed.remotePath)
        assertEquals(AgentFileChangeKind.MODIFIED, changed.kind)

        connection.close()
        assertTrue(client.closed)
    }

    @Test
    fun keepsOneServerPerAttachedSessionAndPassesStartOptions() = runTest {
        val runtime = FakeRuntime(
            listOf(
                listing("session-1", "/one"),
                listing("session-2", "/two"),
            ),
        )
        val starter = RecordingStarter { id, directory, _ ->
            FakeContinueClient(id.value, directory, pending = false)
        }
        val connection = ContinueAgentConnection.create(
            descriptor = ContinueAgentProviderFactory().descriptor,
            runtime = runtime,
            executable = "/home/test/.local/bin/cn",
            clientStarter = starter,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        connection.attach(AgentSessionId("session-1"))
        connection.attach(AgentSessionId("session-2"))
        connection.attach(AgentSessionId("session-1"))
        assertEquals(2, starter.calls.size)
        assertEquals(setOf("/one", "/two"), starter.calls.mapNotNull { it.directory }.toSet())

        val started = connection.startSession(
            StartSessionOptions(
                workingDirectory = "/new",
                model = "owner/model",
                providerOptions = mapOf("readonly" to "true"),
            ),
        )
        val startCall = starter.calls.last()
        assertEquals(started.id, startCall.id)
        assertEquals("/new", startCall.directory)
        assertEquals("owner/model", startCall.options.model)
        assertEquals("true", startCall.options.providerOptions["readonly"])
        assertEquals("owner/model", started.model)

        connection.close()
        assertTrue(starter.clients.all(FakeContinueClient::closed))
    }

    private data class StartCall(
        val id: AgentSessionId,
        val directory: String?,
        val options: StartSessionOptions,
    )

    private class RecordingStarter(
        private val factory: (
            AgentSessionId,
            String?,
            StartSessionOptions,
        ) -> FakeContinueClient,
    ) : ContinueClientStarter {
        val calls = mutableListOf<StartCall>()
        val clients = mutableListOf<FakeContinueClient>()

        override suspend fun start(
            sessionId: AgentSessionId,
            workingDirectory: String?,
            options: StartSessionOptions,
        ): ContinueClient {
            calls += StartCall(sessionId, workingDirectory, options)
            return factory(sessionId, workingDirectory, options).also(clients::add)
        }
    }

    private class FakeContinueClient(
        private val sessionId: String,
        private val directory: String?,
        pending: Boolean,
    ) : ContinueClient {
        var currentState = stateJson(
            sessionId = sessionId,
            directory = directory,
            processing = false,
            pending = pending,
            assistantText = null,
        )
        val messages = mutableListOf<String>()
        val permissionResponses = mutableListOf<Pair<String, Boolean>>()
        var closed = false

        override suspend fun state(): JsonObject = currentState

        override suspend fun sendMessage(message: String) {
            messages += message
            currentState = stateJson(
                sessionId,
                directory,
                processing = true,
                pending = false,
                assistantText = null,
            )
        }

        override suspend fun respondToPermission(requestId: String, approved: Boolean) {
            permissionResponses += requestId to approved
            currentState = stateJson(
                sessionId,
                directory,
                processing = true,
                pending = false,
                assistantText = null,
            )
        }

        override suspend fun pause() = Unit

        override suspend fun diff(): String =
            """
            diff --git a/src/Main.kt b/src/Main.kt
            --- a/src/Main.kt
            +++ b/src/Main.kt
            """.trimIndent()

        override suspend fun close() {
            closed = true
        }

        fun completeWith(text: String) {
            currentState = stateJson(
                sessionId,
                directory,
                processing = false,
                pending = false,
                assistantText = text,
            )
        }
    }

    private class FakeRuntime(private val listings: List<String>) : RemoteAgentRuntime {
        override val hostId: String = "test-host"

        override suspend fun execute(
            command: RemoteCommand,
            timeout: Duration,
        ): RemoteCommandResult {
            assertEquals(listOf("ls", "--json"), command.arguments)
            return RemoteCommandResult(
                0,
                """{"sessions":[""" + listings.joinToString(",") + "]}",
                "",
            )
        }

        override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess =
            error("Not used")
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun listing(id: String, directory: String): String =
            """
            {
              "id":"$id",
              "timestamp":"2026-08-31T23:32:59.441Z",
              "workspaceDirectory":"$directory",
              "title":"Session $id",
              "firstUserMessage":"Run tests",
              "isRemote":false
            }
            """.trimIndent()

        fun stateJson(
            sessionId: String,
            directory: String?,
            processing: Boolean,
            pending: Boolean,
            assistantText: String?,
        ): JsonObject {
            val assistant = assistantText?.let {
                """,{"message":{"role":"assistant","content":"$it","usage":{"model":"owner/model"}}}"""
            }.orEmpty()
            val permission = if (pending) {
                """
                {
                  "toolName":"runTerminalCommand",
                  "toolArgs":{"command":"./gradlew test","cwd":"/workspace"},
                  "requestId":"permission-1",
                  "timestamp":1700000000000
                }
                """.trimIndent()
            } else {
                "null"
            }
            val directoryJson = directory?.let { "\"$it\"" } ?: "null"
            return json.parseToJsonElement(
                """
                {
                  "session":{
                    "sessionId":"$sessionId",
                    "title":"Session $sessionId",
                    "workspaceDirectory":$directoryJson,
                    "history":[{"message":{"role":"user","content":"Run tests"}}$assistant],
                    "usage":{"promptTokens":42}
                  },
                  "isProcessing":$processing,
                  "messageQueueLength":0,
                  "pendingPermission":$permission
                }
                """.trimIndent(),
            ).jsonObject
        }
    }
}
