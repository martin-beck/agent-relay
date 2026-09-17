/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BoundedRetryTest {
    @Test
    fun retriesWithExponentialBackoffAndStopsAfterBound() = runTest {
        var attempts = 0
        val retries = mutableListOf<Pair<Int, Long>>()

        val result = retryBounded(
            maxAttempts = 3,
            initialDelayMillis = 100,
            maxDelayMillis = 250,
            onRetry = { attempt, _, delay -> retries += attempt to delay },
        ) {
            attempts += 1
            if (attempts < 3) error("transient")
            "ready"
        }

        assertEquals("ready", result)
        assertEquals(3, attempts)
        assertEquals(listOf(2 to 100L, 3 to 200L), retries)
    }

    @Test
    fun cancellationDoesNotStartAnotherAttempt() = runTest {
        var attempts = 0
        val error = assertFailsWith<CancellationException> {
            retryBounded(initialDelayMillis = 1_000) {
                attempts += 1
                throw CancellationException("cancelled")
            }
        }
        assertTrue(error.message.orEmpty().contains("cancelled"))
        assertEquals(1, attempts)
        runCurrent()
        advanceTimeBy(2_000)
        assertEquals(1, attempts)
    }
}
