/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.api

import java.time.Instant
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AttentionPersonalizationModelsTest {
    @Test
    fun protectedReasonsAlwaysBypassQuietHoursAndDigest() {
        val quiet = AttentionQuietHours(22 * 60, 7 * 60, "UTC")
        val now = Instant.parse("2026-09-07T23:30:00Z").toEpochMilli()
        for (reason in AttentionProtectionReason.entries) {
            for (urgency in AttentionUrgency.entries) {
                for (digestEnabled in listOf(false, true)) {
                    val item = personalizable(
                        id = "$reason-$urgency-$digestEnabled",
                        urgency = urgency,
                        protectionReasons = setOf(reason),
                        decision = if (reason == AttentionProtectionReason.APPROVAL) {
                            AttentionDecision.APPROVE
                        } else {
                            AttentionDecision.ACKNOWLEDGE
                        },
                    )
                    val projection = AttentionPersonalizer.project(
                        1,
                        listOf(item),
                        AttentionPersonalizationPreferences(
                            quietHours = quiet,
                            digestEnabled = digestEnabled,
                        ),
                        now,
                    )
                    assertEquals(
                        AttentionPersonalizedDelivery.IMMEDIATE,
                        projection.decisions.single().delivery,
                    )
                    assertEquals(now, projection.decisions.single().deliverAtEpochMillis)
                    assertTrue(
                        AttentionPersonalizationExplanation.PROTECTED_VISIBILITY in
                            projection.decisions.single().explanations,
                    )
                }
            }
        }
    }

    @Test
    fun criticalAndAgeEscalatedCriticalAttentionCannotBeBatched() {
        val now = 100_000L
        val critical = personalizable("critical", AttentionUrgency.CRITICAL)
        val aged = personalizable("aged", AttentionUrgency.NORMAL, createdAt = 1)
        val preferences = AttentionPersonalizationPreferences(
            digestEnabled = true,
            escalationRules = listOf(
                AttentionEscalationRule(AttentionUrgency.NORMAL, 10, AttentionUrgency.CRITICAL),
            ),
        )

        val projection = AttentionPersonalizer.project(4, listOf(aged, critical), preferences, now)

        assertEquals(listOf("critical", "aged"), projection.immediate.map { it.source.item.id })
        assertTrue(projection.digestBatches.isEmpty())
        assertTrue(
            AttentionPersonalizationExplanation.AGE_ESCALATION in
                projection.decisions.first().explanations,
        )
    }

    @Test
    fun quietHoursUseExplicitZoneAcrossDstAndIgnoreDefaultLocale() {
        val quiet = AttentionQuietHours(60, 4 * 60, "Europe/Berlin")
        val duringGapNight = Instant.parse("2026-03-29T00:30:00Z").toEpochMilli()
        val expectedEnd = Instant.parse("2026-03-29T02:00:00Z").toEpochMilli()
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertTrue(quiet.isActiveAt(duringGapNight))
            assertEquals(expectedEnd, quiet.nextEndAt(duringGapNight))
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals(expectedEnd, quiet.nextEndAt(duringGapNight))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun quietHoursAreStartInclusiveAndEndExclusiveAcrossMidnight() {
        val quiet = AttentionQuietHours(22 * 60, 7 * 60, "UTC")

        assertTrue(quiet.isActiveAt(Instant.parse("2026-09-07T22:00:00Z").toEpochMilli()))
        assertTrue(quiet.isActiveAt(Instant.parse("2026-09-08T06:59:59Z").toEpochMilli()))
        assertTrue(!quiet.isActiveAt(Instant.parse("2026-09-08T07:00:00Z").toEpochMilli()))
        assertTrue(!quiet.isActiveAt(Instant.parse("2026-09-07T21:59:59Z").toEpochMilli()))
    }

    @Test
    fun digestBatchesAreBoundedDeterministicAndReversible() {
        val items = listOf(
            personalizable("old-normal", AttentionUrgency.NORMAL, createdAt = 1),
            personalizable("high", AttentionUrgency.HIGH, createdAt = 3),
            personalizable("low", AttentionUrgency.LOW, createdAt = 2),
            personalizable("new-normal", AttentionUrgency.NORMAL, createdAt = 4),
            personalizable("old-high", AttentionUrgency.HIGH, createdAt = 0),
        )
        val preferences = AttentionPersonalizationPreferences(
            digestEnabled = true,
            digestIntervalMinutes = 15,
            digestBatchSize = 2,
        )

        val projection = AttentionPersonalizer.project(8, items, preferences, 20_000)
        val restoredItems = items.map { source ->
            PersonalizableAttentionItem(source.item.copy(), source.protectionReasons.toSet())
        }
        val restoredPreferences = AttentionPersonalizationPreferences(
            digestEnabled = true,
            digestIntervalMinutes = 15,
            digestBatchSize = 2,
        )
        val restarted = AttentionPersonalizer.project(
            8,
            restoredItems,
            restoredPreferences,
            20_000,
        )

        assertEquals(projection, restarted)
        assertEquals(listOf(2, 2, 1), projection.digestBatches.map { it.items.size })
        assertEquals(
            listOf("old-high", "high", "old-normal", "new-normal", "low"),
            projection.digestBatches.flatMap { it.items }.map { it.source.item.id },
        )
        assertEquals(items, projection.restoreOriginalOrder())
    }

    @Test
    fun customRankingOnlyReordersNoncriticalAttention() {
        val items = listOf(
            personalizable("high", AttentionUrgency.HIGH),
            personalizable("low", AttentionUrgency.LOW),
            personalizable("critical", AttentionUrgency.CRITICAL),
            personalizable(
                "security",
                AttentionUrgency.LOW,
                setOf(AttentionProtectionReason.SECURITY),
            ),
        )
        val preferences = AttentionPersonalizationPreferences(
            nonCriticalUrgencyOrder = listOf(
                AttentionUrgency.LOW,
                AttentionUrgency.NORMAL,
                AttentionUrgency.HIGH,
            ),
        )

        val ids = AttentionPersonalizer.project(1, items, preferences, 10).immediate
            .map { it.source.item.id }

        assertEquals(listOf("security", "critical", "low", "high"), ids)
    }

    @Test
    fun rankedDeliveryIsPermutationInvariantAndRestorationTracksInput() {
        val items = listOf(
            personalizable("normal", AttentionUrgency.NORMAL, createdAt = 2),
            personalizable("old-high", AttentionUrgency.HIGH, createdAt = 1),
            personalizable("new-high", AttentionUrgency.HIGH, createdAt = 3),
        )
        val permutations = listOf(
            items,
            listOf(items[1], items[2], items[0]),
            items.reversed(),
        )

        val projections = permutations.map { input ->
            AttentionPersonalizer.project(
                2,
                input,
                AttentionPersonalizationPreferences(),
                10,
            )
        }

        assertTrue(
            projections.map { projection -> projection.immediate.map { it.source.item.id } }
                .all { it == listOf("old-high", "new-high", "normal") },
        )
        assertEquals(permutations, projections.map { it.restoreOriginalOrder() })
    }

    @Test
    fun escalationChangesUrgencyExactlyAtTheConfiguredBoundary() {
        val item = personalizable("aging", AttentionUrgency.LOW)
        val preferences = AttentionPersonalizationPreferences(
            digestEnabled = true,
            escalationRules = listOf(
                AttentionEscalationRule(AttentionUrgency.LOW, 10, AttentionUrgency.HIGH),
            ),
        )

        val before = AttentionPersonalizer.project(1, listOf(item), preferences, 9)
        val atBoundary = AttentionPersonalizer.project(1, listOf(item), preferences, 10)

        assertEquals(AttentionUrgency.LOW, before.decisions.single().effectiveUrgency)
        assertEquals(AttentionUrgency.HIGH, atBoundary.decisions.single().effectiveUrgency)
        assertEquals(AttentionPersonalizedDelivery.DIGEST, atBoundary.decisions.single().delivery)
        assertTrue(
            AttentionPersonalizationExplanation.AGE_ESCALATION in
                atBoundary.decisions.single().explanations,
        )
    }

    @Test
    fun inactiveLifecycleRemainsInactiveWithoutPersonalizedSuppression() {
        val expired = personalizable(
            "expired",
            AttentionUrgency.CRITICAL,
            setOf(AttentionProtectionReason.UNCERTAINTY),
            createdAt = 1,
            expiresAt = 5,
        )

        val decision = AttentionPersonalizer.project(
            1,
            listOf(expired),
            AttentionPersonalizationPreferences(digestEnabled = true),
            5,
        ).decisions.single()

        assertEquals(AttentionPersonalizedDelivery.INACTIVE, decision.delivery)
        assertEquals(
            listOf(AttentionPersonalizationExplanation.INACTIVE_LIFECYCLE),
            decision.explanations,
        )
    }

    @Test
    fun malformedPoliciesAndAmbiguousApprovalClassificationFailClosed() {
        assertFailsWith<IllegalArgumentException> {
            AttentionQuietHours(0, 0, "UTC")
        }
        assertFailsWith<Exception> {
            AttentionQuietHours(0, 1, "not/a-zone")
        }
        assertFailsWith<IllegalArgumentException> {
            AttentionPersonalizationPreferences(
                nonCriticalUrgencyOrder = listOf(AttentionUrgency.LOW),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AttentionPersonalizationPreferences(
                escalationRules = listOf(
                    AttentionEscalationRule(AttentionUrgency.HIGH, 1, AttentionUrgency.NORMAL),
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            personalizable(
                "approval",
                AttentionUrgency.NORMAL,
                decision = AttentionDecision.APPROVE,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AttentionPersonalizer.project(
                1,
                listOf(
                    personalizable("duplicate", AttentionUrgency.NORMAL),
                    personalizable("duplicate", AttentionUrgency.HIGH),
                ),
                AttentionPersonalizationPreferences(),
                10,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AttentionPersonalizer.project(
                1,
                listOf(
                    personalizable("first", AttentionUrgency.NORMAL, deduplicationKey = "same"),
                    personalizable("second", AttentionUrgency.HIGH, deduplicationKey = "same"),
                ),
                AttentionPersonalizationPreferences(),
                10,
            )
        }
    }

    private fun personalizable(
        id: String,
        urgency: AttentionUrgency,
        protectionReasons: Set<AttentionProtectionReason> = emptySet(),
        decision: AttentionDecision = AttentionDecision.ACKNOWLEDGE,
        createdAt: Long = 0,
        expiresAt: Long? = null,
        deduplicationKey: String = "dedup-$id",
    ): PersonalizableAttentionItem = PersonalizableAttentionItem(
        AttentionItem(
            id = id,
            deduplicationKey = deduplicationKey,
            projectId = "project",
            workflowId = "workflow",
            reason = "Synthetic attention",
            evidenceId = null,
            risk = AttentionRisk.NONE,
            requestedDecision = decision,
            authority = "operator",
            safeDefault = AttentionSafeDefault.HOLD,
            urgency = urgency,
            createdAtEpochMillis = createdAt,
            expiresAtEpochMillis = expiresAt,
        ),
        protectionReasons,
    )
}
