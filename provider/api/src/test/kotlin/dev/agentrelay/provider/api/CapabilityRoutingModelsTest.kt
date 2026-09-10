/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CapabilityRoutingModelsTest {
    private val policy = RoutingPolicy(
        subjectKind = CapabilitySubjectKind.PROVIDER,
        requiredCapabilities = setOf("session.start"),
        allowedDataLocationClasses = setOf("local"),
        allowedPrivacyClasses = setOf(RoutingPrivacyClass.PUBLIC_METADATA),
        allowedPermissions = setOf(CapabilityPermission.READ_ONLY),
        maxCostMicros = 100,
        requiredContext = setOf("interactive"),
        maxLoadPercent = 80,
    )

    @Test
    fun `selects deterministic least cost eligible candidate`() {
        val selected = route(
            policy,
            listOf(
                candidate("provider-z", costMicros = 10),
                candidate("provider-a", costMicros = 10),
            ),
        ).selected

        assertEquals("provider-a", selected?.subjectId?.value)
    }

    @Test
    fun `fallback never relaxes policy and records bounded rejection`() {
        val decision = route(
            policy,
            listOf(
                candidate("remote", dataLocationClass = "remote", costMicros = 1),
                candidate("sensitive", privacyClass = RoutingPrivacyClass.SENSITIVE_CONTENT, costMicros = 2),
                candidate("allowed", costMicros = 20),
            ),
        )

        assertEquals("allowed", decision.selected?.subjectId?.value)
        assertEquals(
            listOf(
                RoutingRejectionReason.DATA_LOCATION_NOT_ALLOWED,
                RoutingRejectionReason.PRIVACY_NOT_ALLOWED,
                null,
            ),
            decision.attempts.map { it.rejection },
        )
    }

    @Test
    fun `rejects every candidate without inventing a fallback`() {
        val decision = route(policy, listOf(candidate("busy", loadPercent = 99)))

        assertNull(decision.selected)
        assertEquals(RoutingRejectionReason.LOAD_LIMIT, decision.attempts.single().rejection)
    }

    @Test
    fun `audits capability permission availability cost and context rejection`() {
        val decision = route(
            policy.copy(maxAttempts = 10),
            listOf(
                candidate("availability", availability = RoutingAvailability.UNAVAILABLE, costMicros = 1),
                candidate("capability", capabilities = setOf("session.resume"), costMicros = 1),
                candidate("context", context = setOf("background"), costMicros = 1),
                candidate("cost", costMicros = 101),
                candidate("permission", permissions = setOf(CapabilityPermission.NETWORK), costMicros = 1),
            ),
        )

        assertEquals(
            mapOf(
                "availability" to RoutingRejectionReason.UNAVAILABLE,
                "capability" to RoutingRejectionReason.MISSING_CAPABILITY,
                "context" to RoutingRejectionReason.CONTEXT_MISMATCH,
                "cost" to RoutingRejectionReason.COST_LIMIT,
                "permission" to RoutingRejectionReason.PERMISSION_NOT_ALLOWED,
            ),
            decision.attempts.associate { it.subjectId.value to it.rejection },
        )
    }

    @Test
    fun `rejects unsafe bounds and duplicate candidates`() {
        assertFailsWith<IllegalArgumentException> { policy.copy(maxLoadPercent = 101) }
        assertFailsWith<IllegalArgumentException> {
            route(policy, listOf(candidate("same"), candidate("same")))
        }
    }

    private fun candidate(
        id: String,
        dataLocationClass: String = "local",
        privacyClass: RoutingPrivacyClass = RoutingPrivacyClass.PUBLIC_METADATA,
        capabilities: Set<String> = setOf("session.start"),
        permissions: Set<CapabilityPermission> = setOf(CapabilityPermission.READ_ONLY),
        availability: RoutingAvailability = RoutingAvailability.AVAILABLE,
        costMicros: Long = 10,
        context: Set<String> = setOf("interactive"),
        loadPercent: Int = 20,
    ) = RoutingCandidate(
        subjectId = CapabilityRecordId(id),
        subjectKind = CapabilitySubjectKind.PROVIDER,
        capabilities = capabilities,
        dataLocationClass = dataLocationClass,
        privacyClass = privacyClass,
        permissions = permissions,
        availability = availability,
        costMicros = costMicros,
        context = context,
        loadPercent = loadPercent,
    )
}
