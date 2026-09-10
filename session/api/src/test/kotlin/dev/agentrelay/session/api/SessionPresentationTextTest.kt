/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.api

import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentApprovalType
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSessionId
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SessionPresentationTextTest {
    @Test
    fun questionRejectsActionGeneratedText() {
        assertFailsWith<IllegalArgumentException> {
            SessionQuestion(
                id = "question",
                providerQuestionId = "provider-question",
                header = null,
                prompt = SessionPresentationText.Generated(
                    SessionPresentationTextKind.ACTION_REVIEW_REQUIRED,
                ),
            )
        }
    }

    @Test
    fun actionRejectsQuestionGeneratedText() {
        assertFailsWith<IllegalArgumentException> {
            SessionActionRequest(
                id = "action",
                providerApprovalId = "provider-action",
                locator = SessionLocator(
                    connectionProviderId = ConnectionProviderId("local.device"),
                    connectionProfileId = ConnectionProfileId("this-device"),
                    agentProviderId = AgentProviderId("agent.codex"),
                    agentSessionId = AgentSessionId("session"),
                ),
                turnId = null,
                type = AgentApprovalType.USER_INPUT,
                title = SessionPresentationText.Generated(
                    SessionPresentationTextKind.AGENT_QUESTION,
                ),
                description = null,
                command = null,
                workingDirectory = null,
                questions = emptyList(),
                availableDecisions = setOf(AgentApprovalDecision.SUBMIT),
                riskReasons = emptySet(),
                receivedAtEpochMillis = 1,
            )
        }
    }
}
