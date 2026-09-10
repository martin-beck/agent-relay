/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.connection.api

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CrossDeviceContinuityLedgerTest {
    private val digest = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 })

    @Test
    fun offlineDeviceReceivesOrderedUpdatesExactlyOnceAfterReconnect() {
        val ledger = CrossDeviceContinuityLedger("session-1")
        val phone = ContinuityDeviceId("phone-1")
        ledger.enroll(phone)
        ledger.markOffline(phone)
        val first = ledger.publish(digest)
        val second = ledger.publish(digest)
        assertEquals(listOf(first, second), ledger.reconnect(phone, ContinuityCursor(1, 0)))
        ledger.acknowledge(phone, second.cursor)
        assertTrue(ledger.pendingFor(phone).isEmpty())
    }

    @Test
    fun acknowledgementsCannotRegressOrSkipAuthority() {
        val ledger = CrossDeviceContinuityLedger("session-1")
        val wear = ContinuityDeviceId("wear-1")
        ledger.enroll(wear)
        val update = ledger.publish(digest)
        assertFailsWith<IllegalArgumentException> {
            ledger.acknowledge(wear, ContinuityCursor(1, 2))
        }
        ledger.acknowledge(wear, update.cursor)
        assertFailsWith<IllegalArgumentException> {
            ledger.acknowledge(wear, ContinuityCursor(1, 0))
        }
    }

    @Test
    fun replacementDeviceStartsAtAuthoritativeCursorAndRevokedDeviceCannotReturn() {
        val ledger = CrossDeviceContinuityLedger("session-1")
        val old = ContinuityDeviceId("phone-old")
        val replacement = ContinuityDeviceId("phone-new")
        ledger.enroll(old)
        val update = ledger.publish(digest)
        ledger.revoke(old)
        assertFailsWith<IllegalArgumentException> { ledger.reconnect(old, update.cursor) }
        val enrolled = ledger.enroll(replacement)
        assertEquals(update.cursor, enrolled.acknowledged)
        assertTrue(ledger.pendingFor(replacement).isEmpty())
    }
}
