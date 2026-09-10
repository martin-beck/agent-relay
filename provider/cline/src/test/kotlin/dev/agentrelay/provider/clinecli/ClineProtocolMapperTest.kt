/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.clinecli

import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentApprovalType
import dev.agentrelay.provider.api.AgentEvent
import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.AgentToolStatus
import dev.agentrelay.provider.api.AgentTranscriptRole
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class ClineProtocolMapperTest {
    @Test
    fun historyAndDurableTranscriptMapStableFields() {
        val session = ClineSessionMapper.fromHistory(
            objectFrom(
                """
                {
                  "sessionId":"session-1",
                  "status":"completed",
                  "startedAt":"2026-08-31T23:33:16.054Z",
                  "updatedAt":"2026-08-31T23:34:37.511Z",
                  "provider":"ollama",
                  "model":"example-model",
                  "cwd":"/workspace",
                  "prompt":"Fix parser",
                  "messagesPath":"/home/test/.cline/data/sessions/session-1/messages.json"
                }
                """.trimIndent(),
            ),
        )
        assertEquals(AgentSessionState.IDLE, session.state)
        assertEquals("/workspace", session.workingDirectory)
        assertEquals("ollama", session.metadata["cline.provider"])

        val entries = ClineTranscriptMapper.fromDocument(
            session.id,
            objectFrom(
                """
                {
                  "messages":[
                    {"id":"one","role":"user","ts":1700000000000,"content":"Fix parser"},
                    {
                      "id":"two",
                      "role":"assistant",
                      "ts":1700000001000,
                      "content":[
                        {"type":"thinking","thinking":"Inspecting"},
                        {"type":"text","text":"Working"},
                        {
                          "type":"tool_use",
                          "id":"tool-1",
                          "name":"write_to_file",
                          "input":{"path":"src/Main.kt","content":"fixed"}
                        }
                      ]
                    },
                    {
                      "id":"three",
                      "role":"user",
                      "content":[
                        {
                          "type":"tool_result",
                          "tool_use_id":"tool-1",
                          "content":"Saved"
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertEquals(
            listOf(
                AgentTranscriptRole.USER,
                AgentTranscriptRole.AGENT,
                AgentTranscriptRole.AGENT,
                AgentTranscriptRole.TOOL,
                AgentTranscriptRole.TOOL,
            ),
            entries.map { it.role },
        )
        assertEquals(AgentMessageChannel.REASONING_SUMMARY, entries[1].channel)
        assertEquals(AgentToolStatus.STARTED.name, entries[3].metadata["cline.toolStatus"])
        assertEquals(AgentToolStatus.COMPLETED.name, entries[4].metadata["cline.toolStatus"])
        assertEquals(
            listOf("/workspace/src/Main.kt"),
            ClineFileChangeMapper.fromTranscript(entries, "/workspace").map { it.remotePath },
        )
    }

    @Test
    fun acpUpdatesPermissionsAndWorkspaceBoundariesMapExactly() {
        val sessionId = AgentSessionId("session-1")
        val delta = ClineEventMapper.fromCall(
            sessionId,
            call(
                "session/update",
                """
                {
                  "sessionId":"session-1",
                  "update":{
                    "sessionUpdate":"agent_message_chunk",
                    "content":{"type":"text","text":"READY"}
                  }
                }
                """.trimIndent(),
            ),
            "/workspace",
        ).single()
        assertEquals("READY", (delta as AgentEvent.TextDelta).text)

        val toolEvents = ClineEventMapper.fromCall(
            sessionId,
            call(
                "session/update",
                """
                {
                  "sessionId":"session-1",
                  "update":{
                    "sessionUpdate":"tool_call",
                    "toolCallId":"tool-1",
                    "title":"Edit Main.kt",
                    "kind":"edit",
                    "status":"pending",
                    "rawInput":{"path":"src/Main.kt"}
                  }
                }
                """.trimIndent(),
            ),
            "/workspace",
        )
        val changed = toolEvents.filterIsInstance<AgentEvent.FileChanged>().single().file
        assertEquals("/workspace/src/Main.kt", changed.remotePath)
        assertEquals(AgentFileChangeKind.MODIFIED, changed.kind)

        assertTrue(
            ClineEventMapper.fromCall(
                sessionId,
                call(
                    "session/update",
                    """
                    {
                      "sessionId":"session-1",
                      "update":{
                        "sessionUpdate":"tool_call",
                        "toolCallId":"tool-2",
                        "title":"Edit outside",
                        "kind":"edit",
                        "rawInput":{"path":"../outside"}
                      }
                    }
                    """.trimIndent(),
                ),
                "/workspace",
            ).none { it is AgentEvent.FileChanged },
        )

        val pending = assertNotNull(
            ClineApprovalMapper.fromCall(
                call(
                    "session/request_permission",
                    """
                    {
                      "sessionId":"session-1",
                      "toolCall":{
                        "toolCallId":"tool-approval",
                        "title":"Run tests",
                        "kind":"execute",
                        "status":"pending",
                        "rawInput":{"command":"./gradlew test","cwd":"/workspace"}
                      },
                      "options":[
                        {"optionId":"once","kind":"allow_once"},
                        {"optionId":"always","kind":"allow_always"},
                        {"optionId":"reject","kind":"reject_once"}
                      ]
                    }
                    """.trimIndent(),
                    id = 41,
                ),
            ),
        )
        assertEquals("session-1:tool-approval", pending.approval.id.value)
        assertEquals(AgentApprovalType.COMMAND, pending.approval.type)
        assertEquals("./gradlew test", pending.approval.command)
        assertEquals(
            setOf(
                AgentApprovalDecision.APPROVE_ONCE,
                AgentApprovalDecision.APPROVE_FOR_SESSION,
                AgentApprovalDecision.DECLINE,
                AgentApprovalDecision.CANCEL,
            ),
            pending.approval.availableDecisions,
        )
    }

    private fun call(method: String, params: String, id: Int? = null) = ClineAcpCall(
        method = method,
        params = objectFrom(params),
        id = id?.let(::JsonPrimitive),
    )

    private fun objectFrom(value: String) = json.parseToJsonElement(value).jsonObject

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
