/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.codex

import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentTranscriptEntry
import dev.agentrelay.provider.api.AgentTranscriptRole
import dev.agentrelay.provider.api.AgentTurnId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal object CodexTranscriptMapper {
    fun fromThread(thread: JsonObject): List<AgentTranscriptEntry> {
        val sessionId = AgentSessionId(requireNotNull(thread.string("id")))
        return thread.arrayValue("turns").orEmpty().flatMap { turnElement ->
            val turn = turnElement.objectOrNull() ?: return@flatMap emptyList()
            val turnId = turn.string("id")?.let(::AgentTurnId)
            turn.arrayValue("items").orEmpty().mapIndexedNotNull { index, itemElement ->
                itemElement.objectOrNull()?.toEntry(sessionId, turnId, index)
            }
        }
    }

    private fun JsonObject.toEntry(sessionId: AgentSessionId, turnId: AgentTurnId?, index: Int): AgentTranscriptEntry? {
        val type = string("type") ?: return null
        val role = when (type) {
            "userMessage" -> AgentTranscriptRole.USER
            "agentMessage", "plan", "reasoning" -> AgentTranscriptRole.AGENT
            "commandExecution", "fileChange", "mcpToolCall", "webSearch" -> AgentTranscriptRole.TOOL
            else -> AgentTranscriptRole.SYSTEM
        }
        val channel = when (type) {
            "agentMessage" -> AgentMessageChannel.FINAL
            "plan" -> AgentMessageChannel.PLAN
            "reasoning" -> AgentMessageChannel.REASONING_SUMMARY
            "userMessage" -> null
            else -> AgentMessageChannel.SYSTEM
        }
        val text = extractText(type).trim()
        if (text.isEmpty()) {
            return null
        }

        return AgentTranscriptEntry(
            id = string("id") ?: "${turnId?.value ?: "thread"}:$index",
            sessionId = sessionId,
            turnId = turnId,
            role = role,
            channel = channel,
            text = text,
            createdAtEpochSeconds = long("createdAt"),
            metadata = mapOf("codex.itemType" to type),
        )
    }

    private fun JsonObject.extractText(type: String): String {
        string("text")?.let { return it }
        string("message")?.let { return it }

        val contentText = arrayValue("content")
            ?.mapNotNull(JsonElement::textContent)
            ?.joinToString(separator = "")
            .orEmpty()
        if (contentText.isNotBlank()) {
            return contentText
        }

        return when (type) {
            "commandExecution" -> listOfNotNull(
                string("command"),
                string("aggregatedOutput")?.takeIf(String::isNotBlank),
            ).joinToString(separator = "\n\n")
            "fileChange" -> arrayValue("changes").orEmpty()
                .mapNotNull { it.objectOrNull()?.string("path") }
                .joinToString(prefix = "Changed files:\n", separator = "\n")
            "mcpToolCall" -> string("tool") ?: string("name").orEmpty()
            "webSearch" -> string("query").orEmpty()
            else -> ""
        }
    }
}

private fun JsonElement.textContent(): String? = when (this) {
    is JsonPrimitive -> content
    is JsonObject -> string("text")
    else -> null
}
