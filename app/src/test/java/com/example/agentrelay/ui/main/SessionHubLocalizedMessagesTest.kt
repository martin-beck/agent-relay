/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import com.example.agentrelay.R
import dev.agentrelay.connection.api.ConnectionCapability
import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProfileSummary
import dev.agentrelay.connection.api.ConnectionProviderDescriptor
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentApprovalType
import dev.agentrelay.provider.api.AgentCapability
import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentProviderDescriptor
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.AgentTranscriptRole
import dev.agentrelay.session.api.CachedTranscriptEntry
import dev.agentrelay.session.api.SessionActivity
import dev.agentrelay.session.api.SessionActivitySummary
import dev.agentrelay.session.api.SessionActivitySummaryKind
import dev.agentrelay.session.api.SessionActivityType
import dev.agentrelay.session.api.SessionActionRequest
import dev.agentrelay.session.api.SessionDraft
import dev.agentrelay.session.api.SessionHubSnapshot
import dev.agentrelay.session.api.SessionLocator
import dev.agentrelay.session.api.SessionObservation
import dev.agentrelay.session.api.SessionPresentationText
import dev.agentrelay.session.api.SessionPresentationTextKind
import dev.agentrelay.session.api.SessionQuestion
import dev.agentrelay.session.api.SessionRecord
import dev.agentrelay.session.runtime.AgentEndpointKey
import dev.agentrelay.session.runtime.AgentEndpointPhase
import dev.agentrelay.session.runtime.AgentEndpointStatus
import dev.agentrelay.session.runtime.SessionConnectionKey
import dev.agentrelay.session.runtime.SessionCoordinatorIssue
import dev.agentrelay.session.runtime.SessionCoordinatorIssueKind
import dev.agentrelay.session.runtime.SessionCoordinatorSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHubLocalizedMessagesTest {
    @Test
    fun transcriptRolesAndChannelsMapToDistinctTimelineKinds() {
        val connectionProviderId = ConnectionProviderId("local.device")
        val profileId = ConnectionProfileId("this-device")
        val sessionLocator = localizedLocator(connectionProviderId, profileId)
        val cases = listOf(
            Triple(
                AgentTranscriptRole.USER,
                null,
                TimelineEntryKind.USER_MESSAGE to
                    UiMessage.Localized(R.string.session_timeline_role_user),
            ),
            Triple(
                AgentTranscriptRole.TOOL,
                null,
                TimelineEntryKind.TOOL to
                    UiMessage.Localized(R.string.session_timeline_role_tool),
            ),
            Triple(
                AgentTranscriptRole.SYSTEM,
                null,
                TimelineEntryKind.SYSTEM to
                    UiMessage.Localized(R.string.session_timeline_role_system),
            ),
            Triple(
                AgentTranscriptRole.AGENT,
                AgentMessageChannel.COMMENTARY,
                TimelineEntryKind.AGENT_COMMENTARY to
                    UiMessage.Localized(R.string.session_timeline_role_agent_commentary),
            ),
            Triple(
                AgentTranscriptRole.AGENT,
                AgentMessageChannel.FINAL,
                TimelineEntryKind.AGENT_FINAL to
                    UiMessage.Localized(R.string.session_timeline_role_agent_final),
            ),
            Triple(
                AgentTranscriptRole.AGENT,
                AgentMessageChannel.PLAN,
                TimelineEntryKind.PLAN to
                    UiMessage.Localized(R.string.session_timeline_role_plan),
            ),
            Triple(
                AgentTranscriptRole.AGENT,
                AgentMessageChannel.REASONING_SUMMARY,
                TimelineEntryKind.REASONING_SUMMARY to
                    UiMessage.Localized(R.string.session_timeline_role_reasoning_summary),
            ),
            Triple(
                AgentTranscriptRole.AGENT,
                AgentMessageChannel.SYSTEM,
                TimelineEntryKind.SYSTEM to
                    UiMessage.Localized(R.string.session_timeline_role_system),
            ),
        )
        val snapshot = SessionHubSnapshot(
            sessions = listOf(localizedRecord(sessionLocator, "Typed timeline", "This device")),
            transcripts = mapOf(
                sessionLocator to cases.mapIndexed { index, (role, channel, _) ->
                    CachedTranscriptEntry(
                        id = "entry-$index",
                        turnId = null,
                        role = role,
                        channel = channel,
                        text = "Entry $index",
                        createdAtEpochMillis = index.toLong(),
                    )
                },
            ),
        )

        val mapped = SessionHubUiMapper.map(
            coordinator = SessionCoordinatorSnapshot(
                profiles = listOf(localizedProfile(connectionProviderId, profileId, "This device")),
            ),
            sessions = snapshot,
            connectionProviders = listOf(localizedDescriptor(connectionProviderId, "Local")),
            selectedSessionKey = sessionLocator.stableUiKey,
            operationError = null,
            busyConnectionKeys = emptySet(),
        )

        assertEquals(
            cases.map { it.third },
            mapped.selectedSession?.transcript?.map { it.kind to it.roleLabel },
        )
    }

    @Test
    fun coordinatorIssuesMapTypedContextToLocalizedMessages() {
        val issues = listOf(
            SessionCoordinatorIssue(
                id = "profile-discovery",
                kind = SessionCoordinatorIssueKind.PROFILE_DISCOVERY,
                connection = null,
                agentProviderId = null,
                connectionProviderLabel = "Local",
                recoverable = true,
                occurredAtEpochMillis = 1,
            ),
            SessionCoordinatorIssue(
                id = "connection-setup",
                kind = SessionCoordinatorIssueKind.CONNECTION_SETUP,
                connection = null,
                agentProviderId = null,
                connectionLabel = "Workstation 42",
                recoverable = true,
                occurredAtEpochMillis = 2,
            ),
            SessionCoordinatorIssue(
                id = "provider-synchronization",
                kind = SessionCoordinatorIssueKind.PROVIDER_SYNCHRONIZATION,
                connection = null,
                agentProviderId = null,
                connectionLabel = "Workstation 42",
                agentProviderLabel = "Codex",
                recoverable = true,
                occurredAtEpochMillis = 3,
            ),
            SessionCoordinatorIssue(
                id = "session-persistence",
                kind = SessionCoordinatorIssueKind.SESSION_PERSISTENCE,
                connection = null,
                agentProviderId = null,
                agentProviderLabel = "Codex",
                recoverable = false,
                occurredAtEpochMillis = 4,
            ),
        )

        val mapped = SessionHubUiMapper.map(
            coordinator = SessionCoordinatorSnapshot(
                issues = issues.associateBy(SessionCoordinatorIssue::id),
            ),
            sessions = SessionHubSnapshot(),
            connectionProviders = emptyList(),
            selectedSessionKey = null,
            operationError = null,
            busyConnectionKeys = emptySet(),
        )

        assertEquals(
            listOf(
                UiMessage.Localized(
                    R.string.session_issue_session_persistence,
                    listOf("Codex"),
                ),
                UiMessage.Localized(
                    R.string.session_issue_provider_synchronization,
                    listOf("Codex", "Workstation 42"),
                ),
                UiMessage.Localized(
                    R.string.session_issue_connection_setup,
                    listOf("Workstation 42"),
                ),
                UiMessage.Localized(
                    R.string.session_issue_profile_discovery,
                    listOf("Local"),
                ),
            ),
            mapped.issues.map(CoordinatorIssueUiModel::message),
        )
        assertFalse(mapped.issues.first().recoverable)
        assertTrue(mapped.issues.drop(1).all(CoordinatorIssueUiModel::recoverable))
    }

    @Test
    fun activitySummariesMapGeneratedKindsAndVerbatimContent() {
        val connectionProviderId = ConnectionProviderId("local.device")
        val profileId = ConnectionProfileId("this-device")
        val sessionLocator = localizedLocator(connectionProviderId, profileId)
        val cases = listOf(
            SessionActivitySummary.Generated(SessionActivitySummaryKind.NEW_AGENT_OUTPUT) to
                UiMessage.Localized(R.string.session_activity_summary_new_agent_output),
            SessionActivitySummary.Generated(SessionActivitySummaryKind.TOOL_FAILED) to
                UiMessage.Localized(R.string.session_activity_summary_tool_failed),
            SessionActivitySummary.Generated(
                SessionActivitySummaryKind.NAMED_TOOL_FAILED,
                "Shell",
            ) to UiMessage.Localized(
                R.string.session_activity_summary_named_tool_failed,
                listOf("Shell"),
            ),
            SessionActivitySummary.Generated(SessionActivitySummaryKind.AGENT_TURN_COMPLETED) to
                UiMessage.Localized(R.string.session_activity_summary_agent_turn_completed),
            SessionActivitySummary.Generated(SessionActivitySummaryKind.AGENT_TURN_FAILED) to
                UiMessage.Localized(R.string.session_activity_summary_agent_turn_failed),
            SessionActivitySummary.Generated(SessionActivitySummaryKind.AGENT_PROVIDER_FAILED) to
                UiMessage.Localized(R.string.session_activity_summary_agent_provider_failed),
            SessionActivitySummary.Generated(
                SessionActivitySummaryKind.AGENT_QUESTION_REQUIRES_ANSWER,
            ) to UiMessage.Localized(
                R.string.session_activity_summary_agent_question_requires_answer,
            ),
            SessionActivitySummary.Generated(SessionActivitySummaryKind.AGENT_APPROVAL_REQUIRED) to
                UiMessage.Localized(R.string.session_activity_summary_agent_approval_required),
            SessionActivitySummary.Generated(
                SessionActivitySummaryKind.CONNECTION_RECONNECTED,
                "Workstation 42",
            ) to UiMessage.Localized(
                R.string.session_activity_summary_connection_reconnected,
                listOf("Workstation 42"),
            ),
            SessionActivitySummary.Verbatim("Provider-owned detail") to
                UiMessage.Verbatim("Provider-owned detail"),
        )
        val snapshot = SessionHubSnapshot(
            sessions = listOf(localizedRecord(sessionLocator, "Activity messages", "This device")),
            activities = cases.mapIndexed { index, (summary, _) ->
                SessionActivity(
                    id = "activity-$index",
                    locator = sessionLocator,
                    type = SessionActivityType.NEW_OUTPUT,
                    summary = summary,
                    eventAnchorId = null,
                    occurredAtEpochMillis = index.toLong(),
                )
            },
        )

        val mapped = SessionHubUiMapper.map(
            coordinator = SessionCoordinatorSnapshot(
                profiles = listOf(localizedProfile(connectionProviderId, profileId, "This device")),
            ),
            sessions = snapshot,
            connectionProviders = listOf(localizedDescriptor(connectionProviderId, "Local")),
            selectedSessionKey = sessionLocator.stableUiKey,
            operationError = null,
            busyConnectionKeys = emptySet(),
        )

        assertEquals(
            cases.map { it.second }.asReversed(),
            mapped.selectedSession?.activities?.map { it.summary },
        )
    }

    @Test
    fun generatedActionAndQuestionPresentationMapsToLocalizedMessages() {
        val connectionProviderId = ConnectionProviderId("local.device")
        val profileId = ConnectionProfileId("this-device")
        val sessionLocator = localizedLocator(connectionProviderId, profileId)
        val action = SessionActionRequest(
            id = "generated-action",
            providerApprovalId = "provider-action",
            locator = sessionLocator,
            turnId = null,
            type = AgentApprovalType.USER_INPUT,
            title = SessionPresentationText.Generated(
                SessionPresentationTextKind.ACTION_REVIEW_REQUIRED,
            ),
            description = null,
            command = null,
            workingDirectory = null,
            questions = listOf(
                SessionQuestion(
                    id = "generated-question",
                    providerQuestionId = "provider-generated",
                    header = null,
                    prompt = SessionPresentationText.Generated(
                        SessionPresentationTextKind.AGENT_QUESTION,
                    ),
                ),
                SessionQuestion(
                    id = "verbatim-question",
                    providerQuestionId = "provider-verbatim",
                    header = "Provider header",
                    prompt = SessionPresentationText.Verbatim("Provider-owned prompt"),
                ),
            ),
            availableDecisions = setOf(AgentApprovalDecision.SUBMIT),
            riskReasons = emptySet(),
            receivedAtEpochMillis = 3,
        )

        val mapped = SessionHubUiMapper.map(
            coordinator = SessionCoordinatorSnapshot(
                profiles = listOf(localizedProfile(connectionProviderId, profileId, "This device")),
            ),
            sessions = SessionHubSnapshot(
                sessions = listOf(localizedRecord(sessionLocator, "Generated copy", "This device")),
                actionRequests = listOf(action),
            ),
            connectionProviders = listOf(localizedDescriptor(connectionProviderId, "Local")),
            selectedSessionKey = sessionLocator.stableUiKey,
            operationError = null,
            busyConnectionKeys = emptySet(),
        )

        val actionUi = mapped.attentionActions.single()
        assertEquals(
            UiMessage.Localized(R.string.session_action_title_review_required),
            actionUi.title,
        )
        assertEquals(
            UiMessage.Localized(R.string.session_question_prompt_fallback),
            actionUi.questions.first().prompt,
        )
        assertEquals(
            UiMessage.Verbatim("Provider-owned prompt"),
            actionUi.questions.last().prompt,
        )
    }

    @Test
    fun composerActionsFollowEndpointCapabilitiesAndDurableSessionState() {
        val connectionProviderId = ConnectionProviderId("local.device")
        val profileId = ConnectionProfileId("this-device")
        val sessionLocator = localizedLocator(connectionProviderId, profileId)
        val connectionKey = SessionConnectionKey(connectionProviderId, profileId)
        val endpointKey = AgentEndpointKey(connectionKey, sessionLocator.agentProviderId)
        val draft = SessionDraft(
            text = "Check the focused tests",
            selectionStart = 2,
            selectionEnd = 7,
            updatedAtEpochMillis = 10,
        )

        fun composer(
            state: AgentSessionState,
            capabilities: Set<AgentCapability>,
            canAcceptInput: Boolean = true,
            busy: Boolean = false,
            endpointReady: Boolean = true,
        ): SessionComposerUiModel {
            val agentDescriptor = AgentProviderDescriptor(
                id = sessionLocator.agentProviderId,
                displayName = "Codex",
                providerVersion = "1.0",
                capabilities = capabilities,
            )
            val sessions = SessionHubSnapshot(
                sessions = listOf(
                    localizedRecord(
                        locator = sessionLocator,
                        title = "Provider-aware interaction",
                        connectionLabel = "This device",
                        state = state,
                        canAcceptInput = canAcceptInput,
                    ),
                ),
                drafts = mapOf(sessionLocator to draft),
            )
            return checkNotNull(
                SessionHubUiMapper.map(
                    coordinator = SessionCoordinatorSnapshot(
                        profiles = listOf(localizedProfile(connectionProviderId, profileId, "This device")),
                        agentEndpoints = if (endpointReady) {
                            mapOf(
                                endpointKey to AgentEndpointStatus(
                                    key = endpointKey,
                                    descriptor = agentDescriptor,
                                    phase = AgentEndpointPhase.READY,
                                    updatedAtEpochMillis = 20,
                                ),
                            )
                        } else {
                            emptyMap()
                        },
                    ),
                    sessions = sessions,
                    connectionProviders = listOf(localizedDescriptor(connectionProviderId, "Local")),
                    selectedSessionKey = sessionLocator.stableUiKey,
                    operationError = null,
                    busyConnectionKeys = emptySet(),
                    busySessionKeys = if (busy) setOf(sessionLocator.stableUiKey) else emptySet(),
                ).selectedSession?.composer,
            )
        }

        val idle = composer(AgentSessionState.IDLE, emptySet())
        assertEquals("Check the focused tests", idle.draftText)
        assertEquals(2, idle.selectionStart)
        assertEquals(7, idle.selectionEnd)
        assertEquals(SessionSubmitMode.SEND, idle.submitMode)
        assertTrue(idle.canSubmit)

        val running = composer(
            AgentSessionState.RUNNING,
            setOf(AgentCapability.ACTIVE_TURN_STEERING, AgentCapability.TURN_INTERRUPT),
        )
        assertEquals(SessionSubmitMode.STEER, running.submitMode)
        assertTrue(running.canSubmit)
        assertTrue(running.canInterrupt)

        val saved = composer(AgentSessionState.NOT_LOADED, setOf(AgentCapability.SESSION_RESUME))
        assertTrue(saved.canResume)
        assertFalse(saved.canSubmit)
        assertEquals(
            UiMessage.Localized(R.string.session_composer_status_resume),
            saved.statusMessage,
        )

        val unsupportedSteering = composer(AgentSessionState.RUNNING, emptySet())
        assertFalse(unsupportedSteering.canSubmit)
        assertEquals(
            UiMessage.Localized(R.string.session_composer_status_steering_unsupported),
            unsupportedSteering.statusMessage,
        )

        val busy = composer(AgentSessionState.IDLE, emptySet(), busy = true)
        assertTrue(busy.isBusy)
        assertFalse(busy.canSubmit)
        assertEquals(
            UiMessage.Localized(R.string.session_composer_status_applying),
            busy.statusMessage,
        )

        val disconnected = composer(
            AgentSessionState.IDLE,
            emptySet(),
            endpointReady = false,
        )
        assertEquals(
            UiMessage.Localized(
                R.string.session_composer_status_connect_draft,
                listOf("This device"),
            ),
            disconnected.statusMessage,
        )

        val waiting = composer(
            AgentSessionState.WAITING_FOR_APPROVAL,
            setOf(AgentCapability.TURN_INTERRUPT),
        )
        assertTrue(waiting.canInterrupt)
        assertEquals(
            UiMessage.Localized(R.string.session_composer_status_pending_action),
            waiting.statusMessage,
        )

        val unsupportedResume = composer(AgentSessionState.NOT_LOADED, emptySet())
        assertFalse(unsupportedResume.canResume)
        assertEquals(
            UiMessage.Localized(R.string.session_composer_status_resume_unsupported),
            unsupportedResume.statusMessage,
        )

        val readOnly = composer(
            AgentSessionState.IDLE,
            emptySet(),
            canAcceptInput = false,
        )
        assertFalse(readOnly.canSubmit)
        assertEquals(
            UiMessage.Localized(R.string.session_composer_status_read_only),
            readOnly.statusMessage,
        )
    }
}

private fun localizedLocator(
    connectionProviderId: ConnectionProviderId,
    profileId: ConnectionProfileId,
) = SessionLocator(
    connectionProviderId = connectionProviderId,
    connectionProfileId = profileId,
    agentProviderId = AgentProviderId("agent.codex"),
    agentSessionId = AgentSessionId("localized-agent-session"),
)

private fun localizedRecord(
    locator: SessionLocator,
    title: String?,
    connectionLabel: String,
    state: AgentSessionState = AgentSessionState.IDLE,
    canAcceptInput: Boolean = false,
) = SessionRecord(
    observation = SessionObservation(
        locator = locator,
        connectionLabel = connectionLabel,
        connectionTarget = connectionLabel,
        projectPath = "/workspace",
        agentProviderLabel = "Codex",
        title = title,
        preview = "Session preview",
        agentState = state,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
        metadata = mapOf("can_accept_input" to canAcceptInput.toString()),
    ),
)

private fun localizedProfile(
    providerId: ConnectionProviderId,
    profileId: ConnectionProfileId,
    label: String,
) = ConnectionProfileSummary(
    id = profileId,
    providerId = providerId,
    label = label,
    target = label,
    authenticationLabel = null,
)

private fun localizedDescriptor(
    id: ConnectionProviderId,
    name: String,
) = ConnectionProviderDescriptor(
    id = id,
    displayName = name,
    providerVersion = "1.0",
    capabilities = setOf(ConnectionCapability.MULTIPLEXED_PROCESSES),
)
