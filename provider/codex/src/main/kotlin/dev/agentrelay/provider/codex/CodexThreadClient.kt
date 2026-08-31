package dev.agentrelay.provider.codex

import dev.agentrelay.provider.api.AgentSession
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentTranscriptEntry
import dev.agentrelay.provider.api.AgentTurnId
import dev.agentrelay.provider.api.StartSessionOptions
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class CodexThreadClient(private val peer: JsonRpcClient) {
    suspend fun listSessions(): List<AgentSession> {
        val threads = mutableListOf<JsonObject>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val result = peer.request(
                "thread/list",
                buildJsonObject {
                    put("limit", PAGE_SIZE)
                    put("sortKey", "updated_at")
                    put("sortDirection", "desc")
                    put(
                        "sourceKinds",
                        buildJsonArray {
                            SOURCE_KINDS.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                        },
                    )
                    cursor?.let { put("cursor", it) }
                },
            ).objectOrNull() ?: error("thread/list returned a non-object result")
            result.arrayValue("data").orEmpty()
                .mapNotNullTo(threads) { it.objectOrNull() }
            cursor = result.string("nextCursor")
        } while (cursor != null && seenCursors.add(cursor))

        return threads
            .map(CodexSessionMapper::fromThread)
            .distinctBy(AgentSession::id)
            .sortedByDescending { it.updatedAtEpochSeconds ?: it.createdAtEpochSeconds ?: 0 }
    }

    suspend fun resume(sessionId: AgentSessionId): JsonObject = peer.request(
        "thread/resume",
        buildJsonObject { put("threadId", sessionId.value) },
    ).threadResult("thread/resume")

    suspend fun start(options: StartSessionOptions): JsonObject = peer.request(
        "thread/start",
        buildJsonObject {
            options.workingDirectory?.let { put("cwd", it) }
            options.model?.let { put("model", it) }
            put("approvalPolicy", "on-request")
            put("sandbox", "workspaceWrite")
            put("serviceName", "agent_relay")
        },
    ).threadResult("thread/start")

    suspend fun transcript(sessionId: AgentSessionId): List<AgentTranscriptEntry> {
        val thread = runCatching {
            peer.request(
                "thread/read",
                buildJsonObject {
                    put("threadId", sessionId.value)
                    put("includeTurns", true)
                },
            ).threadResult("thread/read")
        }.getOrElse {
            readPaginatedThread(sessionId)
        }
        return CodexTranscriptMapper.fromThread(thread)
    }

    suspend fun startTurn(sessionId: AgentSessionId, text: String): AgentTurnId {
        require(text.isNotBlank()) { "Input must not be blank" }
        val result = peer.request(
            "turn/start",
            buildJsonObject {
                put("threadId", sessionId.value)
                put(
                    "input",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", text)
                            },
                        )
                    },
                )
            },
        ).objectOrNull() ?: error("turn/start returned a non-object result")
        val turn = result.objectValue("turn") ?: error("turn/start returned no turn")
        return AgentTurnId(requireNotNull(turn.string("id")) { "Codex turn is missing its id" })
    }

    suspend fun steer(sessionId: AgentSessionId, turnId: AgentTurnId, text: String) {
        require(text.isNotBlank()) { "Input must not be blank" }
        peer.request(
            "turn/steer",
            buildJsonObject {
                put("threadId", sessionId.value)
                put("expectedTurnId", turnId.value)
                put(
                    "input",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", text)
                            },
                        )
                    },
                )
            },
        )
    }

    suspend fun interrupt(sessionId: AgentSessionId, turnId: AgentTurnId) {
        peer.request(
            "turn/interrupt",
            buildJsonObject {
                put("threadId", sessionId.value)
                put("turnId", turnId.value)
            },
        )
    }

    private suspend fun readPaginatedThread(sessionId: AgentSessionId): JsonObject {
        val turns = mutableListOf<kotlinx.serialization.json.JsonElement>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val result = peer.request(
                "thread/turns/list",
                buildJsonObject {
                    put("threadId", sessionId.value)
                    put("limit", PAGE_SIZE)
                    put("sortDirection", "asc")
                    put("itemsView", "full")
                    cursor?.let { put("cursor", it) }
                },
            ).objectOrNull() ?: error("thread/turns/list returned a non-object result")
            turns += result.arrayValue("data").orEmpty()
            cursor = result.string("nextCursor")
        } while (cursor != null && seenCursors.add(cursor))

        return buildJsonObject {
            put("id", sessionId.value)
            put("turns", buildJsonArray { turns.forEach(::add) })
        }
    }

    private fun kotlinx.serialization.json.JsonElement.threadResult(method: String): JsonObject {
        val result = objectOrNull() ?: error("$method returned a non-object result")
        return result.objectValue("thread") ?: error("$method returned no thread")
    }

    private companion object {
        const val PAGE_SIZE = 100
        val SOURCE_KINDS = listOf(
            "cli",
            "vscode",
            "exec",
            "appServer",
            "subAgent",
            "subAgentReview",
            "subAgentCompact",
            "subAgentThreadSpawn",
            "subAgentOther",
            "unknown",
        )
    }
}
