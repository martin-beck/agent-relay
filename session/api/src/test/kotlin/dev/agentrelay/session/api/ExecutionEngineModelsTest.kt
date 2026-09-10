/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.api

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ExecutionEngineModelsTest {
    @Test
    fun acceptsProgressArtifactQuestionAndWaitingResult() {
        val artifact = ExecutionEngineArtifact(
            name = "result.json",
            mediaType = "application/json",
            digest = DIGEST,
            sizeBytes = 12,
        )
        val question = ExecutionEngineQuestion("question-1", "Choose a target", listOf("phone", "watch"))

        ExecutionEngineRequest("request-1", "run", DIGEST, 100, 1_024)
        ExecutionEngineProgress(1, ExecutionEngineProgressPhase.STARTED, "started", 0)
        ExecutionEngineCheckpoint(1, DIGEST, resumable = true)
        ExecutionEngineResult(ExecutionEngineOutcome.WAITING_FOR_QUESTION, "needs input", listOf(artifact), question = question)
    }

    @Test
    fun rejectsInvalidBudgetsDigestsAndTerminalQuestionMismatch() {
        assertFailsWith<IllegalArgumentException> {
            ExecutionEngineRequest("request-1", "run", "bad", 100, 1_024)
        }
        assertFailsWith<IllegalArgumentException> {
            ExecutionEngineArtifact("result", "text/plain", "bad", 1)
        }
        assertFailsWith<IllegalArgumentException> {
            ExecutionEngineResult(ExecutionEngineOutcome.SUCCEEDED, "done", question = ExecutionEngineQuestion("q", "Continue?"))
        }
    }

    private companion object {
        const val DIGEST = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
