/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProviderOutcomeModelsTest {
    @Test
    fun progressAllowsOpenEndedWork() {
        val outcome = ProviderOutcome.Progress(10, completedUnits = 3)
        assertEquals(null, outcome.totalUnits)
    }

    @Test
    fun progressRejectsInconsistentTotals() {
        assertFailsWith<IllegalArgumentException> {
            ProviderOutcome.Progress(10, completedUnits = 4, totalUnits = 3)
        }
    }

    @Test
    fun checkpointTokenIsBoundedAndOpaque() {
        assertEquals(
            true,
            ProviderOutcome.Checkpoint(10, "turn-1:checkpoint").resumable,
        )
        assertFailsWith<IllegalArgumentException> {
            ProviderOutcome.Checkpoint(10, "token with spaces")
        }
    }

    @Test
    fun resourceRejectsNegativeConsumption() {
        assertFailsWith<IllegalArgumentException> {
            ProviderOutcome.Resource(10, ProviderResourceKind.BYTES, -1)
        }
    }

    @Test
    fun failurePreservesRecoveryClassification() {
        val outcome = ProviderOutcome.Failure(
            observedAtEpochSeconds = 10,
            kind = ProviderFailureKind.RATE_LIMIT,
            recoverable = true,
            detail = "retry-after",
        )
        assertEquals(ProviderFailureKind.RATE_LIMIT, outcome.kind)
        assertEquals(true, outcome.recoverable)
    }
}
