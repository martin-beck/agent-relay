/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.companion.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WearNotificationModelsTest {
    private fun card(revision: Long, summary: String = "Build completed") = WearNotificationCard(
        "card_v1_attention-1", revision, "Build", summary, WearCardUrgency.NORMAL,
        WearCardState.ACTIVE, "agentrelay://attention/1", null, 200,
    )

    @Test
    fun reconcilerKeepsNewestRevisionAndOnlyVisibleCards() {
        val reconciler = WearNotificationReconciler()
        assertTrue(reconciler.apply(card(1)))
        assertFalse(reconciler.apply(card(1)))
        assertTrue(reconciler.apply(card(2)))
        assertEquals(1, reconciler.visible(100).size)
        reconciler.clearExpired(200)
        assertTrue(reconciler.visible(100).isEmpty())
    }

    @Test
    fun protectedContentAndNonPhoneLinksAreRejected() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { card(1, "secret token") }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            card(1).copy(phoneDeepLink = "https://host/private")
        }
    }
}
