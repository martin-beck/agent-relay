/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.api

enum class AttentionState { OPEN, SNOOZED, ESCALATED, RESOLVED, INVALIDATED, EXPIRED }
enum class AttentionUrgency { LOW, NORMAL, HIGH, CRITICAL }
enum class AttentionRisk { NONE, REVERSIBLE, IRREVERSIBLE }
enum class AttentionDecision { APPROVE, REJECT, ACKNOWLEDGE, RETRY }
enum class AttentionSafeDefault { DISMISS, REJECT, HOLD }

data class AttentionItem(
    val id: String,
    val deduplicationKey: String,
    val projectId: String,
    val workflowId: String,
    val reason: String,
    val evidenceId: String?,
    val risk: AttentionRisk,
    val requestedDecision: AttentionDecision,
    val authority: String,
    val safeDefault: AttentionSafeDefault,
    val urgency: AttentionUrgency,
    val state: AttentionState = AttentionState.OPEN,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long? = null,
    val snoozedUntilEpochMillis: Long? = null,
    val resolvedAtEpochMillis: Long? = null,
) {
    init {
        listOf(
            id to "Attention id",
            deduplicationKey to "Deduplication key",
            projectId to "Project id",
            workflowId to "Workflow id",
            reason to "Attention reason",
            authority to "Attention authority",
        )
            .forEach { (value, label) -> requireAttentionText(value, label) }
        evidenceId?.let { requireAttentionText(it, "Evidence id") }
        require(createdAtEpochMillis >= 0)
        require(expiresAtEpochMillis == null || expiresAtEpochMillis >= createdAtEpochMillis)
        require(snoozedUntilEpochMillis == null || snoozedUntilEpochMillis >= createdAtEpochMillis)
        require(resolvedAtEpochMillis == null || resolvedAtEpochMillis >= createdAtEpochMillis)
        require(state != AttentionState.RESOLVED || resolvedAtEpochMillis != null)
        require(state == AttentionState.SNOOZED || snoozedUntilEpochMillis == null)
    }

    fun isExpired(nowEpochMillis: Long): Boolean =
        expiresAtEpochMillis != null && nowEpochMillis >= expiresAtEpochMillis && state in ACTIVE_STATES

    fun transition(to: AttentionState, nowEpochMillis: Long): AttentionItem {
        require(nowEpochMillis >= createdAtEpochMillis)
        require(AttentionTransitions.allows(state, to)) { "Invalid attention transition" }
        return copy(
            state = to,
            snoozedUntilEpochMillis = if (to == AttentionState.SNOOZED) snoozedUntilEpochMillis else null,
            resolvedAtEpochMillis = if (to == AttentionState.RESOLVED) nowEpochMillis else resolvedAtEpochMillis,
        )
    }

    val notificationKey: String get() = deduplicationKey
}

object AttentionTransitions {
    fun allows(from: AttentionState, to: AttentionState): Boolean = when (from) {
        AttentionState.OPEN -> to in setOf(
            AttentionState.SNOOZED,
            AttentionState.ESCALATED,
            AttentionState.RESOLVED,
            AttentionState.INVALIDATED,
            AttentionState.EXPIRED,
        )
        AttentionState.SNOOZED -> to in setOf(
            AttentionState.OPEN,
            AttentionState.ESCALATED,
            AttentionState.EXPIRED,
            AttentionState.INVALIDATED,
        )
        AttentionState.ESCALATED -> to in setOf(AttentionState.RESOLVED, AttentionState.INVALIDATED, AttentionState.EXPIRED)
        AttentionState.RESOLVED, AttentionState.INVALIDATED, AttentionState.EXPIRED -> false
    }
}

data class AttentionPolicyDecision(val state: AttentionState, val notify: Boolean, val safeDefault: AttentionSafeDefault)

fun AttentionItem.policyAt(nowEpochMillis: Long): AttentionPolicyDecision {
    if (isExpired(nowEpochMillis)) return AttentionPolicyDecision(AttentionState.EXPIRED, false, safeDefault)
    return AttentionPolicyDecision(state, state == AttentionState.OPEN || state == AttentionState.ESCALATED, safeDefault)
}

private val ACTIVE_STATES = setOf(AttentionState.OPEN, AttentionState.SNOOZED, AttentionState.ESCALATED)

private fun requireAttentionText(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_ATTENTION_TEXT_CHARS) { "$label is invalid" }
}

private const val MAX_ATTENTION_TEXT_CHARS = 512
