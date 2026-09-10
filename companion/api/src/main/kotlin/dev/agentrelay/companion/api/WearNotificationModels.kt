/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.companion.api

enum class WearCardUrgency { LOW, NORMAL, HIGH }

enum class WearCardState { ACTIVE, STALE, RESOLVED, EXPIRED }

/** Bounded watch card; it never carries raw session data or privileged actions. */
data class WearNotificationCard(
    val cardId: String,
    val revision: Long,
    val title: String,
    val redactedSummary: String,
    val urgency: WearCardUrgency,
    val state: WearCardState,
    val phoneDeepLink: String?,
    val safeAction: CompanionSafeAction?,
    val expiresAtEpochMillis: Long,
) {
    init {
        require(cardId.matches(Regex("card_v1_[A-Za-z0-9_-]{8,64}"))) { "Card id is invalid" }
        require(revision > 0) { "Card revision must be positive" }
        require(title.isNotBlank() && title.length <= 80) { "Card title is invalid" }
        require(redactedSummary.isNotBlank() && redactedSummary.length <= 240) {
            "Card summary is invalid"
        }
        require(!redactedSummary.contains("secret", true) && !redactedSummary.contains("token", true)) {
            "Card summary contains protected content"
        }
        phoneDeepLink?.let { require(it.length <= 256 && it.startsWith("agentrelay://")) { "Deep link is invalid" } }
        require(expiresAtEpochMillis > 0) { "Card expiry is invalid" }
        require(state != WearCardState.ACTIVE || expiresAtEpochMillis > 0) { "Active card must expire" }
    }

    fun visibleAt(nowEpochMillis: Long): Boolean =
        state == WearCardState.ACTIVE && nowEpochMillis < expiresAtEpochMillis
}

class WearNotificationReconciler {
    private val cards = linkedMapOf<String, WearNotificationCard>()

    fun apply(card: WearNotificationCard): Boolean {
        val previous = cards[card.cardId]
        if (previous != null && card.revision <= previous.revision) return false
        cards[card.cardId] = card
        return true
    }

    fun visible(nowEpochMillis: Long): List<WearNotificationCard> =
        cards.values.filter { it.visibleAt(nowEpochMillis) }

    fun clearExpired(nowEpochMillis: Long) {
        cards.entries.removeIf { !it.value.visibleAt(nowEpochMillis) }
    }
}
