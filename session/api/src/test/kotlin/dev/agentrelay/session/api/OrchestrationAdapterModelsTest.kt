/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OrchestrationAdapterModelsTest {
    @Test
    fun disabledPolicyAndExplicitModesAreEnforced() {
        assertFailsWith<IllegalArgumentException> { input(policy = policy()) }

        val adapter = PolicyOrchestrationAdapter()
        val plan = adapter.plan(
            input(
                mode = OrchestrationMode.PARALLEL,
                policy = policy(enabled = true, modes = setOf(OrchestrationMode.PARALLEL)),
            ),
        )
        assertEquals(listOf("attempt-a", "attempt-b"), plan.orderedAttemptIds)
        assertEquals(2, plan.maxParallelism)
    }

    @Test
    fun pipelineIsSerialAndSelectionIsPolicyBound() {
        val plan = PolicyOrchestrationAdapter().plan(
            input(
                mode = OrchestrationMode.PIPELINE,
                policy = policy(
                    enabled = true,
                    modes = setOf(OrchestrationMode.PIPELINE),
                    models = setOf("model-a"),
                    tools = setOf("tool-a"),
                ),
                selection = ToolModelSelection("model-a", listOf("tool-a")),
            ),
        )
        assertEquals(1, plan.maxParallelism)
        assertEquals("model-a", plan.selection.modelId)
        assertFailsWith<IllegalArgumentException> {
            PolicyOrchestrationAdapter().plan(
                input(
                    policy = policy(enabled = true, modes = setOf(OrchestrationMode.PARALLEL)),
                    selection = ToolModelSelection("not-allowed"),
                ),
            )
        }
    }

    @Test
    fun duplicateAttemptsAndOverlargeDelegationAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            input(
                attempts = listOf(attempt("attempt-a"), attempt("attempt-a")),
                policy = policy(enabled = true, modes = setOf(OrchestrationMode.PARALLEL)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            input(attempts = emptyList(), policy = policy(enabled = true, modes = setOf(OrchestrationMode.PARALLEL)))
        }
    }

    private fun input(
        mode: OrchestrationMode = OrchestrationMode.PARALLEL,
        attempts: List<DelegatedAttempt> = listOf(attempt("attempt-a"), attempt("attempt-b")),
        selection: ToolModelSelection = ToolModelSelection(),
        policy: OrchestrationAdapterPolicy,
    ) = OrchestrationAdapterInput("delegation-1", mode, attempts, selection, policy)

    private fun attempt(id: String) = DelegatedAttempt(id, DelegatedRole.TESTER, "check", DIGEST)

    private fun policy(
        enabled: Boolean = false,
        modes: Set<OrchestrationMode> = emptySet(),
        models: Set<String> = emptySet(),
        tools: Set<String> = emptySet(),
    ) = OrchestrationAdapterPolicy(enabled, modes, maxDelegates = 4, maxParallelism = 2, models, tools)

    private companion object {
        const val DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
