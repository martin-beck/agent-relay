/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.api

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowReplayReconciliationTest {
    @Test
    fun appliesInOrderAndSuppressesDuplicateEvents() {
        val reconciler = WorkflowReplayReconciler(WorkflowReplayCursor(authorityEpoch = 7))

        assertEquals(WorkflowReplayDisposition.APPLY, reconciler.reconcile(0, 7).disposition)
        assertEquals(WorkflowReplayDisposition.APPLY, reconciler.reconcile(1, 7).disposition)
        assertEquals(WorkflowReplayDisposition.DUPLICATE, reconciler.reconcile(1, 7).disposition)
        assertEquals(WorkflowReplayCursor(1, 7), reconciler.cursor())
    }

    @Test
    fun gapsAndAuthorityChangesDoNotAdvanceTheDurableCursor() {
        val reconciler = WorkflowReplayReconciler(WorkflowReplayCursor(sequence = 2, authorityEpoch = 7))

        assertEquals(WorkflowReplayDisposition.GAP, reconciler.reconcile(4, 7).disposition)
        assertEquals(WorkflowReplayDisposition.AUTHORITY_CHANGED, reconciler.reconcile(3, 8).disposition)
        assertEquals(WorkflowReplayCursor(2, 7), reconciler.cursor())
    }
}
