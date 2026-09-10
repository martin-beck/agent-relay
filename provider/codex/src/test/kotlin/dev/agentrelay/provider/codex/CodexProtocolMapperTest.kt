/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.codex

import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentApprovalType
import dev.agentrelay.provider.api.AgentEvent
import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.AgentTranscriptRole
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class CodexProtocolMapperTest {
    private val json = Json

    @Test
    fun threadSummaryMapsStableFieldsAndMetadata() {
        val thread = objectFrom(
            """
            {
              "id": "thread-1",
              "name": "Fix build",
              "preview": "Investigate the build",
              "cwd": "/workspace",
              "createdAt": 100,
              "updatedAt": 200,
              "status": {"type": "active"},
              "source": "cli",
              "modelProvider": "openai",
              "cliVersion": "0.151.0",
              "gitInfo": {"branch": "main", "sha": "abc"}
            }
            """,
        )

        val session = CodexSessionMapper.fromThread(thread)

        assertEquals("thread-1", session.id.value)
        assertEquals("Fix build", session.title)
        assertEquals(AgentSessionState.RUNNING, session.state)
        assertEquals(false, session.canAcceptInput)
        assertEquals("main", session.metadata["git.branch"])
        assertEquals("0.151.0", session.metadata["cliVersion"])
    }

    @Test
    fun transcriptReconstructsUserAgentAndToolEntries() {
        val thread = objectFrom(
            """
            {
              "id": "thread-1",
              "turns": [{
                "id": "turn-1",
                "items": [
                  {"id": "u1", "type": "userMessage", "content": [{"type": "input_text", "text": "Run tests"}]},
                  {"id": "a1", "type": "agentMessage", "text": "Tests pass"},
                  {
                    "id": "c1",
                    "type": "commandExecution",
                    "command": "./gradlew test",
                    "aggregatedOutput": "BUILD SUCCESSFUL"
                  }
                ]
              }]
            }
            """,
        )

        val entries = CodexTranscriptMapper.fromThread(thread)

        assertEquals(3, entries.size)
        assertEquals(AgentTranscriptRole.USER, entries[0].role)
        assertEquals("Run tests", entries[0].text)
        assertEquals(AgentTranscriptRole.AGENT, entries[1].role)
        assertEquals(AgentTranscriptRole.TOOL, entries[2].role)
        assertTrue(entries[2].text.contains("BUILD SUCCESSFUL"))
    }

    @Test
    fun completedFileChangeProducesToolAndFileEvents() {
        val call = JsonRpcCall(
            method = "item/completed",
            id = null,
            params = objectFrom(
                """
                {
                  "threadId": "thread-1",
                  "turnId": "turn-1",
                  "item": {
                    "id": "item-1",
                    "type": "fileChange",
                    "status": "completed",
                    "changes": [
                      {"path": "/workspace/new.kt", "kind": "add"},
                      {"path": "/workspace/old.kt", "oldPath": "/workspace/before.kt", "kind": "rename"}
                    ]
                  }
                }
                """,
            ),
        )

        val events = CodexEventMapper.map(call)

        assertEquals(3, events.size)
        val files = events.filterIsInstance<AgentEvent.FileChanged>()
        assertEquals(AgentFileChangeKind.ADDED, files[0].file.kind)
        assertEquals(AgentFileChangeKind.RENAMED, files[1].file.kind)
        assertEquals("/workspace/before.kt", files[1].file.oldRemotePath)
    }

    @Test
    fun commandApprovalPreservesAvailableServerDecisions() {
        val pending = CodexApprovalMapper.fromCall(
            JsonRpcCall(
                method = "item/commandExecution/requestApproval",
                id = JsonPrimitive(7),
                params = objectFrom(
                    """
                    {
                      "threadId": "thread-1",
                      "turnId": "turn-1",
                      "itemId": "item-1",
                      "command": "rm generated.tmp",
                      "cwd": "/workspace",
                      "reason": "Remove generated output",
                      "availableDecisions": ["accept", "decline"]
                    }
                    """,
                ),
            ),
        )

        requireNotNull(pending)
        assertEquals(AgentApprovalType.COMMAND, pending.approval.type)
        assertEquals(
            setOf(AgentApprovalDecision.APPROVE_ONCE, AgentApprovalDecision.DECLINE),
            pending.approval.availableDecisions,
        )
        assertEquals("rm generated.tmp", pending.approval.command)
        assertEquals(
            "accept",
            CodexApprovalMapper.response(
                pending,
                AgentApprovalDecision.APPROVE_ONCE,
                emptyMap(),
            ).jsonObject["decision"]?.asString(),
        )
    }

    @Test
    fun userInputApprovalMapsQuestionsAndAnswers() {
        val pending = CodexApprovalMapper.fromCall(
            JsonRpcCall(
                method = "item/tool/requestUserInput",
                id = JsonPrimitive("rpc-9"),
                params = objectFrom(
                    """
                    {
                      "threadId": "thread-1",
                      "turnId": "turn-1",
                      "requestId": "request-1",
                      "questions": [{
                        "id": "strategy",
                        "header": "Strategy",
                        "question": "Which strategy?",
                        "options": [
                          {"label": "Safe", "description": "Prefer compatibility"},
                          {"label": "Other", "isOther": true}
                        ]
                      }]
                    }
                    """,
                ),
            ),
        )

        requireNotNull(pending)
        val question = pending.approval.questions.single()
        assertEquals("strategy", question.id)
        assertEquals(true, question.allowsOther)
        assertEquals(AgentApprovalType.USER_INPUT, pending.approval.type)

        val response = CodexApprovalMapper.response(
            pending,
            AgentApprovalDecision.SUBMIT,
            mapOf("strategy" to listOf("Safe")),
        ).jsonObject
        assertIs<kotlinx.serialization.json.JsonObject>(response["answers"])
        assertTrue(response.toString().contains("Safe"))
    }

    private fun objectFrom(value: String) = json.parseToJsonElement(value).jsonObject
}
