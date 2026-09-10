/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.opencode

import dev.agentrelay.provider.api.AgentApproval
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentApprovalId
import dev.agentrelay.provider.api.AgentApprovalType
import dev.agentrelay.provider.api.AgentEvent
import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentQuestion
import dev.agentrelay.provider.api.AgentQuestionOption
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.AgentToolStatus
import kotlinx.serialization.json.JsonObject

internal object OpenCodeEventMapper {
    fun map(
        event: JsonObject,
        dialect: OpenCodeProtocolDialect = OpenCodeProtocolDialect.OPENCODE,
        providerName: String = "OpenCode",
    ): List<AgentEvent> {
        val type = event.string("type") ?: return emptyList()
        val properties = event.objectValue("properties") ?: return emptyList()
        return when (type) {
            "message.part.updated" -> partUpdated(properties)
            "message.part.delta" -> partDelta(properties)
            "session.status" -> sessionStatus(properties)
            "session.idle" -> properties.string("sessionID")?.let {
                listOf(AgentEvent.SessionStateChanged(AgentSessionId(it), AgentSessionState.IDLE))
            }.orEmpty()
            "permission.updated", "permission.asked" -> permissionUpdated(properties)
            "question.asked" -> questionAsked(properties)
            "session.diff" -> if (dialect == OpenCodeProtocolDialect.OPENCODE) {
                sessionDiff(properties)
            } else {
                emptyList()
            }
            "session.error" -> sessionError(properties, providerName)
            else -> emptyList()
        }
    }

    private fun partUpdated(properties: JsonObject): List<AgentEvent> {
        val part = properties.objectValue("part") ?: return emptyList()
        val sessionId = (part.string("sessionID") ?: properties.string("sessionID"))
            ?.let(::AgentSessionId)
            ?: return emptyList()
        return when (part.string("type")) {
            "text", "reasoning" -> {
                val delta = properties.string("delta").orEmpty()
                if (delta.isNotEmpty()) {
                    listOf(
                        AgentEvent.TextDelta(
                            sessionId = sessionId,
                            turnId = null,
                            itemId = part.string("id"),
                            channel = if (part.string("type") == "reasoning") {
                                AgentMessageChannel.REASONING_SUMMARY
                            } else {
                                AgentMessageChannel.COMMENTARY
                            },
                            text = delta,
                        ),
                    )
                } else if (part.objectValue("time")?.long("end") != null) {
                    part.string("text")?.takeIf(String::isNotBlank)?.let { text ->
                        listOf(
                            AgentEvent.MessageCompleted(
                                sessionId = sessionId,
                                turnId = null,
                                itemId = part.string("id"),
                                channel = AgentMessageChannel.FINAL,
                                text = text,
                            ),
                        )
                    }.orEmpty()
                } else {
                    emptyList()
                }
            }
            "tool" -> listOf(
                AgentEvent.ToolChanged(
                    sessionId = sessionId,
                    turnId = null,
                    itemId = part.string("id") ?: part.string("tool").orEmpty(),
                    toolName = part.string("tool") ?: "Tool",
                    summary = part.objectValue("state")?.string("title")
                        ?: part.objectValue("state")?.objectValue("input")?.string("command"),
                    status = part.objectValue("state")?.string("status").toToolStatus(),
                ),
            )
            else -> emptyList()
        }
    }

    private fun partDelta(properties: JsonObject): List<AgentEvent> {
        val sessionId = properties.string("sessionID")?.let(::AgentSessionId) ?: return emptyList()
        val delta = properties.string("delta")?.takeIf(String::isNotEmpty) ?: return emptyList()
        return listOf(
            AgentEvent.TextDelta(
                sessionId = sessionId,
                turnId = null,
                itemId = properties.string("partID"),
                channel = if (properties.string("field") == "reasoning") {
                    AgentMessageChannel.REASONING_SUMMARY
                } else {
                    AgentMessageChannel.COMMENTARY
                },
                text = delta,
            ),
        )
    }

    private fun sessionStatus(properties: JsonObject): List<AgentEvent> {
        val sessionId = properties.string("sessionID")?.let(::AgentSessionId) ?: return emptyList()
        val status = properties.objectValue("status")?.string("type")
        return listOf(AgentEvent.SessionStateChanged(sessionId, OpenCodeSessionMapper.parseState(status)))
    }

    private fun permissionUpdated(properties: JsonObject): List<AgentEvent> {
        val permission = properties.objectValue("permission") ?: properties
        val id = permission.string("id") ?: return emptyList()
        val sessionId = permission.string("sessionID")?.let(::AgentSessionId) ?: return emptyList()
        val permissionName = permission.string("type") ?: permission.string("permission")
        val metadata = permission.objectValue("metadata")
        val input = metadata?.objectValue("input")
        val approval = AgentApproval(
            id = AgentApprovalId(id),
            sessionId = sessionId,
            turnId = null,
            type = when (permissionName) {
                "bash" -> AgentApprovalType.COMMAND
                "edit", "write" -> AgentApprovalType.FILE_CHANGE
                else -> AgentApprovalType.PERMISSION
            },
            title = permission.string("title") ?: "Allow " + (permissionName ?: "operation"),
            description = permission.string("description")
                ?: permission.arrayValue("patterns")?.joinToString { it.toString().trim('"') },
            command = metadata?.string("command") ?: input?.string("command"),
            workingDirectory = metadata?.string("cwd") ?: input?.string("cwd"),
            availableDecisions = setOf(
                AgentApprovalDecision.APPROVE_ONCE,
                AgentApprovalDecision.APPROVE_FOR_SESSION,
                AgentApprovalDecision.DECLINE,
            ),
        )
        return listOf(AgentEvent.ApprovalRequested(sessionId, approval))
    }

    private fun questionAsked(properties: JsonObject): List<AgentEvent> {
        val request = properties.objectValue("question") ?: properties
        val id = request.string("id") ?: return emptyList()
        val sessionId = request.string("sessionID")?.let(::AgentSessionId) ?: return emptyList()
        val questions = request.arrayValue("questions").orEmpty().mapIndexedNotNull { index, element ->
            val question = element.objectOrNull() ?: return@mapIndexedNotNull null
            val prompt = question.string("question") ?: return@mapIndexedNotNull null
            AgentQuestion(
                id = "$id:$index",
                header = question.string("header"),
                prompt = prompt,
                options = question.arrayValue("options").orEmpty().mapNotNull { optionElement ->
                    val option = optionElement.objectOrNull() ?: return@mapNotNull null
                    val label = option.string("label") ?: return@mapNotNull null
                    AgentQuestionOption(label, option.string("description"))
                },
                allowsOther = question.string("custom")?.toBooleanStrictOrNull() ?: true,
                allowsMultiple = question.string("multiple")?.toBooleanStrictOrNull() ?: false,
            )
        }
        if (questions.isEmpty()) {
            return emptyList()
        }
        val approval = AgentApproval(
            id = AgentApprovalId(id),
            sessionId = sessionId,
            turnId = null,
            type = AgentApprovalType.USER_INPUT,
            title = questions.first().header ?: "Input required",
            description = questions.joinToString("\n") { it.prompt },
            questions = questions,
            availableDecisions = setOf(
                AgentApprovalDecision.SUBMIT,
                AgentApprovalDecision.DECLINE,
                AgentApprovalDecision.CANCEL,
            ),
        )
        return listOf(AgentEvent.ApprovalRequested(sessionId, approval))
    }

    private fun sessionDiff(properties: JsonObject): List<AgentEvent> {
        val sessionId = properties.string("sessionID")?.let(::AgentSessionId) ?: return emptyList()
        val diff = properties.arrayValue("diff")
            ?: kotlinx.serialization.json.JsonArray(emptyList())
        return OpenCodeDiffMapper.fromJson(diff).map {
            AgentEvent.FileChanged(sessionId, it)
        }
    }

    private fun sessionError(properties: JsonObject, providerName: String): List<AgentEvent> {
        val sessionId = properties.string("sessionID")?.let(::AgentSessionId) ?: return emptyList()
        val error = properties.objectValue("error")
        val message = error?.string("message") ?: error?.string("name") ?: "$providerName session failed"
        return listOf(
            AgentEvent.Error(sessionId, message, recoverable = true),
            AgentEvent.SessionStateChanged(sessionId, AgentSessionState.FAILED),
        )
    }
}

private fun String?.toToolStatus(): AgentToolStatus = when (this) {
    "completed" -> AgentToolStatus.COMPLETED
    "error", "failed" -> AgentToolStatus.FAILED
    "declined" -> AgentToolStatus.DECLINED
    else -> AgentToolStatus.STARTED
}
