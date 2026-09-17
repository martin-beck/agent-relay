/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.runtime

import dev.agentrelay.provider.api.ProviderReadiness
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProviderSynchronizationClassificationTest {
    @Test
    fun readinessStatesHaveTruthfulSynchronizationClassification() {
        assertEquals(
            ProviderSynchronizationClassification.UNSUPPORTED,
            ProviderReadiness.Incompatible("1.0.0", "bridge unavailable")
                .synchronizationClassification(),
        )
        assertEquals(
            ProviderSynchronizationClassification.UNAVAILABLE,
            ProviderReadiness.Missing("install").synchronizationClassification(),
        )
        assertEquals(
            ProviderSynchronizationClassification.UNAVAILABLE,
            ProviderReadiness.NeedsAuthentication(null, "sign in")
                .synchronizationClassification(),
        )
        assertEquals(
            ProviderSynchronizationClassification.TRANSIENT_FAILURE,
            ProviderReadiness.Failed("timeout", recoverable = true)
                .synchronizationClassification(),
        )
        assertEquals(
            ProviderSynchronizationClassification.REAL_FAILURE,
            ProviderReadiness.Failed("malformed response", recoverable = false)
                .synchronizationClassification(),
        )
    }

    @Test
    fun fatalSynchronizationFailuresAreNotConvertedToProviderStatus() {
        val fatal = AssertionError("fatal test failure")

        assertFailsWith<AssertionError> {
            fatal.rethrowFatalSynchronizationFailure()
        }

        IllegalStateException("recoverable test failure")
            .rethrowFatalSynchronizationFailure()
    }

    @Test
    fun timeoutCauseAlwaysRetainsTransientClassification() = runTest {
        val timeout = assertFailsWith<TimeoutCancellationException> {
            withTimeout(1) {
                delay(10)
            }
        }

        assertEquals(
            ProviderSynchronizationClassification.TRANSIENT_FAILURE,
            synchronizationFailureClassification(
                cause = timeout,
                fallback = ProviderSynchronizationClassification.REAL_FAILURE,
            ),
        )
        assertEquals(
            ProviderSynchronizationClassification.REAL_FAILURE,
            synchronizationFailureClassification(
                cause = IllegalStateException("unexpected"),
                fallback = ProviderSynchronizationClassification.REAL_FAILURE,
            ),
        )
    }
}
