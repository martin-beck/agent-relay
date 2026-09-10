/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttentionModelsTest {
    private val item = AttentionItem(
        "a1", "project/workflow/reason", "project", "workflow", "Approval required",
        "evidence-1", AttentionRisk.REVERSIBLE, AttentionDecision.APPROVE, "operator", AttentionSafeDefault.REJECT,
        AttentionUrgency.HIGH, createdAtEpochMillis = 10, expiresAtEpochMillis = 20,
    )

    @Test
    fun policyExpiresAndNotificationUsesStableDeduplicationKey() {
        assertEquals(AttentionState.EXPIRED, item.policyAt(20).state)
        assertFalse(item.policyAt(20).notify)
        assertEquals(item.deduplicationKey, item.notificationKey)
    }

    @Test
    fun transitionsRecordResolutionAndRejectTerminalMutation() {
        val resolved = item.transition(AttentionState.RESOLVED, 12)
        assertEquals(12, resolved.resolvedAtEpochMillis)
        assertFailsWith<IllegalArgumentException> { resolved.transition(AttentionState.OPEN, 13) }
    }

    @Test
    fun snoozeRequiresSnoozedStateAndInvalidatesUnsafeLifecycle() {
        assertFailsWith<IllegalArgumentException> { item.copy(snoozedUntilEpochMillis = 15) }
        assertTrue(AttentionTransitions.allows(AttentionState.OPEN, AttentionState.INVALIDATED))
        assertFalse(AttentionTransitions.allows(AttentionState.EXPIRED, AttentionState.OPEN))
    }
}
