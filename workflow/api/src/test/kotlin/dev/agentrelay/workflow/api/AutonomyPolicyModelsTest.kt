/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.workflow.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutonomyPolicyModelsTest {
    private val policy = AutonomyPolicy(
        version = 2,
        mode = AutonomyMode.APPROVE_SENSITIVE,
        allowedEffects = setOf(PolicyEffect.READ_CONTEXT, PolicyEffect.NOTIFY_USER),
        maxEffects = 2,
        maxRuntimeSeconds = 60,
    )
    private val lease = PolicyLease("lease_v1_session", 2, 100, 200)

    @Test
    fun allowsOnlyMatchingUnexpiredEffectsWithinBudget() {
        val decision = AutonomyPolicyEvaluator.evaluate(policy, lease, PolicyEffect.NOTIFY_USER, 150, 0)
        assertTrue(decision.allowed)
        assertEquals(PolicyDecisionReason.ALLOWED, decision.reason)
        assertFalse(AutonomyPolicyEvaluator.evaluate(policy, lease, PolicyEffect.NOTIFY_USER, 150, 2).allowed)
    }

    @Test
    fun revocationPauseExpiryAndVersionMismatchFailClosed() {
        assertEquals(
            PolicyDecisionReason.LEASE_REVOKED,
            AutonomyPolicyEvaluator.evaluate(policy, lease.revoke(), PolicyEffect.READ_CONTEXT, 150, 0).reason,
        )
        assertEquals(
            PolicyDecisionReason.PAUSED,
            AutonomyPolicyEvaluator.evaluate(policy, lease, PolicyEffect.READ_CONTEXT, 150, 0, paused = true).reason,
        )
        assertEquals(
            PolicyDecisionReason.LEASE_EXPIRED,
            AutonomyPolicyEvaluator.evaluate(policy, lease, PolicyEffect.READ_CONTEXT, 200, 0).reason,
        )
        assertEquals(
            PolicyDecisionReason.POLICY_VERSION_MISMATCH,
            AutonomyPolicyEvaluator.evaluate(policy, lease.copy(policyVersion = 1), PolicyEffect.READ_CONTEXT, 150, 0).reason,
        )
    }

    @Test
    fun policyRejectsImplicitBroadeningAndInvalidLease() {
        assertFailsWith<IllegalArgumentException> {
            AutonomyPolicy(1, AutonomyMode.TRUST_SAFE, setOf(PolicyEffect.EXTERNAL_WRITE), 1, 1)
        }
        assertFailsWith<IllegalArgumentException> { PolicyLease("invalid", 1, 2, 1) }
        assertTrue(AutonomyControlState().pauseAll().paused)
        assertFalse(AutonomyControlState().pauseAll().resume().paused)
    }
}
