/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.wear

import dev.agentrelay.companion.api.WearCardUrgency
import dev.agentrelay.companion.api.WearNotificationCard
import org.junit.Assert.assertEquals
import org.junit.Test

class WearAttentionStateTest {
    @Test
    fun visibleCardsOnlyReturnsActiveUnexpiredCards() {
        val active = WearNotificationCard(
            cardId = "card_v1_active01",
            revision = 1,
            title = "Review",
            redactedSummary = "A phone review is ready",
            urgency = WearCardUrgency.HIGH,
            state = dev.agentrelay.companion.api.WearCardState.ACTIVE,
            phoneDeepLink = null,
            safeAction = null,
            expiresAtEpochMillis = 2_000,
        )
        val expired = active.copy(cardId = "card_v1_expired1", expiresAtEpochMillis = 1_000)
        assertEquals(listOf(active), WearAttentionState("Connected", listOf(active, expired)).visibleCards(1_500))
    }
}
