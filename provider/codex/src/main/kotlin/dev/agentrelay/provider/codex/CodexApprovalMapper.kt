/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.codex

import dev.agentrelay.provider.api.AgentApproval
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentApprovalId
import dev.agentrelay.provider.api.AgentApprovalType
import dev.agentrelay.provider.api.AgentQuestion
import dev.agentrelay.provider.api.AgentQuestionOption
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentTurnId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class PendingCodexApproval(
    val rpcId: JsonElement,
    val method: String,
    val rawParams: JsonObject,
    val approval: AgentApproval,
)

internal object CodexApprovalMapper {
    fun fromCall(call: JsonRpcCall): PendingCodexApproval? {
        val rpcId = call.id ?: return null
        val params = call.params.objectOrNull() ?: return null
        val sessionId = params.string("threadId")?.let(::AgentSessionId) ?: return null
        val turnId = params.string("turnId")?.let(::AgentTurnId)
        val requestId = params.string("requestId")
            ?: params.string("itemId")
            ?: rpcId.asString()
            ?: return null

        val approval = when (call.method) {
            "item/commandExecution/requestApproval" -> commandApproval(
                requestId,
                sessionId,
                turnId,
                params,
            )
            "item/fileChange/requestApproval" -> fileApproval(
                requestId,
                sessionId,
                turnId,
                params,
            )
            "item/tool/requestUserInput" -> userInputApproval(
                requestId,
                sessionId,
                turnId,
                params,
            )
            "item/permissions/requestApproval" -> permissionApproval(
                requestId,
                sessionId,
                turnId,
                params,
            )
            else -> return null
        }
        return PendingCodexApproval(rpcId, call.method, params, approval)
    }

    fun response(
        pending: PendingCodexApproval,
        decision: AgentApprovalDecision,
        answers: Map<String, List<String>>,
    ): JsonElement = when (pending.method) {
        "item/tool/requestUserInput" -> buildJsonObject {
            put(
                "answers",
                buildJsonObject {
                    answers.forEach { (questionId, values) ->
                        put(
                            questionId,
                            buildJsonObject {
                                put("answers", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
                            },
                        )
                    }
                },
            )
        }
        "item/permissions/requestApproval" -> permissionResponse(pending, decision)
        else -> buildJsonObject { put("decision", decision.toCodexDecision()) }
    }

    private fun commandApproval(id: String, sessionId: AgentSessionId, turnId: AgentTurnId?, params: JsonObject) =
        AgentApproval(
            id = AgentApprovalId(id),
            sessionId = sessionId,
            turnId = turnId,
            type = AgentApprovalType.COMMAND,
            title = params.objectValue("networkApprovalContext")?.let { "Allow network access" }
                ?: "Run remote command",
            description = params.string("reason")
                ?: params.objectValue("networkApprovalContext")?.networkDescription(),
            command = params.commandText(),
            workingDirectory = params.string("cwd"),
            availableDecisions = params.availableDecisions(),
        )

    private fun fileApproval(id: String, sessionId: AgentSessionId, turnId: AgentTurnId?, params: JsonObject) =
        AgentApproval(
            id = AgentApprovalId(id),
            sessionId = sessionId,
            turnId = turnId,
            type = AgentApprovalType.FILE_CHANGE,
            title = "Apply remote file changes",
            description = params.string("reason"),
            workingDirectory = params.string("grantRoot"),
            availableDecisions = params.availableDecisions(),
        )

    private fun permissionApproval(id: String, sessionId: AgentSessionId, turnId: AgentTurnId?, params: JsonObject) =
        AgentApproval(
            id = AgentApprovalId(id),
            sessionId = sessionId,
            turnId = turnId,
            type = AgentApprovalType.PERMISSION,
            title = "Grant additional permissions",
            description = params.string("reason"),
            workingDirectory = params.string("cwd"),
            availableDecisions = setOf(
                AgentApprovalDecision.APPROVE_ONCE,
                AgentApprovalDecision.APPROVE_FOR_SESSION,
                AgentApprovalDecision.DECLINE,
            ),
        )

    private fun userInputApproval(id: String, sessionId: AgentSessionId, turnId: AgentTurnId?, params: JsonObject) =
        AgentApproval(
            id = AgentApprovalId(id),
            sessionId = sessionId,
            turnId = turnId,
            type = AgentApprovalType.USER_INPUT,
            title = "Agent needs input",
            description = null,
            questions = params.arrayValue("questions").orEmpty().mapNotNull { element ->
                val question = element.objectOrNull() ?: return@mapNotNull null
                val questionId = question.string("id") ?: return@mapNotNull null
                val options = question.arrayValue("options").orEmpty().mapNotNull { optionElement ->
                    val option = optionElement.objectOrNull() ?: return@mapNotNull null
                    option.string("label")?.let { AgentQuestionOption(it, option.string("description")) }
                }
                AgentQuestion(
                    id = questionId,
                    header = question.string("header"),
                    prompt = question.string("question").orEmpty(),
                    options = options,
                    allowsOther = question.arrayValue("options").orEmpty().any {
                        it.objectOrNull()?.boolean("isOther") == true
                    } ||
                        options.isEmpty(),
                    allowsMultiple = question.boolean("isMultiSelect") == true,
                )
            },
            availableDecisions = setOf(AgentApprovalDecision.SUBMIT, AgentApprovalDecision.CANCEL),
        )

    private fun permissionResponse(pending: PendingCodexApproval, decision: AgentApprovalDecision): JsonObject =
        buildJsonObject {
            val requested = pending.rawParams["permissions"]
                ?: pending.rawParams["requestedPermissions"]
                ?: buildJsonObject {}
            put(
                "permissions",
                if (decision == AgentApprovalDecision.APPROVE_ONCE ||
                    decision == AgentApprovalDecision.APPROVE_FOR_SESSION
                ) {
                    requested
                } else {
                    buildJsonObject {}
                },
            )
            if (decision == AgentApprovalDecision.APPROVE_FOR_SESSION) {
                put("scope", "session")
            }
        }

    private fun JsonObject.availableDecisions(): Set<AgentApprovalDecision> {
        val wireDecisions = arrayValue("availableDecisions")
            ?.mapNotNull(JsonElement::asString)
            ?.toSet()
            .orEmpty()
        if (wireDecisions.isEmpty()) {
            return setOf(
                AgentApprovalDecision.APPROVE_ONCE,
                AgentApprovalDecision.APPROVE_FOR_SESSION,
                AgentApprovalDecision.DECLINE,
                AgentApprovalDecision.CANCEL,
            )
        }
        return wireDecisions.mapNotNullTo(linkedSetOf()) {
            when (it) {
                "accept" -> AgentApprovalDecision.APPROVE_ONCE
                "acceptForSession" -> AgentApprovalDecision.APPROVE_FOR_SESSION
                "decline" -> AgentApprovalDecision.DECLINE
                "cancel" -> AgentApprovalDecision.CANCEL
                else -> null
            }
        }
    }

    private fun JsonObject.commandText(): String? {
        string("command")?.let { return it }
        return (get("command") as? JsonArray)
            ?.mapNotNull(JsonElement::asString)
            ?.joinToString(" ")
    }

    private fun JsonObject.networkDescription(): String? {
        val host = string("host") ?: return null
        return listOfNotNull(string("protocol"), host, string("port"))
            .joinToString(" ")
    }

    private fun AgentApprovalDecision.toCodexDecision(): String = when (this) {
        AgentApprovalDecision.APPROVE_ONCE, AgentApprovalDecision.SUBMIT -> "accept"
        AgentApprovalDecision.APPROVE_FOR_SESSION -> "acceptForSession"
        AgentApprovalDecision.DECLINE -> "decline"
        AgentApprovalDecision.CANCEL -> "cancel"
    }
}
