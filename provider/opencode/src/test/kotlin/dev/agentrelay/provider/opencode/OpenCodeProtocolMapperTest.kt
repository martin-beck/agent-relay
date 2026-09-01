package dev.agentrelay.provider.opencode

import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentEvent
import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.AgentToolStatus
import dev.agentrelay.provider.api.AgentTranscriptRole
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test

class OpenCodeProtocolMapperTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun sessionMapsStableFieldsAndBusyStatus() {
        val session = OpenCodeSessionMapper.fromJson(
            objectFrom(
                """
                {
                  "id":"ses-1",
                  "title":"Fix parser",
                  "directory":"/workspace",
                  "model":{"providerID":"ollama","id":"example-model"},
                  "time":{"created":1700000000000,"updated":1700000005000},
                  "version":"1.18.23",
                  "summary":{"files":2,"additions":4,"deletions":1}
                }
                """.trimIndent(),
            ),
            status = "busy",
        )

        assertEquals("ses-1", session.id.value)
        assertEquals("/workspace", session.workingDirectory)
        assertEquals("example-provider/example-model", session.model)
        assertEquals(1_700_000_005, session.updatedAtEpochSeconds)
        assertEquals(AgentSessionState.RUNNING, session.state)
        assertEquals(false, session.canAcceptInput)
        assertEquals("2", session.metadata["summary.files"])
    }

    @Test
    fun transcriptUsesStructuredMessageParts() {
        val entries = OpenCodeTranscriptMapper.fromJson(
            AgentSessionId("ses-1"),
            arrayFrom(
                """
                [
                  {
                    "info":{"id":"msg-u","sessionID":"ses-1","role":"user","time":{"created":1700000000000}},
                    "parts":[{"id":"part-u","sessionID":"ses-1","messageID":"msg-u","type":"text","text":"Run tests"}]
                  },
                  {
                    "info":{"id":"msg-a","sessionID":"ses-1","role":"assistant","time":{"created":1700000001000}},
                    "parts":[
                      {"id":"part-a","sessionID":"ses-1","messageID":"msg-a","type":"text","text":"Running"},
                      {
                        "id":"part-t",
                        "sessionID":"ses-1",
                        "messageID":"msg-a",
                        "type":"tool",
                        "tool":"bash",
                        "state":{"status":"completed","title":"Tests","output":"BUILD SUCCESSFUL"}
                      }
                    ]
                  }
                ]
                """.trimIndent(),
            ),
        )

        assertEquals(3, entries.size)
        assertEquals(AgentTranscriptRole.USER, entries[0].role)
        assertEquals(AgentTranscriptRole.AGENT, entries[1].role)
        assertEquals(AgentMessageChannel.FINAL, entries[1].channel)
        assertEquals(AgentTranscriptRole.TOOL, entries[2].role)
        assertTrue(entries[2].text.contains("BUILD SUCCESSFUL"))
    }

    @Test
    fun eventsMapDeltasToolsStatePermissionsAndDiffs() {
        val delta = OpenCodeEventMapper.map(
            objectFrom(
                """
                {
                  "type":"message.part.updated",
                  "properties":{
                    "part":{"id":"part-1","sessionID":"ses-1","messageID":"msg-1","type":"text","text":"Working"},
                    "delta":"ing"
                  }
                }
                """.trimIndent(),
            ),
        )
        assertEquals("ing", assertIs<AgentEvent.TextDelta>(delta.single()).text)

        val tool = OpenCodeEventMapper.map(
            objectFrom(
                """
                {
                  "type":"message.part.updated",
                  "properties":{
                    "part":{
                      "id":"tool-1",
                      "sessionID":"ses-1",
                      "messageID":"msg-1",
                      "type":"tool",
                      "tool":"bash",
                      "state":{"status":"running","title":"Build","input":{"command":"./gradlew test"}}
                    }
                  }
                }
                """.trimIndent(),
            ),
        )
        assertEquals(AgentToolStatus.STARTED, assertIs<AgentEvent.ToolChanged>(tool.single()).status)

        val state = OpenCodeEventMapper.map(
            objectFrom(
                """
                {"type":"session.status","properties":{"sessionID":"ses-1","status":{"type":"busy"}}}
                """.trimIndent(),
            ),
        )
        assertEquals(
            AgentSessionState.RUNNING,
            assertIs<AgentEvent.SessionStateChanged>(state.single()).state,
        )

        val approval = OpenCodeEventMapper.map(
            objectFrom(
                """
                {
                  "type":"permission.updated",
                  "properties":{
                    "id":"permission-1",
                    "type":"bash",
                    "sessionID":"ses-1",
                    "messageID":"msg-1",
                    "title":"Run tests",
                    "metadata":{"command":"./gradlew test"},
                    "time":{"created":1700000000000}
                  }
                }
                """.trimIndent(),
            ),
        )
        val requested = assertIs<AgentEvent.ApprovalRequested>(approval.single()).approval
        assertEquals("./gradlew test", requested.command)
        assertEquals(
            setOf(
                AgentApprovalDecision.APPROVE_ONCE,
                AgentApprovalDecision.APPROVE_FOR_SESSION,
                AgentApprovalDecision.DECLINE,
            ),
            requested.availableDecisions,
        )

        val files = OpenCodeEventMapper.map(
            objectFrom(
                """
                {
                  "type":"session.diff",
                  "properties":{
                    "sessionID":"ses-1",
                    "diff":[
                      {"file":"src/New.kt","before":"","after":"class New","additions":1,"deletions":0},
                      {"file":"src/Old.kt","before":"class Old","after":"","additions":0,"deletions":1}
                    ]
                  }
                }
                """.trimIndent(),
            ),
        )
        assertEquals(
            listOf(AgentFileChangeKind.ADDED, AgentFileChangeKind.DELETED),
            files.map { assertIs<AgentEvent.FileChanged>(it).file.kind },
        )
    }

    private fun objectFrom(value: String) = json.parseToJsonElement(value).jsonObject

    private fun arrayFrom(value: String) = json.parseToJsonElement(value).jsonArray
}
