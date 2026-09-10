/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class WorkflowExecutionTest {
    private val policy = WorkflowExecutionPolicy(
        maxConcurrentRuns = 1,
        maxConcurrentSteps = 2,
        leaseMillis = 100,
        retry = WorkflowRetryPolicy(3, 10, 25),
    )

    @Test
    fun transitionsRequireRevisionAndRecordDeterministicHistory() {
        val execution = InMemoryWorkflowExecution(policy)
        execution.transition(0, WorkflowRunState.RUNNING, "admitted")
        assertFailsWith<IllegalArgumentException> {
            execution.transition(0, WorkflowRunState.PAUSED, "stale")
        }
        execution.transition(1, WorkflowRunState.SUCCEEDED, "completed")
        assertEquals(listOf(WorkflowRunState.RUNNING, WorkflowRunState.SUCCEEDED), execution.transitionHistory().map { it.to })
    }

    @Test
    fun concurrencyCancellationAndTerminalGuardsAreEnforced() {
        val execution = InMemoryWorkflowExecution(policy)
        execution.transition(0, WorkflowRunState.RUNNING, "start")
        execution.acquireStep(WorkflowStepId("one"), 100)
        execution.acquireStep(WorkflowStepId("two"), 100)
        assertFailsWith<IllegalArgumentException> { execution.acquireStep(WorkflowStepId("three"), 100) }
        val revision = execution.requestCancellation(1)
        assertFailsWith<IllegalArgumentException> { execution.acquireStep(WorkflowStepId("three"), 100) }
        execution.transition(revision.value, WorkflowRunState.CANCELLED, "user-request")
        assertFailsWith<IllegalArgumentException> { execution.requestCancellation(2) }
    }

    @Test
    fun retriesAreBoundedExponentialAndNeverRepeatUncertainEffects() {
        val execution = InMemoryWorkflowExecution(policy)
        execution.transition(0, WorkflowRunState.RUNNING, "start")
        val lease = execution.acquireStep(WorkflowStepId("step"), 50)
        assertEquals(
            RetryDecision.RetryAfter(10),
            execution.retryDecision(lease.stepId, 1, WorkflowFailureCategory.TRANSIENT, WorkflowEffectState.NONE),
        )
        assertEquals(
            RetryDecision.RetryAfter(10),
            execution.retryDecision(lease.stepId, 1, WorkflowFailureCategory.RATE_LIMITED, WorkflowEffectState.NONE),
        )
        assertEquals(
            RetryDecision.DoNotRetry("effect-outcome-uncertain"),
            execution.retryDecision(lease.stepId, 1, WorkflowFailureCategory.TRANSIENT, WorkflowEffectState.UNCERTAIN),
        )
        assertEquals(
            RetryDecision.DoNotRetry("failure-is-not-retryable"),
            execution.retryDecision(lease.stepId, 1, WorkflowFailureCategory.AUTHORIZATION, WorkflowEffectState.NONE),
        )
        execution.releaseStep(lease.stepId, lease.attempt)
        val retryLease = execution.acquireStep(lease.stepId, 50)
        assertEquals(
            RetryDecision.RetryAfter(20),
            execution.retryDecision(retryLease.stepId, retryLease.attempt, WorkflowFailureCategory.TRANSIENT, WorkflowEffectState.NONE),
        )
    }

    @Test
    fun expiredLeasesAreReturnedAndReleasedDeterministically() {
        val execution = InMemoryWorkflowExecution(policy)
        execution.transition(0, WorkflowRunState.RUNNING, "start")
        val lease = execution.acquireStep(WorkflowStepId("step"), 100)
        assertEquals(listOf(lease), execution.expireLeases(200))
        assertEquals(emptyList(), execution.expireLeases(200))
    }

    @Test
    fun retryAttemptCannotExceedBound() {
        val retry = WorkflowRetryPolicy(2, 1, 1)
        assertEquals(
            RetryDecision.RetryAfter(1),
            retry.decision(1, WorkflowFailureCategory.TIMEOUT, WorkflowEffectState.NONE),
        )
        assertEquals(
            RetryDecision.DoNotRetry("retry-budget-exhausted"),
            retry.decision(2, WorkflowFailureCategory.TIMEOUT, WorkflowEffectState.NONE),
        )
        assertIs<RetryDecision.DoNotRetry>(retry.decision(1, WorkflowFailureCategory.UNCERTAIN_EFFECT, WorkflowEffectState.NONE))
    }
}
