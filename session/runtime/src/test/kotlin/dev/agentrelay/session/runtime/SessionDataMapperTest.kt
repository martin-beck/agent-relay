package dev.agentrelay.session.runtime

import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.provider.api.AgentApproval
import dev.agentrelay.provider.api.AgentApprovalId
import dev.agentrelay.provider.api.AgentApprovalType
import dev.agentrelay.provider.api.AgentEvent
import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentToolStatus
import dev.agentrelay.session.api.SessionActivitySummary
import dev.agentrelay.session.api.SessionActivitySummaryKind
import dev.agentrelay.session.api.SessionLocator
import kotlin.test.assertEquals
import org.junit.Test

class SessionDataMapperTest {
    @Test
    fun generatedFallbacksStayTypedAndOutOfProviderPreview() {
        val cases = listOf(
            AgentEvent.MessageCompleted(
                sessionId,
                turnId = null,
                itemId = "message",
                channel = AgentMessageChannel.FINAL,
                text = "  ",
            ) to SessionActivitySummary.Generated(SessionActivitySummaryKind.NEW_AGENT_OUTPUT),
            AgentEvent.ToolChanged(
                sessionId,
                turnId = null,
                itemId = "tool-unnamed",
                toolName = "",
                summary = null,
                status = AgentToolStatus.FAILED,
            ) to SessionActivitySummary.Generated(SessionActivitySummaryKind.TOOL_FAILED),
            AgentEvent.ToolChanged(
                sessionId,
                turnId = null,
                itemId = "tool-named",
                toolName = "Shell",
                summary = "",
                status = AgentToolStatus.FAILED,
            ) to SessionActivitySummary.Generated(
                SessionActivitySummaryKind.NAMED_TOOL_FAILED,
                "Shell",
            ),
            AgentEvent.TurnCompleted(
                sessionId,
                turnId = null,
                successful = true,
                errorMessage = null,
            ) to SessionActivitySummary.Generated(SessionActivitySummaryKind.AGENT_TURN_COMPLETED),
            AgentEvent.TurnCompleted(
                sessionId,
                turnId = null,
                successful = false,
                errorMessage = "",
            ) to SessionActivitySummary.Generated(SessionActivitySummaryKind.AGENT_TURN_FAILED),
            AgentEvent.Error(sessionId, message = "", recoverable = true) to
                SessionActivitySummary.Generated(SessionActivitySummaryKind.AGENT_PROVIDER_FAILED),
            approvalEvent(AgentApprovalType.USER_INPUT, "question", title = "") to
                SessionActivitySummary.Generated(
                    SessionActivitySummaryKind.AGENT_QUESTION_REQUIRES_ANSWER,
                ),
            approvalEvent(AgentApprovalType.COMMAND, "approval", title = "") to
                SessionActivitySummary.Generated(SessionActivitySummaryKind.AGENT_APPROVAL_REQUIRED),
        )

        cases.forEach { (event, expected) ->
            val projection = SessionDataMapper.event(event, locator, now = 42)
            assertEquals(expected, projection.activity?.summary)
            assertEquals(null, projection.preview)
        }
    }

    @Test
    fun providerSummariesStayVerbatimAndOnlyEligibleContentUpdatesPreview() {
        val cases = listOf(
            Triple(
                AgentEvent.MessageCompleted(
                    sessionId,
                    turnId = null,
                    itemId = "message",
                    channel = AgentMessageChannel.FINAL,
                    text = "  Provider output  ",
                ),
                "Provider output",
                "Provider output",
            ),
            Triple(
                AgentEvent.ToolChanged(
                    sessionId,
                    turnId = null,
                    itemId = "tool",
                    toolName = "Shell",
                    summary = "  Provider tool detail  ",
                    status = AgentToolStatus.FAILED,
                ),
                "Provider tool detail",
                null,
            ),
            Triple(
                AgentEvent.TurnCompleted(
                    sessionId,
                    turnId = null,
                    successful = false,
                    errorMessage = "  Provider turn detail  ",
                ),
                "Provider turn detail",
                "Provider turn detail",
            ),
            Triple(
                AgentEvent.Error(sessionId, "  Provider failure detail  ", recoverable = false),
                "Provider failure detail",
                "Provider failure detail",
            ),
            Triple(
                approvalEvent(AgentApprovalType.COMMAND, "approval", "  Provider approval  "),
                "Provider approval",
                "Provider approval",
            ),
        )

        cases.forEach { (event, expected, expectedPreview) ->
            val projection = SessionDataMapper.event(event, locator, now = 42)
            assertEquals(SessionActivitySummary.Verbatim(expected), projection.activity?.summary)
            assertEquals(expectedPreview, projection.preview)
        }
    }

    private fun approvalEvent(
        type: AgentApprovalType,
        id: String,
        title: String,
    ) = AgentEvent.ApprovalRequested(
        sessionId,
        AgentApproval(
            id = AgentApprovalId(id),
            sessionId = sessionId,
            turnId = null,
            type = type,
            title = title,
            description = null,
        ),
    )

    private companion object {
        val sessionId = AgentSessionId("session")
        val locator = SessionLocator(
            connectionProviderId = ConnectionProviderId("local.device"),
            connectionProfileId = ConnectionProfileId("local"),
            agentProviderId = AgentProviderId("agent.codex"),
            agentSessionId = sessionId,
        )
    }
}
