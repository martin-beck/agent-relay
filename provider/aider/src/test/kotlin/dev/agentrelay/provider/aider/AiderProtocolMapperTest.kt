package dev.agentrelay.provider.aider

import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentTranscriptRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

class AiderProtocolMapperTest {
    @Test
    fun durableStateAndTranscriptMapStableFields() {
        val state = Json.parseToJsonElement(
            """
            {
              "id":"12345678-1234-1234-1234-123456789abc",
              "title":"repo",
              "preview":"fix it",
              "workingDirectory":"/work/repo",
              "model":"ollama_chat/model",
              "createdAt":10,
              "updatedAt":20,
              "stateDirectory":"/state/id",
              "chatHistoryPath":"/state/id/chat-history.md",
              "files":"[\"src/Main.kt\"]",
              "changedFiles":{"/work/repo/src/Main.kt":"modified"}
            }
            """.trimIndent(),
        ).jsonObject
        val session = AiderSessionMapper.fromState(state)
        assertEquals(AIDER_PROVIDER_ID, session.providerId)
        assertEquals("repo", session.title)
        assertEquals("fix it", session.preview)
        assertEquals("/work/repo", session.workingDirectory)
        assertEquals("ollama_chat/model", session.model)
        assertEquals(10, session.createdAtEpochSeconds)
        assertEquals(20, session.updatedAtEpochSeconds)
        assertEquals("/state/id/chat-history.md", session.metadata["aider.chatHistoryPath"])

        val transcript = AiderTranscriptMapper.fromRows(
            session.id,
            Json.parseToJsonElement(
                """
                [
                  {"id":"u1","role":"user","text":"hello"},
                  {"id":"a1","role":"assistant","text":"world"},
                  {"id":"t1","role":"tool","text":"Tokens: 2"}
                ]
                """.trimIndent(),
            ).jsonArray,
        )
        assertEquals(
            listOf(
                AgentTranscriptRole.USER,
                AgentTranscriptRole.AGENT,
                AgentTranscriptRole.TOOL,
            ),
            transcript.map { it.role },
        )
        assertNull(transcript[0].channel)
        assertEquals(AgentMessageChannel.FINAL, transcript[1].channel)
    }

    @Test
    fun changedFilesIgnoreRelativePathsAndMapKinds() {
        val files = AiderFileChangeMapper.fromMetadata(
            """
            {
              "/work/new.kt":"added",
              "/work/old.kt":"deleted",
              "/work/main.kt":"modified",
              "relative.kt":"modified"
            }
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                "/work/new.kt" to AgentFileChangeKind.ADDED,
                "/work/old.kt" to AgentFileChangeKind.DELETED,
                "/work/main.kt" to AgentFileChangeKind.MODIFIED,
            ),
            files.map { it.remotePath to it.kind },
        )
        assertEquals(emptyList(), AiderFileChangeMapper.fromMetadata("not-json"))
    }
}
