/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.journeys

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserJourneyContractsTest {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    @Test
    fun `catalog is versioned unique and machine readable`() {
        val decoded = json.decodeFromString<UserJourneyCatalog>(UserJourneyCatalog.current.canonicalJson())
        assertEquals(1, decoded.schemaVersion)
        assertEquals(5, decoded.contracts.size)
        assertEquals(decoded.contracts.size, decoded.contracts.map { it.id }.toSet().size)
    }

    @Test
    fun `budgets are contextual rather than universal click limits`() {
        val attention = UserJourneyCatalog.current.contract("attention-resolution")
        val freshStart = UserJourneyCatalog.current.contract("fresh-start")
        assertTrue(attention.accepts(InteractionCost(navigationSteps = 4, confirmations = 1)))
        assertFalse(attention.accepts(InteractionCost(navigationSteps = 5)))
        assertTrue(freshStart.budget.navigationSteps > attention.budget.navigationSteps)
    }

    @Test
    fun `recovery expectations are explicit`() {
        assertEquals(
            JourneyRecovery.EXPLICIT_REAUTHORIZATION,
            UserJourneyCatalog.current.contract("connection-recovery").recovery,
        )
        assertEquals(
            JourneyRecovery.SAFE_ABORT,
            UserJourneyCatalog.current.contract("artifact-export").recovery,
        )
    }

    @Test
    fun `weighted score does not replace dimension checks`() {
        val budget = InteractionCostBudget(1, 0, 0, 0, 10_000, 0)
        val cost = InteractionCost(navigationSteps = 0, scrolls = 1)
        assertTrue(cost.weightedScore() > 0)
        assertFalse(budget.accepts(cost))
    }
}
