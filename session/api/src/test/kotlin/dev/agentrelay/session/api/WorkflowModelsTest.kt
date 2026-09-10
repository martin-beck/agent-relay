/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class WorkflowModelsTest {
    @Test
    fun transitionsAreFailClosedAtTerminalAndApprovalBoundaries() {
        assertTrue(WorkflowTransitions.task(WorkflowTaskState.PROPOSED, WorkflowTaskState.APPROVED))
        assertFalse(WorkflowTransitions.task(WorkflowTaskState.PROPOSED, WorkflowTaskState.RUNNING))
        assertFalse(WorkflowTransitions.task(WorkflowTaskState.COMPLETED, WorkflowTaskState.RUNNING))
        assertTrue(WorkflowTransitions.run(WorkflowRunState.RUNNING, WorkflowRunState.UNCERTAIN))
        assertFalse(WorkflowTransitions.run(WorkflowRunState.UNCERTAIN, WorkflowRunState.RUNNING))
        assertTrue(WorkflowTransitions.task(WorkflowTaskState.RUNNING, WorkflowTaskState.PAUSED))
        assertTrue(WorkflowTransitions.task(WorkflowTaskState.PAUSED, WorkflowTaskState.RUNNING))
        assertTrue(WorkflowTransitions.task(WorkflowTaskState.PAUSED, WorkflowTaskState.CANCELLED))
        assertTrue(WorkflowTransitions.run(WorkflowRunState.QUEUED, WorkflowRunState.RUNNING))
        assertTrue(WorkflowTransitions.run(WorkflowRunState.QUEUED, WorkflowRunState.CANCELLED))
        assertTrue(WorkflowTransitions.run(WorkflowRunState.PAUSED, WorkflowRunState.RUNNING))
        assertTrue(WorkflowTransitions.run(WorkflowRunState.PAUSED, WorkflowRunState.CANCELLED))
    }

    @Test
    fun contractsBoundBudgetsAndUncertainEffects() {
        assertFailsWith<IllegalArgumentException> {
            WorkflowBudget(1, 1, 1, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowRun(WorkflowRunId("run"), WorkflowId("task"), effect = WorkflowEffectState.UNCERTAIN)
        }
        WorkflowRun(
            WorkflowRunId("run"),
            WorkflowId("task"),
            state = WorkflowRunState.UNCERTAIN,
            effect = WorkflowEffectState.UNCERTAIN,
        )
        WorkflowStep(WorkflowStepId("step"), WorkflowRunId("run"), ordinal = 0, attempt = 1)
        assertFailsWith<IllegalArgumentException> {
            WorkflowStep(WorkflowStepId("step"), WorkflowRunId("run"), ordinal = -1)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowStep(WorkflowStepId("step"), WorkflowRunId("run"), ordinal = 0, state = WorkflowStepState.RETRYING)
        }
        assertFailsWith<IllegalArgumentException> { WorkflowId("") }
    }

    @Test
    fun r3TasksCannotAdvanceBeyondProposal() {
        assertFailsWith<IllegalArgumentException> {
            WorkflowTask(
                WorkflowId("task"),
                WorkflowId("workflow"),
                "project",
                "revision",
                WorkflowRisk.R3,
                WorkflowBudget(1, 1, 1, 1),
                WorkflowCompletionContract(emptySet(), emptySet(), false),
                state = WorkflowTaskState.APPROVED,
            )
        }
    }
}
