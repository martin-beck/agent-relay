/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.wear

import dev.agentrelay.companion.api.WearCardState
import dev.agentrelay.companion.api.WearNotificationCard

/** UI-facing state for the watch; it intentionally contains only redacted cards. */
data class WearAttentionState(
    val connectionLabel: String,
    val cards: List<WearNotificationCard>,
) {
    fun visibleCards(nowEpochMillis: Long): List<WearNotificationCard> =
        cards.filter { it.state == WearCardState.ACTIVE && it.visibleAt(nowEpochMillis) }
}
