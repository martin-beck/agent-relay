/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilityDiscoveryModelsTest {
    private val subject = DiscoveredCapability(
        subjectId = CapabilityRecordId("provider-a"),
        subjectKind = CapabilitySubjectKind.PROVIDER,
        capabilities = setOf("session.start", "session.resume"),
        dataLocationClass = "local",
        permissions = setOf(CapabilityPermission.READ_ONLY),
        health = CapabilityHealthState.HEALTHY,
    )

    @Test
    fun `matches capability permission and location request`() {
        val request = CapabilityDiscoveryRequest(
            subjectKind = CapabilitySubjectKind.PROVIDER,
            requiredCapabilities = setOf("session.start"),
            allowedDataLocationClasses = setOf("local"),
            allowedPermissions = setOf(CapabilityPermission.READ_ONLY),
        )
        assertTrue(subject.matches(request))
    }

    @Test
    fun `rejects wrong location or unhealthy subject`() {
        val request = CapabilityDiscoveryRequest(
            subjectKind = CapabilitySubjectKind.PROVIDER,
            requiredCapabilities = setOf("session.start"),
            allowedDataLocationClasses = setOf("remote"),
            allowedPermissions = setOf(CapabilityPermission.READ_ONLY),
        )
        assertFalse(subject.matches(request))
        assertFalse(subject.copy(health = CapabilityHealthState.DEGRADED).matches(request))
    }

    @Test
    fun `rejects malformed identifiers instead of accepting raw paths`() {
        assertFailsWith<IllegalArgumentException> {
            CapabilityDiscoveryRequest(
                subjectKind = CapabilitySubjectKind.MODEL,
                requiredCapabilities = setOf("model/run"),
                allowedDataLocationClasses = setOf("local"),
                allowedPermissions = setOf(CapabilityPermission.READ_ONLY),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            subject.copy(dataLocationClass = "/tmp/provider-secret")
        }
    }

    @Test
    fun `keeps redaction reason bounded and result timestamp explicit`() {
        val result = CapabilityDiscoveryResult(
            matches = listOf(subject),
            observedAtEpochSeconds = 42,
            redactedReason = "permission.filtered",
        )

        assertEquals(42, result.observedAtEpochSeconds)
        assertEquals("permission.filtered", result.redactedReason)
        assertFailsWith<IllegalArgumentException> {
            result.copy(redactedReason = "permission filtered")
        }
        assertFailsWith<IllegalArgumentException> {
            result.copy(observedAtEpochSeconds = -1)
        }
    }
}
