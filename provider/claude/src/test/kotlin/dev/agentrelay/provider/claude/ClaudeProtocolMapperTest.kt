/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.claude

import dev.agentrelay.provider.api.AgentApprovalDecision
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class ClaudeProtocolMapperTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun discoverySummaryMapsStableSessionFields() {
        val session = ClaudeSessionMapper.fromDiscovery(
            objectFrom(
                """
                {
                  "id":"11111111-2222-4333-8444-555555555555",
                  "file":"/home/test/.claude/projects/-workspace/session.jsonl",
                  "cwd":"/workspace",
                  "firstText":"Fix parser",
                  "lastText":"Done",
                  "createdAt":1700000000,
                  "updatedAt":1700000010,
                  "model":"example-model"
                }
                """.trimIndent(),
            ),
        )
        assertEquals("11111111-2222-4333-8444-555555555555", session.id.value)
        assertEquals("Fix parser", session.preview)
        assertEquals("/workspace", session.workingDirectory)
        assertEquals("example-model", session.model)
        assertEquals(AgentSessionState.IDLE, session.state)
    }

    @Test
    fun durableHistoryMapsTextToolsResultsAndFiles() {
        val rows = arrayFrom(
            """
            [
              {
                "type":"user",
                "uuid":"user-1",
                "timestamp":"2026-09-01T01:04:59.061Z",
                "message":{"role":"user","content":[{"type":"text","text":"Fix parser"}]}
              },
              {
                "type":"assistant",
                "uuid":"assistant-1",
                "timestamp":"2026-09-01T01:05:52.039Z",
                "message":{
                  "role":"assistant",
                  "content":[
                    {"type":"text","text":"Working"},
                    {
                      "type":"tool_use",
                      "id":"tool-1",
                      "name":"Edit",
                      "input":{"file_path":"/workspace/src/Main.kt","old_string":"a","new_string":"b"}
                    }
                  ]
                }
              },
              {
                "type":"user",
                "uuid":"result-1",
                "message":{
                  "role":"user",
                  "content":[{"type":"tool_result","tool_use_id":"tool-1","content":"Updated"}]
                }
              }
            ]
            """.trimIndent(),
        )
        val entries = ClaudeTranscriptMapper.fromRows(AgentSessionId("session-1"), rows)
        assertEquals(
            listOf(
                AgentTranscriptRole.USER,
                AgentTranscriptRole.AGENT,
                AgentTranscriptRole.TOOL,
                AgentTranscriptRole.TOOL,
            ),
            entries.map { it.role },
        )
        assertEquals(AgentMessageChannel.FINAL, entries[1].channel)
        assertEquals(AgentToolStatus.STARTED.name, entries[2].metadata["claude.toolStatus"])
        assertEquals(AgentToolStatus.COMPLETED.name, entries[3].metadata["claude.toolStatus"])

        val files = ClaudeFileChangeMapper.fromTranscript(entries, "/workspace")
        assertEquals(
            listOf(
                dev.agentrelay.provider.api.AgentChangedFile(
                    remotePath = "/workspace/src/Main.kt",
                    kind = AgentFileChangeKind.MODIFIED,
                ),
            ),
            files,
        )
    }

    @Test
    fun streamDeltasResultsAndPermissionsMapWithoutGuessing() {
        val sessionId = AgentSessionId("session-1")
        val delta = ClaudeEventMapper.fromWire(
            sessionId,
            objectFrom(
                """
                {
                  "type":"stream_event",
                  "event":{
                    "type":"content_block_delta",
                    "index":0,
                    "delta":{"type":"text_delta","text":"READY"}
                  }
                }
                """.trimIndent(),
            ),
        ).single()
        assertEquals("READY", (delta as dev.agentrelay.provider.api.AgentEvent.TextDelta).text)

        val approval = assertNotNull(
            ClaudeApprovalMapper.fromControlRequest(
                sessionId,
                objectFrom(
                    """
                    {
                      "type":"control_request",
                      "request_id":"permission-1",
                      "request":{
                        "subtype":"can_use_tool",
                        "tool_name":"Bash",
                        "input":{"command":"./gradlew test"},
                        "tool_use_id":"tool-1",
                        "title":"Run tests",
                        "permission_suggestions":[{"type":"addRules","rules":[{"toolName":"Bash"}]}]
                      }
                    }
                    """.trimIndent(),
                ),
            ),
        )
        assertEquals("./gradlew test", approval.command)
        assertEquals(
            setOf(
                AgentApprovalDecision.APPROVE_ONCE,
                AgentApprovalDecision.APPROVE_FOR_SESSION,
                AgentApprovalDecision.DECLINE,
            ),
            approval.availableDecisions,
        )

        val completed = ClaudeEventMapper.fromWire(
            sessionId,
            objectFrom(
                """{"type":"result","subtype":"success","is_error":false,"result":"READY"}""",
            ),
        )
        assertTrue(completed.any { it is dev.agentrelay.provider.api.AgentEvent.TurnCompleted })
    }

    @Test
    fun fileChangesStayInsideTheKnownWorkspace() {
        val entries = ClaudeTranscriptMapper.fromRows(
            AgentSessionId("session-1"),
            arrayFrom(
                """
                [{
                  "type":"assistant",
                  "uuid":"assistant-1",
                  "message":{"role":"assistant","content":[
                    {"type":"tool_use","id":"one","name":"Edit","input":{"file_path":"src/Main.kt"}},
                    {"type":"tool_use","id":"two","name":"Write","input":{"file_path":"../secret"}},
                    {"type":"tool_use","id":"three","name":"Edit","input":{"file_path":"/tmp/outside"}},
                    {"type":"tool_use","id":"four","name":"Bash","input":{"file_path":"/workspace/not-a-direct-edit"}}
                  ]}
                }]
                """.trimIndent(),
            ),
        )

        assertEquals(
            listOf("/workspace/src/Main.kt"),
            ClaudeFileChangeMapper.fromTranscript(entries, "/workspace").map { it.remotePath },
        )
        assertTrue(ClaudeFileChangeMapper.fromTranscript(entries, null).isEmpty())
    }

    private fun objectFrom(value: String) = json.parseToJsonElement(value).jsonObject

    private fun arrayFrom(value: String) = json.parseToJsonElement(value).jsonArray
}
