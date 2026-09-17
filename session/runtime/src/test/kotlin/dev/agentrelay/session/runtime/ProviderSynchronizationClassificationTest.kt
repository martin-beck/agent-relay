/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.runtime

import dev.agentrelay.provider.api.ProviderReadiness
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
