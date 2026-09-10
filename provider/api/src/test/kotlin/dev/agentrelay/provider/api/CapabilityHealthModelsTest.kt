/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.api

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals

class CapabilityHealthModelsTest {
    @Test
    fun `accepts bounded health records and snapshots`() {
        val record = CapabilityHealthRecord(
            id = CapabilityRecordId("provider-a"),
            subjectKind = CapabilitySubjectKind.PROVIDER,
            capabilityNames = setOf("session.start", "session.resume"),
            health = CapabilityHealthObservation(
                state = CapabilityHealthState.HEALTHY,
                observedAtEpochSeconds = 42,
            ),
            dataLocationClass = "local",
        )

        assertEquals(1, CapabilityHealthSnapshot(listOf(record), 43).records.size)
    }

    @Test
    fun `rejects duplicate snapshot identifiers`() {
        val record = CapabilityHealthRecord(
            id = CapabilityRecordId("provider-a"),
            subjectKind = CapabilitySubjectKind.PROVIDER,
            capabilityNames = setOf("session.start"),
            health = CapabilityHealthObservation(CapabilityHealthState.DEGRADED, 1),
        )

        assertFailsWith<IllegalArgumentException> {
            CapabilityHealthSnapshot(listOf(record, record), 2)
        }
    }

    @Test
    fun `rejects unbounded or malformed values`() {
        assertFailsWith<IllegalArgumentException> { CapabilityRecordId("Provider A") }
        assertFailsWith<IllegalArgumentException> {
            CapabilityHealthObservation(CapabilityHealthState.UNKNOWN, 0, reasonCode = "Bad Code")
        }
    }
}
