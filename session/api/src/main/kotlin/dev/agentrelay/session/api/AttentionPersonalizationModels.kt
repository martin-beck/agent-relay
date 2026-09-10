/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.api

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Reasons whose visibility cannot be reduced by personalization. */
enum class AttentionProtectionReason {
    SECURITY,
    APPROVAL,
    UNCERTAINTY,
    BUDGET_EXHAUSTION,
}

/** An attention item plus its explicit safety classification. */
data class PersonalizableAttentionItem(
    val item: AttentionItem,
    val protectionReasons: Set<AttentionProtectionReason> = emptySet(),
) {
    init {
        require(
            item.requestedDecision != AttentionDecision.APPROVE ||
                AttentionProtectionReason.APPROVAL in protectionReasons,
        ) { "Approval attention must be protected" }
    }

    val isProtected: Boolean get() = protectionReasons.isNotEmpty()
}

/** A daily, start-inclusive and end-exclusive quiet window in an explicit time zone. */
data class AttentionQuietHours(
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
    val zoneId: String,
) {
    init {
        require(startMinuteOfDay in 0 until MINUTES_PER_DAY) {
            "Quiet-hours start is invalid"
        }
        require(endMinuteOfDay in 0 until MINUTES_PER_DAY) {
            "Quiet-hours end is invalid"
        }
        require(startMinuteOfDay != endMinuteOfDay) {
            "Quiet hours cannot cover an ambiguous full day"
        }
        require(zoneId.isNotBlank() && zoneId.length <= MAX_ZONE_ID_CHARS) {
            "Quiet-hours zone is invalid"
        }
        ZoneId.of(zoneId)
    }

    fun isActiveAt(epochMillis: Long): Boolean {
        require(epochMillis >= 0) { "Quiet-hours time is invalid" }
        val minute = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.of(zoneId)).toLocalTime()
            .toSecondOfDay() / SECONDS_PER_MINUTE
        return if (startMinuteOfDay < endMinuteOfDay) {
            minute in startMinuteOfDay until endMinuteOfDay
        } else {
            minute >= startMinuteOfDay || minute < endMinuteOfDay
        }
    }

    fun nextEndAt(epochMillis: Long): Long {
        require(isActiveAt(epochMillis)) { "Quiet hours are not active" }
        val zone = ZoneId.of(zoneId)
        val current = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val minute = current.toLocalTime().toSecondOfDay() / SECONDS_PER_MINUTE
        val endDate = if (startMinuteOfDay > endMinuteOfDay && minute >= startMinuteOfDay) {
            current.toLocalDate().plusDays(1)
        } else {
            current.toLocalDate()
        }
        return endDate.atTime(LocalTime.ofSecondOfDay(endMinuteOfDay * SECONDS_PER_MINUTE.toLong()))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }
}

/** A monotonic age-based urgency promotion. */
data class AttentionEscalationRule(
    val fromUrgency: AttentionUrgency,
    val afterMillis: Long,
    val toUrgency: AttentionUrgency,
) {
    init {
        require(fromUrgency != AttentionUrgency.CRITICAL) {
            "Critical attention cannot be escalated"
        }
        require(afterMillis in 1..MAX_ESCALATION_MILLIS) {
            "Escalation delay is invalid"
        }
        require(toUrgency.ordinal > fromUrgency.ordinal) {
            "Escalation must increase urgency"
        }
    }
}

/** User preferences with safety-critical ordering deliberately excluded from customization. */
data class AttentionPersonalizationPreferences(
    val quietHours: AttentionQuietHours? = null,
    val digestEnabled: Boolean = false,
    val digestIntervalMinutes: Int = DEFAULT_DIGEST_INTERVAL_MINUTES,
    val digestBatchSize: Int = DEFAULT_DIGEST_BATCH_SIZE,
    val nonCriticalUrgencyOrder: List<AttentionUrgency> = DEFAULT_NONCRITICAL_ORDER,
    val escalationRules: List<AttentionEscalationRule> = emptyList(),
) {
    init {
        require(digestIntervalMinutes in 1..MAX_DIGEST_INTERVAL_MINUTES) {
            "Digest interval is invalid"
        }
        require(digestBatchSize in 1..MAX_DIGEST_BATCH_SIZE) {
            "Digest batch size is invalid"
        }
        require(
            nonCriticalUrgencyOrder.size == NONCRITICAL_URGENCIES.size &&
                nonCriticalUrgencyOrder.toSet() == NONCRITICAL_URGENCIES,
        ) { "Noncritical urgency order must contain each supported value once" }
        require(escalationRules.size <= MAX_ESCALATION_RULES) {
            "Too many escalation rules"
        }
        require(escalationRules.map { it.fromUrgency }.distinct().size == escalationRules.size) {
            "Escalation source urgencies must be unique"
        }
    }
}

enum class AttentionPersonalizedDelivery { IMMEDIATE, DIGEST, INACTIVE }

enum class AttentionPersonalizationExplanation {
    INACTIVE_LIFECYCLE,
    PROTECTED_VISIBILITY,
    CRITICAL_VISIBILITY,
    AGE_ESCALATION,
    QUIET_HOURS,
    DIGEST_PREFERENCE,
    IMMEDIATE_PREFERENCE,
}

/** One inspectable transformation. Original input and index make ordering reversible. */
@ConsistentCopyVisibility
data class AttentionPersonalizationDecision internal constructor(
    val originalIndex: Int,
    val source: PersonalizableAttentionItem,
    val effectiveUrgency: AttentionUrgency,
    val delivery: AttentionPersonalizedDelivery,
    val deliverAtEpochMillis: Long?,
    val explanations: List<AttentionPersonalizationExplanation>,
) {
    init {
        require(originalIndex >= 0) { "Original attention index is invalid" }
        require(explanations.isNotEmpty()) { "Personalization needs an explanation" }
        require(
            (delivery == AttentionPersonalizedDelivery.INACTIVE) ==
                (deliverAtEpochMillis == null),
        ) { "Only active attention has a delivery time" }
        require(
            !source.isProtected ||
                delivery == AttentionPersonalizedDelivery.IMMEDIATE ||
                AttentionPersonalizationExplanation.INACTIVE_LIFECYCLE in explanations,
        ) { "Personalization cannot defer protected attention" }
    }
}

@ConsistentCopyVisibility
data class AttentionDigestBatch internal constructor(
    val deliverAtEpochMillis: Long,
    val items: List<AttentionPersonalizationDecision>,
) {
    init {
        require(deliverAtEpochMillis >= 0) { "Digest delivery time is invalid" }
        require(items.isNotEmpty()) { "Digest batch cannot be empty" }
        require(items.all { it.delivery == AttentionPersonalizedDelivery.DIGEST }) {
            "Digest batch contains immediate or inactive attention"
        }
        require(items.all { it.deliverAtEpochMillis == deliverAtEpochMillis }) {
            "Digest batch delivery times differ"
        }
    }
}

/** Deterministic output used by notifications, widgets, and attention screens. */
@ConsistentCopyVisibility
data class AttentionPersonalizationProjection internal constructor(
    val revision: Long,
    val evaluatedAtEpochMillis: Long,
    val decisions: List<AttentionPersonalizationDecision>,
    val immediate: List<AttentionPersonalizationDecision>,
    val digestBatches: List<AttentionDigestBatch>,
) {
    init {
        require(revision >= 0) { "Personalization revision is invalid" }
        require(evaluatedAtEpochMillis >= 0) { "Personalization time is invalid" }
        require(decisions.size <= MAX_PERSONALIZATION_ITEMS) {
            "Personalization item count is too large"
        }
        require(decisions.map { it.originalIndex }.toSet().size == decisions.size) {
            "Original attention indexes must be unique"
        }
        require(decisions.map { it.source.item.id }.toSet().size == decisions.size) {
            "Attention ids must be unique"
        }
        require(decisions.map { it.source.item.notificationKey }.toSet().size == decisions.size) {
            "Attention notification keys must be unique"
        }
        require(immediate.all { it.delivery == AttentionPersonalizedDelivery.IMMEDIATE }) {
            "Immediate projection contains deferred attention"
        }
        require(
            decisions.filter { it.delivery == AttentionPersonalizedDelivery.IMMEDIATE }.toSet() ==
                immediate.toSet(),
        ) { "Immediate projection is incomplete" }
        val digested = digestBatches.flatMap { it.items }
        require(
            decisions.filter { it.delivery == AttentionPersonalizedDelivery.DIGEST }.toSet() ==
                digested.toSet(),
        ) { "Digest projection is incomplete" }
    }

    fun restoreOriginalOrder(): List<PersonalizableAttentionItem> =
        decisions.sortedBy { it.originalIndex }.map { it.source }
}

/** Pure personalization projector. It performs no delivery or persistence side effect. */
object AttentionPersonalizer {
    fun project(
        revision: Long,
        items: List<PersonalizableAttentionItem>,
        preferences: AttentionPersonalizationPreferences,
        nowEpochMillis: Long,
    ): AttentionPersonalizationProjection {
        require(revision >= 0) { "Personalization revision is invalid" }
        require(nowEpochMillis >= 0) { "Personalization time is invalid" }
        require(items.size <= MAX_PERSONALIZATION_ITEMS) {
            "Personalization item count is too large"
        }
        require(items.map { it.item.id }.distinct().size == items.size) {
            "Attention ids must be unique"
        }
        require(items.map { it.item.notificationKey }.distinct().size == items.size) {
            "Attention notification keys must be unique"
        }

        val quietEnd = preferences.quietHours
            ?.takeIf { it.isActiveAt(nowEpochMillis) }
            ?.nextEndAt(nowEpochMillis)
        val nextDigest = nextDigestAt(nowEpochMillis, preferences.digestIntervalMinutes)
        val decisions = items.mapIndexed { index, source ->
            decide(index, source, preferences, nowEpochMillis, quietEnd, nextDigest)
        }
        val ranking = decisionComparator(preferences)
        val immediate = decisions
            .filter { it.delivery == AttentionPersonalizedDelivery.IMMEDIATE }
            .sortedWith(ranking)
        val digestBatches = decisions
            .filter { it.delivery == AttentionPersonalizedDelivery.DIGEST }
            .groupBy { requireNotNull(it.deliverAtEpochMillis) }
            .toSortedMap()
            .flatMap { (deliverAt, scheduled) ->
                scheduled.sortedWith(ranking).chunked(preferences.digestBatchSize).map { batch ->
                    AttentionDigestBatch(deliverAt, batch)
                }
            }
        return AttentionPersonalizationProjection(
            revision,
            nowEpochMillis,
            decisions,
            immediate,
            digestBatches,
        )
    }

    private fun decide(
        index: Int,
        source: PersonalizableAttentionItem,
        preferences: AttentionPersonalizationPreferences,
        nowEpochMillis: Long,
        quietEndEpochMillis: Long?,
        nextDigestEpochMillis: Long,
    ): AttentionPersonalizationDecision {
        val item = source.item
        if (item.createdAtEpochMillis > nowEpochMillis || !item.policyAt(nowEpochMillis).notify) {
            return AttentionPersonalizationDecision(
                index,
                source,
                item.urgency,
                AttentionPersonalizedDelivery.INACTIVE,
                null,
                listOf(AttentionPersonalizationExplanation.INACTIVE_LIFECYCLE),
            )
        }

        val rule = preferences.escalationRules.singleOrNull { escalation ->
            escalation.fromUrgency == item.urgency &&
                nowEpochMillis - item.createdAtEpochMillis >= escalation.afterMillis
        }
        val effectiveUrgency = rule?.toUrgency ?: item.urgency
        val explanations = buildList {
            if (source.isProtected) {
                add(AttentionPersonalizationExplanation.PROTECTED_VISIBILITY)
            }
            if (effectiveUrgency == AttentionUrgency.CRITICAL) {
                add(AttentionPersonalizationExplanation.CRITICAL_VISIBILITY)
            }
            if (rule != null) add(AttentionPersonalizationExplanation.AGE_ESCALATION)
        }
        if (source.isProtected || effectiveUrgency == AttentionUrgency.CRITICAL) {
            return AttentionPersonalizationDecision(
                index,
                source,
                effectiveUrgency,
                AttentionPersonalizedDelivery.IMMEDIATE,
                nowEpochMillis,
                explanations,
            )
        }

        val deliveryTime = quietEndEpochMillis ?: nextDigestEpochMillis
        if (quietEndEpochMillis != null || preferences.digestEnabled) {
            val deferredExplanation = if (quietEndEpochMillis != null) {
                AttentionPersonalizationExplanation.QUIET_HOURS
            } else {
                AttentionPersonalizationExplanation.DIGEST_PREFERENCE
            }
            return AttentionPersonalizationDecision(
                index,
                source,
                effectiveUrgency,
                AttentionPersonalizedDelivery.DIGEST,
                deliveryTime,
                explanations + deferredExplanation,
            )
        }
        return AttentionPersonalizationDecision(
            index,
            source,
            effectiveUrgency,
            AttentionPersonalizedDelivery.IMMEDIATE,
            nowEpochMillis,
            explanations + AttentionPersonalizationExplanation.IMMEDIATE_PREFERENCE,
        )
    }

    private fun decisionComparator(
        preferences: AttentionPersonalizationPreferences,
    ): Comparator<AttentionPersonalizationDecision> {
        val rank = preferences.nonCriticalUrgencyOrder.withIndex().associate { it.value to it.index }
        return compareByDescending<AttentionPersonalizationDecision> { it.source.isProtected }
            .thenByDescending { it.effectiveUrgency == AttentionUrgency.CRITICAL }
            .thenBy { rank[it.effectiveUrgency] ?: -1 }
            .thenByDescending { it.source.item.state == AttentionState.ESCALATED }
            .thenBy { it.source.item.createdAtEpochMillis }
            .thenBy { it.source.item.id }
    }

    private fun nextDigestAt(nowEpochMillis: Long, intervalMinutes: Int): Long {
        val intervalMillis = intervalMinutes * MILLIS_PER_MINUTE
        val currentSlot = Math.floorDiv(nowEpochMillis, intervalMillis)
        return if (currentSlot >= Long.MAX_VALUE / intervalMillis) {
            Long.MAX_VALUE
        } else {
            (currentSlot + 1) * intervalMillis
        }
    }
}

private val NONCRITICAL_URGENCIES = setOf(
    AttentionUrgency.LOW,
    AttentionUrgency.NORMAL,
    AttentionUrgency.HIGH,
)
private val DEFAULT_NONCRITICAL_ORDER = listOf(
    AttentionUrgency.HIGH,
    AttentionUrgency.NORMAL,
    AttentionUrgency.LOW,
)
private const val MINUTES_PER_DAY = 24 * 60
private const val SECONDS_PER_MINUTE = 60
private const val MILLIS_PER_MINUTE = 60_000L
private const val MAX_ZONE_ID_CHARS = 64
private const val MAX_ESCALATION_MILLIS = 365L * 24 * 60 * MILLIS_PER_MINUTE
private const val MAX_ESCALATION_RULES = 3
private const val DEFAULT_DIGEST_INTERVAL_MINUTES = 60
private const val MAX_DIGEST_INTERVAL_MINUTES = 24 * 60
private const val DEFAULT_DIGEST_BATCH_SIZE = 4
private const val MAX_DIGEST_BATCH_SIZE = 32
private const val MAX_PERSONALIZATION_ITEMS = 256
