/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.codex

import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentTranscriptRole
import dev.agentrelay.provider.api.StartSessionOptions
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class CodexThreadClientTest {
    @Test
    fun startUsesTheV2ThreadStartContractAndProtocolSandboxEnum() = runTest {
        val rpc = FakeRpcClient { method, params ->
            assertEquals("thread/start", method)
            assertEquals("/workspace", params.jsonObject.string("cwd"))
            assertEquals("gpt-5-codex", params.jsonObject.string("model"))
            assertEquals("on-request", params.jsonObject.string("approvalPolicy"))
            assertEquals("workspace-write", params.jsonObject.string("sandbox"))
            assertEquals("agent_relay", params.jsonObject.string("serviceName"))
            json("""{"thread":{"id":"thread-new","status":{"type":"idle"}}}""")
        }

        val session = CodexThreadClient(rpc).start(
            StartSessionOptions(
                workingDirectory = "/workspace",
                model = "gpt-5-codex",
            ),
        )

        assertEquals("thread-new", session.jsonObject.string("id"))
    }

    @Test
    fun listSessionsFollowsOpaquePaginationCursor() = runTest {
        val rpc = FakeRpcClient { method, params ->
            require(method == "thread/list")
            if (params.jsonObject.string("cursor") == null) {
                json(
                    """
                    {
                      "data": [{"id": "new", "preview": "New", "updatedAt": 20, "status": {"type": "idle"}}],
                      "nextCursor": "cursor-2"
                    }
                    """,
                )
            } else {
                json(
                    """
                    {
                      "data": [{"id": "old", "preview": "Old", "updatedAt": 10, "status": {"type": "notLoaded"}}],
                      "nextCursor": null
                    }
                    """,
                )
            }
        }

        val sessions = CodexThreadClient(rpc).listSessions()

        assertEquals(listOf("new", "old"), sessions.map { it.id.value })
        assertEquals(2, rpc.requests.size)
        assertEquals("cursor-2", rpc.requests[1].second.jsonObject.string("cursor"))
        assertTrue(rpc.requests[0].second.toString().contains("appServer"))
    }

    @Test
    fun transcriptFallsBackToPaginatedTurnHistory() = runTest {
        val rpc = FakeRpcClient { method, _ ->
            when (method) {
                "thread/read" -> throw JsonRpcException("Paginated history is not readable")
                "thread/turns/list" -> json(
                    """
                    {
                      "data": [{
                        "id": "turn-1",
                        "items": [
                          {"id": "user-1", "type": "userMessage", "text": "Hello"},
                          {"id": "agent-1", "type": "agentMessage", "text": "Hi"}
                        ]
                      }],
                      "nextCursor": null
                    }
                    """,
                )
                else -> error("Unexpected method $method")
            }
        }

        val entries = CodexThreadClient(rpc).transcript(AgentSessionId("thread-1"))

        assertEquals(2, entries.size)
        assertEquals(AgentTranscriptRole.USER, entries[0].role)
        assertEquals(AgentTranscriptRole.AGENT, entries[1].role)
    }

    private class FakeRpcClient(private val handler: suspend (String, JsonElement) -> JsonElement) : JsonRpcClient {
        override val calls = MutableSharedFlow<JsonRpcCall>()
        val requests = mutableListOf<Pair<String, JsonElement>>()

        override suspend fun request(method: String, params: JsonElement): JsonElement {
            requests += method to params
            return handler(method, params)
        }

        override suspend fun notify(method: String, params: JsonElement) = Unit

        override suspend fun respond(id: JsonElement, result: JsonElement) = Unit

        override suspend fun close() = Unit
    }

    private companion object {
        fun json(value: String): JsonObject = Json.parseToJsonElement(value).jsonObject
    }
}
