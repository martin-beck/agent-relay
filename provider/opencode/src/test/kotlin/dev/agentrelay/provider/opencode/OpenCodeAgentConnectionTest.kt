/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.opencode

import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentEvent
import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.StartSessionOptions
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpenCodeAgentConnectionTest {
    @Test
    fun connectionDiscoversSessionsAndUsesOfficialSessionEndpoints() = runTest {
        val client = FakeOpenCodeClient()
        val connection = OpenCodeAgentConnection.create(
            descriptor = OpenCodeAgentProviderFactory().descriptor,
            client = client,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val existing = connection.sessions.value.single()
        assertEquals("ses-existing", existing.id.value)
        assertEquals(AgentSessionState.RUNNING, existing.state)
        assertEquals("/workspace", existing.workingDirectory)
        assertEquals(setOf(null, "/workspace"), client.eventDirectories.toSet())

        val attached = connection.attach(existing.id)
        assertEquals("ollama/qwen", attached.model)
        assertEquals("Run tests", connection.transcript(existing.id).single().text)

        val started = connection.startSession(
            StartSessionOptions(
                workingDirectory = "/other",
                model = "example-provider/example-model",
                providerOptions = mapOf("title" to "New work"),
            ),
        )
        connection.sendInput(started.id, "Return READY")
        val prompt = client.posts.last { it.path.endsWith("/prompt_async") }
        assertEquals("/other", prompt.directory)
        assertTrue(prompt.body.toString().contains("example-model"))
        assertTrue(prompt.body.toString().contains("Return READY"))
        assertEquals(AgentSessionState.RUNNING, connection.sessions.value.first { it.id == started.id }.state)

        connection.interrupt(started.id)
        assertEquals("/session/ses-new/abort", client.posts.last().path)
        assertEquals(AgentSessionState.IDLE, connection.sessions.value.first { it.id == started.id }.state)

        val changed = connection.changedFiles(existing.id).single()
        assertEquals("src/Main.kt", changed.remotePath)
        assertEquals(AgentFileChangeKind.MODIFIED, changed.kind)

        connection.close()
        assertTrue(client.closed)
    }

    @Test
    fun connectionStreamsPermissionsAndRoutesApprovalReplies() = runTest {
        val client = FakeOpenCodeClient()
        val connection = OpenCodeAgentConnection.create(
            descriptor = OpenCodeAgentProviderFactory().descriptor,
            client = client,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val session = connection.sessions.value.single()
        val requested = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            connection.events.first { it is AgentEvent.ApprovalRequested }
        }

        client.event("/workspace").emit(
            objectFrom(
                """
                {
                  "type":"permission.updated",
                  "properties":{
                    "id":"permission-1",
                    "type":"bash",
                    "sessionID":"ses-existing",
                    "title":"Run tests",
                    "metadata":{"command":"./gradlew test"}
                  }
                }
                """,
            ),
        )

        val approval = assertIs<AgentEvent.ApprovalRequested>(requested.await()).approval
        assertEquals("./gradlew test", approval.command)
        assertEquals(
            AgentSessionState.WAITING_FOR_APPROVAL,
            connection.sessions.value.first { it.id == session.id }.state,
        )

        connection.respondToApproval(
            approval.id,
            AgentApprovalDecision.APPROVE_FOR_SESSION,
        )
        val reply = client.posts.last()
        assertEquals("/session/ses-existing/permissions/permission-1", reply.path)
        assertTrue(reply.body.toString().contains("always"))
        assertEquals(
            AgentSessionState.RUNNING,
            connection.sessions.value.first { it.id == session.id }.state,
        )

        val idle = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            connection.events.first {
                it is AgentEvent.SessionStateChanged && it.state == AgentSessionState.IDLE
            }
        }
        client.event("/workspace").emit(
            objectFrom(
                """{"type":"session.status","properties":{"sessionID":"ses-existing","status":{"type":"idle"}}}""",
            ),
        )
        assertIs<AgentEvent.SessionStateChanged>(idle.await())
        assertEquals(AgentSessionState.IDLE, connection.sessions.value.single().state)
        connection.close()
    }

    @Test
    fun openDeskRoutesPermissionAndQuestionRepliesToImplementedEndpoints() = runTest {
        val client = FakeOpenCodeClient()
        val connection = OpenCodeAgentConnection.create(
            descriptor = OpenCodeAgentProviderFactory().descriptor,
            client = client,
            dialect = OpenCodeProtocolDialect.OPENDESK,
            providerName = "OpenDesk",
            metadataNamespace = "opendesk",
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        val eventFlow = client.event("/workspace")

        val permissionEvent = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            connection.events.first { it is AgentEvent.ApprovalRequested }
        }
        eventFlow.emit(
            objectFrom(
                """
                {
                  "type":"permission.asked",
                  "properties":{
                    "id":"permission-1",
                    "sessionID":"ses-existing",
                    "permission":"bash",
                    "metadata":{"input":{"command":"./gradlew test"}}
                  }
                }
                """,
            ),
        )
        val permission = assertIs<AgentEvent.ApprovalRequested>(permissionEvent.await()).approval
        connection.respondToApproval(permission.id, AgentApprovalDecision.APPROVE_ONCE)
        assertEquals("/permission/permission-1/reply", client.posts.last().path)
        assertTrue(client.posts.last().body.toString().contains("\"reply\":\"once\""))

        val questionEvent = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            connection.events.first { it is AgentEvent.ApprovalRequested }
        }
        eventFlow.emit(
            objectFrom(
                """
                {
                  "type":"question.asked",
                  "properties":{
                    "id":"question-1",
                    "sessionID":"ses-existing",
                    "questions":[{
                      "question":"Which mode?",
                      "header":"Mode",
                      "options":[{"label":"Fast"}],
                      "multiple":false,
                      "custom":true
                    }]
                  }
                }
                """,
            ),
        )
        val question = assertIs<AgentEvent.ApprovalRequested>(questionEvent.await()).approval
        connection.respondToApproval(
            question.id,
            AgentApprovalDecision.SUBMIT,
            mapOf("question-1:0" to listOf("Fast")),
        )
        assertEquals("/question/question-1/reply", client.posts.last().path)
        assertTrue(client.posts.last().body.toString().contains("[[\"Fast\"]]"))
        assertFailsWith<IllegalStateException> {
            connection.changedFiles(connection.sessions.value.single().id)
        }
        connection.close()
    }

    private data class PostCall(
        val path: String,
        val body: JsonElement,
        val directory: String?,
    )

    private class FakeOpenCodeClient : OpenCodeClient {
        val posts = mutableListOf<PostCall>()
        val eventDirectories = mutableListOf<String?>()
        private val eventFlows = mutableMapOf<String, MutableSharedFlow<JsonObject>>()
        var closed = false

        override suspend fun get(path: String, directory: String?): JsonElement = when {
            path == "/experimental/session?scope=project" -> element(
                "[" + existingSession + "]",
            )
            path == "/project" -> element(
                """[{"id":"project-1","worktree":"/workspace"}]""",
            )
            path == "/session/status" && directory == null -> element("{}")
            path == "/session" && directory == null -> element("[]")
            path == "/session/status" && directory == "/workspace" ->
                element("""{"ses-existing":{"type":"busy"}}""")
            path == "/session" && directory == "/workspace" ->
                element("[" + existingSession + "]")
            path == "/session/ses-existing" -> element(existingSession)
            path == "/session/ses-existing/message" -> element(
                """
                [{
                  "info":{"id":"msg-1","sessionID":"ses-existing","role":"user","time":{"created":1700000000000}},
                  "parts":[{"id":"part-1","type":"text","text":"Run tests"}]
                }]
                """,
            )
            path == "/session/ses-existing/diff" -> element(
                """[{"file":"src/Main.kt","before":"old","after":"new","additions":1,"deletions":1}]""",
            )
            else -> error("Unexpected GET $path for $directory")
        }

        override suspend fun post(
            path: String,
            body: JsonElement,
            directory: String?,
        ): JsonElement {
            posts += PostCall(path, body, directory)
            return when (path) {
                "/session" -> element(
                    """
                    {
                      "id":"ses-new",
                      "title":"New work",
                      "directory":"/other",
                      "time":{"created":1700000010000,"updated":1700000010000}
                    }
                    """,
                )
                else -> element("{}")
            }
        }

        override suspend fun events(directory: String?): Flow<JsonObject> {
            eventDirectories += directory
            return event(directory)
        }

        fun event(directory: String?): MutableSharedFlow<JsonObject> =
            eventFlows.getOrPut(directory.orEmpty()) {
                MutableSharedFlow(extraBufferCapacity = 16)
            }

        override suspend fun close() {
            closed = true
        }

        private companion object {
            val existingSession =
                """
                {
                  "id":"ses-existing",
                  "title":"Existing",
                  "directory":"/workspace",
                  "model":{"providerID":"ollama","id":"qwen"},
                  "time":{"created":1700000000000,"updated":1700000005000}
                }
                """.trimIndent()
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun element(value: String): JsonElement = json.parseToJsonElement(value)

        fun objectFrom(value: String): JsonObject = element(value).jsonObject
    }
}
