/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.codex

import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentEvent
import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.StartSessionOptions
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CodexAgentConnectionTest {
    @Test
    fun connectionStartsTurnsStreamsEventsAndAnswersApprovals() = runTest {
        val rpc = FakeRpcClient()
        val connection = CodexAgentConnection.create(
            descriptor = CodexAgentProviderFactory().descriptor,
            peer = rpc,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val session = connection.startSession(StartSessionOptions(workingDirectory = "/workspace"))
        assertEquals("thread-1", session.id.value)
        assertEquals(AgentSessionState.IDLE, session.state)

        connection.sendInput(session.id, "Run the tests")
        assertEquals("turn/start", rpc.requests.last().first)
        assertTrue(rpc.requests.last().second.toString().contains("Run the tests"))

        val delta = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            connection.events.first { it is AgentEvent.TextDelta }
        }
        rpc.calls.emit(
            JsonRpcCall(
                method = "item/agentMessage/delta",
                id = null,
                params = json(
                    """
                    {
                      "threadId": "thread-1",
                      "turnId": "turn-1",
                      "itemId": "message-1",
                      "delta": "Working"
                    }
                    """,
                ),
            ),
        )
        assertEquals("Working", assertIs<AgentEvent.TextDelta>(delta.await()).text)

        val approvalEvent = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            connection.events.first { it is AgentEvent.ApprovalRequested }
        }
        rpc.calls.emit(
            JsonRpcCall(
                method = "item/commandExecution/requestApproval",
                id = JsonPrimitive("rpc-1"),
                params = json(
                    """
                    {
                      "threadId": "thread-1",
                      "turnId": "turn-1",
                      "itemId": "command-1",
                      "command": "./gradlew test"
                    }
                    """,
                ),
            ),
        )
        val approval = assertIs<AgentEvent.ApprovalRequested>(approvalEvent.await()).approval
        connection.respondToApproval(
            approvalId = approval.id,
            decision = AgentApprovalDecision.APPROVE_ONCE,
        )
        assertTrue(rpc.responses.single().second.toString().contains("accept"))

        rpc.calls.emit(
            JsonRpcCall(
                method = "item/completed",
                id = null,
                params = json(
                    """
                    {
                      "threadId": "thread-1",
                      "turnId": "turn-1",
                      "item": {
                        "id": "files-1",
                        "type": "fileChange",
                        "status": "completed",
                        "changes": [{"path": "/workspace/build.gradle.kts", "kind": "modify"}]
                      }
                    }
                    """,
                ),
            ),
        )

        val changedFile = connection.changedFiles(session.id).single()
        assertEquals("/workspace/build.gradle.kts", changedFile.remotePath)
        assertEquals(AgentFileChangeKind.MODIFIED, changedFile.kind)
        connection.close()
        assertEquals(true, rpc.closed)
    }

    @Test
    fun attachingStoredThreadLoadsTranscriptAndActivatesTheSameSession() = runTest {
        val rpc = FakeRpcClient()
        val connection = CodexAgentConnection.create(
            descriptor = CodexAgentProviderFactory().descriptor,
            peer = rpc,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val attached = connection.attach(AgentSessionId("saved-thread"))

        assertEquals("saved-thread", attached.id.value)
        assertEquals(AgentSessionState.IDLE, attached.state)
        assertEquals("thread/resume", rpc.requests.last().first)
        assertEquals(
            "saved-thread",
            rpc.requests.last().second.jsonObject.string("threadId"),
        )
        assertEquals("Restored context", connection.transcript(attached.id).single().text)
        assertEquals("saved-thread", connection.sessions.value.single().id.value)
        connection.close()
    }

    private class FakeRpcClient : JsonRpcClient {
        override val calls = MutableSharedFlow<JsonRpcCall>(extraBufferCapacity = 16)
        val requests = mutableListOf<Pair<String, JsonElement>>()
        val responses = mutableListOf<Pair<JsonElement, JsonElement>>()
        var closed = false

        override suspend fun request(method: String, params: JsonElement): JsonElement {
            requests += method to params
            return when (method) {
                "initialize" -> buildJsonObject {}
                "thread/list" -> json("""{"data":[],"nextCursor":null}""")
                "thread/start" -> json(
                    """
                    {
                      "thread": {
                        "id": "thread-1",
                        "preview": "",
                        "cwd": "/workspace",
                        "status": {"type": "idle"},
                        "turns": []
                      }
                    }
                    """,
                )
                "thread/resume" -> json(
                    """
                    {
                      "thread": {
                        "id": "saved-thread",
                        "preview": "Restored context",
                        "cwd": "/workspace",
                        "status": {"type": "idle"},
                        "turns": [{
                          "id": "turn-saved",
                          "items": [{
                            "id": "message-saved",
                            "type": "agentMessage",
                            "text": "Restored context"
                          }]
                        }]
                      }
                    }
                    """,
                )
                "turn/start" -> json("""{"turn":{"id":"turn-1","status":"inProgress"}}""")
                else -> error("Unexpected method $method")
            }
        }

        override suspend fun notify(method: String, params: JsonElement) = Unit

        override suspend fun respond(id: JsonElement, result: JsonElement) {
            responses += id to result
        }

        override suspend fun close() {
            closed = true
        }
    }

    private companion object {
        fun json(value: String): JsonElement = Json.parseToJsonElement(value)
    }
}
