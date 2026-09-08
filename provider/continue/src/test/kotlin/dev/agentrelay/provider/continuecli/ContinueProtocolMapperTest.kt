/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.continuecli

import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.AgentToolStatus
import dev.agentrelay.provider.api.AgentTranscriptRole
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class ContinueProtocolMapperTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun listingAndStateMapStableSessionFields() {
        val listed = ContinueSessionMapper.fromListing(
            objectFrom(
                """
                {
                  "id":"session-1",
                  "timestamp":"2026-08-31T23:32:59.441Z",
                  "workspaceDirectory":"/workspace",
                  "title":"Fix parser",
                  "firstUserMessage":"Investigate failure",
                  "isRemote":false
                }
                """.trimIndent(),
            ),
        )
        assertEquals("session-1", listed.id.value)
        assertEquals(1_788_219_179, listed.createdAtEpochSeconds)
        assertEquals(AgentSessionState.IDLE, listed.state)
        assertEquals("Investigate failure", listed.preview)

        val state = ContinueSessionMapper.fromState(
            objectFrom(
                """
                {
                  "session":{
                    "sessionId":"session-1",
                    "title":"Fix parser",
                    "workspaceDirectory":"/workspace",
                    "history":[
                      {"message":{"role":"assistant","content":"Working","usage":{"model":"example-model"}}}
                    ],
                    "usage":{"totalCost":0.2,"promptTokens":42,"completionTokens":3}
                  },
                  "isProcessing":true,
                  "messageQueueLength":1,
                  "pendingPermission":null
                }
                """.trimIndent(),
            ),
            previous = listed,
        )
        assertEquals(AgentSessionState.RUNNING, state.state)
        assertEquals("example-model", state.model)
        assertEquals("42", state.metadata["usage.promptTokens"])
    }

    @Test
    fun historyPermissionsAndDiffsUseStructuredState() {
        val sessionId = AgentSessionId("session-1")
        val state = objectFrom(
            """
            {
              "session":{
                "sessionId":"session-1",
                "history":[
                  {"message":{"role":"user","content":"Run tests"}},
                  {
                    "message":{
                      "role":"assistant",
                      "content":"Running",
                      "tool_calls":[{"id":"call-1","type":"function","function":{"name":"runTerminalCommand","arguments":"{\"command\":\"./gradlew test\"}"}}]
                    }
                  },
                  {"message":{"role":"tool","content":"BUILD SUCCESSFUL","tool_call_id":"call-1"}}
                ]
              },
              "isProcessing":false,
              "messageQueueLength":0,
              "pendingPermission":{
                "toolName":"runTerminalCommand",
                "toolArgs":{"command":"./gradlew test","cwd":"/workspace"},
                "requestId":"permission-1",
                "timestamp":1700000000000
              }
            }
            """.trimIndent(),
        )

        val transcript = ContinueTranscriptMapper.fromState(sessionId, state)
        assertEquals(
            listOf(
                AgentTranscriptRole.USER,
                AgentTranscriptRole.AGENT,
                AgentTranscriptRole.TOOL,
                AgentTranscriptRole.TOOL,
            ),
            transcript.map { it.role },
        )
        assertTrue(transcript.last().text.contains("BUILD SUCCESSFUL"))
        assertEquals(
            AgentToolStatus.STARTED.name,
            transcript[2].metadata["continue.toolStatus"],
        )

        val approval = assertNotNull(ContinueApprovalMapper.fromState(state))
        assertEquals("./gradlew test", approval.command)
        assertEquals(
            setOf(AgentApprovalDecision.APPROVE_ONCE, AgentApprovalDecision.DECLINE),
            approval.availableDecisions,
        )

        val files = ContinueDiffMapper.fromPatch(
            """
            diff --git a/src/New.kt b/src/New.kt
            new file mode 100644
            --- /dev/null
            +++ b/src/New.kt
            diff --git a/src/Old.kt b/src/Old.kt
            deleted file mode 100644
            --- a/src/Old.kt
            +++ /dev/null
            diff --git a/src/Renamed.kt b/src/Current.kt
            similarity index 98%
            rename from src/Renamed.kt
            rename to src/Current.kt
            """.trimIndent(),
            "/workspace",
        )
        assertEquals(
            listOf(
                AgentFileChangeKind.ADDED,
                AgentFileChangeKind.DELETED,
                AgentFileChangeKind.RENAMED,
            ),
            files.map { it.kind },
        )
        assertEquals("/workspace/src/Current.kt", files.last().remotePath)
        assertEquals("/workspace/src/Renamed.kt", files.last().oldRemotePath)
    }

    @Test
    fun diffsDecodeQuotedPathsAndRejectWorkspaceTraversal() {
        val files = ContinueDiffMapper.fromPatch(
            """
            diff --git "a/src/With Space.kt" "b/src/With Space.kt"
            --- "a/src/With Space.kt"
            +++ "b/src/With Space.kt"
            diff --git a/../outside.txt b/../outside.txt
            --- a/../outside.txt
            +++ b/../outside.txt
            """.trimIndent(),
            "/workspace",
        )

        assertEquals(
            listOf(
                dev.agentrelay.provider.api.AgentChangedFile(
                    remotePath = "/workspace/src/With Space.kt",
                    kind = AgentFileChangeKind.MODIFIED,
                ),
            ),
            files,
        )
    }

    private fun objectFrom(value: String) = json.parseToJsonElement(value).jsonObject
}
