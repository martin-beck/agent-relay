/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrivacyLifecycleModelsTest {
    private val digest = "C".repeat(43)
    private val record = PrivacyRecord("record-1", PrivacySensitivity.SENSITIVE, "incident-review", 100, digest)

    @Test
    fun retentionAndDeletionAreTerminal() {
        val ledger = PrivacyLifecycleLedger()
        ledger.put(record)
        assertEquals(1, ledger.expire(100))
        assertEquals(PrivacyProjection.DELETED, ledger.export(record.id, redacted = false).projection)
        assertFalse(ledger.delete(record.id))
        ledger.put(record.copy(id = "record-2", retentionUntilMillis = 200))
        assertTrue(ledger.delete("record-2"))
        assertEquals(PrivacyProjection.DELETED, ledger.export("record-2", redacted = false).projection)
    }

    @Test
    fun sensitiveAndSecretExportsFailClosedToRedaction() {
        val ledger = PrivacyLifecycleLedger()
        ledger.put(record)
        assertEquals(PrivacyProjection.REDACTED, ledger.export(record.id, redacted = false).projection)
        ledger.put(record.copy(id = "secret-1", sensitivity = PrivacySensitivity.SECRET))
        assertEquals(PrivacyProjection.DENIED, ledger.export("secret-1", redacted = false).projection)
    }

    @Test
    fun credentialRotationRevokesOldVersionAndRequiresMonotonicVersion() {
        val ledger = PrivacyLifecycleLedger()
        ledger.put(record.copy(credentialVersion = 1))
        assertTrue(ledger.rotateCredential(record.id, 2))
        assertTrue(ledger.isCredentialRevoked(record.id, 1))
        assertFalse(ledger.isCredentialRevoked(record.id, 2))
        assertFailsForVersionRegression { ledger.rotateCredential(record.id, 2) }
    }

    private fun assertFailsForVersionRegression(block: () -> Unit) {
        try {
            block()
            error("Expected credential version regression")
        } catch (_: IllegalArgumentException) {
            // Expected fail-closed rotation rejection.
        }
    }
}
