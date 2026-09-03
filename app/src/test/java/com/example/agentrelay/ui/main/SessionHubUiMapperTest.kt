package com.example.agentrelay.ui.main

import dev.agentrelay.connection.api.ConnectionCapability
import dev.agentrelay.connection.api.ConnectionChallengeId
import dev.agentrelay.connection.api.ConnectionDisconnectReason
import dev.agentrelay.connection.api.ConnectionFailure
import dev.agentrelay.connection.api.ConnectionFailureCategory
import dev.agentrelay.connection.api.ConnectionIdentityChallenge
import dev.agentrelay.connection.api.ConnectionIdentityDisposition
import dev.agentrelay.connection.api.ConnectionPhase
import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProfileSummary
import dev.agentrelay.connection.api.ConnectionProviderDescriptor
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.connection.api.ConnectionState
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentApprovalType
import dev.agentrelay.provider.api.AgentCapability
import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentProviderDescriptor
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.AgentTranscriptRole
import dev.agentrelay.session.api.CachedTranscriptEntry
import dev.agentrelay.session.api.SessionActionRequest
import dev.agentrelay.session.api.SessionActionRisk
import dev.agentrelay.session.api.SessionActionState
import dev.agentrelay.session.api.SessionActivity
import dev.agentrelay.session.api.SessionActivityType
import dev.agentrelay.session.api.SessionDraft
import dev.agentrelay.session.api.SessionArtifact
import dev.agentrelay.session.api.SessionArtifactAvailability
import dev.agentrelay.session.api.SessionHubSnapshot
import dev.agentrelay.session.api.SessionLocator
import dev.agentrelay.session.api.SessionObservation
import dev.agentrelay.session.api.SessionQuestion
import dev.agentrelay.session.api.SessionQuestionOption
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class SessionHubUiMapperTest {
    @Test
    fun providerScopedSessionIdentitiesDoNotCollide() {
        val localProvider = ConnectionProviderId("local.device")
        val sshProvider = ConnectionProviderId("ssh.secure-shell")
        val profileId = ConnectionProfileId("same-profile")
        val localLocator = locator(localProvider, profileId)
        val sshLocator = locator(sshProvider, profileId)
        val sessionSnapshot = SessionHubSnapshot(
            sessions = listOf(
                record(localLocator, "Local session", "This device"),
                record(sshLocator, "SSH session", "Trusted server"),
            ),
            activities = listOf(
                SessionActivity(
                    id = "approval-1",
                    locator = sshLocator,
                    type = SessionActivityType.APPROVAL_REQUIRED,
                    summary = "Review the remote command",
                    eventAnchorId = "approval",
                    occurredAtEpochMillis = 100,
                ),
            ),
            transcripts = mapOf(
                sshLocator to listOf(
                    CachedTranscriptEntry(
                        id = "message-1",
                        turnId = null,
                        role = AgentTranscriptRole.AGENT,
                        channel = AgentMessageChannel.FINAL,
                        text = "x".repeat(40_000),
                        createdAtEpochMillis = 90,
                    ),
                ),
            ),
        )
        val coordinator = SessionCoordinatorSnapshot(
            profiles = listOf(
                profile(localProvider, profileId, "This device"),
                profile(sshProvider, profileId, "Trusted server"),
            ),
        )

        val mapped = SessionHubUiMapper.map(
            coordinator = coordinator,
            sessions = sessionSnapshot,
            connectionProviders = listOf(
                descriptor(localProvider, "Local"),
                descriptor(
                    sshProvider,
                    "Secure Shell",
                    setOf(
                        ConnectionCapability.MULTIPLEXED_PROCESSES,
                        ConnectionCapability.PROFILE_MANAGEMENT,
                    ),
                ),
            ),
            selectedSessionKey = sshLocator.stableUiKey,
            operationError = null,
            busyConnectionKeys = emptySet(),
        )

        assertEquals(2, mapped.sessions.size)
        assertNotEquals(mapped.sessions[0].stableKey, mapped.sessions[1].stableKey)
        assertEquals(64, mapped.sessions[0].stableKey.length)
        assertEquals("SSH session", mapped.selectedSession?.session?.title)
        assertEquals(1, mapped.selectedSession?.session?.requiresActionCount)
        assertEquals(32_000, mapped.selectedSession?.transcript?.single()?.text?.length)
        assertTrue(mapped.selectedSession?.transcript?.single()?.wasTruncated == true)
        assertEquals("Final answer", mapped.selectedSession?.transcript?.single()?.roleLabel)
        assertEquals(TimelineEntryKind.AGENT_FINAL, mapped.selectedSession?.transcript?.single()?.kind)
        assertFalse(mapped.sessions.single { it.title == "Local session" }.stableKey == sshLocator.stableUiKey)
        assertEquals(listOf("Secure Shell"), mapped.manageableConnectionProviders.map { it.name })
        assertFalse(mapped.connections.single { it.providerName == "Local" }.canEdit)
        assertTrue(mapped.connections.single { it.providerName == "Secure Shell" }.canEdit)
    }

    @Test
    fun transcriptRolesAndChannelsMapToDistinctTimelineKinds() {
        val connectionProviderId = ConnectionProviderId("local.device")
        val profileId = ConnectionProfileId("this-device")
        val sessionLocator = locator(connectionProviderId, profileId)
        val cases = listOf(
            Triple(
                AgentTranscriptRole.USER,
                null,
                TimelineEntryKind.USER_MESSAGE to "You",
            ),
            Triple(
                AgentTranscriptRole.TOOL,
                null,
                TimelineEntryKind.TOOL to "Tool",
            ),
            Triple(
                AgentTranscriptRole.SYSTEM,
                null,
                TimelineEntryKind.SYSTEM to "System",
            ),
            Triple(
                AgentTranscriptRole.AGENT,
                AgentMessageChannel.COMMENTARY,
                TimelineEntryKind.AGENT_COMMENTARY to "Agent commentary",
            ),
            Triple(
                AgentTranscriptRole.AGENT,
                AgentMessageChannel.FINAL,
                TimelineEntryKind.AGENT_FINAL to "Final answer",
            ),
            Triple(
                AgentTranscriptRole.AGENT,
                AgentMessageChannel.PLAN,
                TimelineEntryKind.PLAN to "Plan",
            ),
            Triple(
                AgentTranscriptRole.AGENT,
                AgentMessageChannel.REASONING_SUMMARY,
                TimelineEntryKind.REASONING_SUMMARY to "Reasoning summary",
            ),
            Triple(
                AgentTranscriptRole.AGENT,
                AgentMessageChannel.SYSTEM,
                TimelineEntryKind.SYSTEM to "System",
            ),
        )
        val snapshot = SessionHubSnapshot(
            sessions = listOf(record(sessionLocator, "Typed timeline", "This device")),
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
                profiles = listOf(profile(connectionProviderId, profileId, "This device")),
            ),
            sessions = snapshot,
            connectionProviders = listOf(descriptor(connectionProviderId, "Local")),
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
    fun composerActionsFollowEndpointCapabilitiesAndDurableSessionState() {
        val connectionProviderId = ConnectionProviderId("local.device")
        val profileId = ConnectionProfileId("this-device")
        val sessionLocator = locator(connectionProviderId, profileId)
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
        ): SessionComposerUiModel {
            val agentDescriptor = AgentProviderDescriptor(
                id = sessionLocator.agentProviderId,
                displayName = "Codex",
                providerVersion = "1.0",
                capabilities = capabilities,
            )
            val sessions = SessionHubSnapshot(
                sessions = listOf(
                    record(
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
                        profiles = listOf(profile(connectionProviderId, profileId, "This device")),
                        agentEndpoints = mapOf(
                            endpointKey to AgentEndpointStatus(
                                key = endpointKey,
                                descriptor = agentDescriptor,
                                phase = AgentEndpointPhase.READY,
                                updatedAtEpochMillis = 20,
                            ),
                        ),
                    ),
                    sessions = sessions,
                    connectionProviders = listOf(descriptor(connectionProviderId, "Local")),
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
        assertTrue(saved.statusMessage?.contains("Resume") == true)

        val unsupportedSteering = composer(AgentSessionState.RUNNING, emptySet())
        assertFalse(unsupportedSteering.canSubmit)
        assertTrue(unsupportedSteering.statusMessage?.contains("cannot steer") == true)

        val busy = composer(AgentSessionState.IDLE, emptySet(), busy = true)
        assertTrue(busy.isBusy)
        assertFalse(busy.canSubmit)
        val waiting = composer(
            AgentSessionState.WAITING_FOR_APPROVAL,
            setOf(AgentCapability.TURN_INTERRUPT),
        )
        assertTrue(waiting.canInterrupt)
        assertTrue(waiting.statusMessage?.contains("pending approval") == true)

        val unsupportedResume = composer(AgentSessionState.NOT_LOADED, emptySet())
        assertFalse(unsupportedResume.canResume)
        assertTrue(unsupportedResume.statusMessage?.contains("cannot safely resume") == true)

        val readOnly = composer(
            AgentSessionState.IDLE,
            emptySet(),
            canAcceptInput = false,
        )
        assertFalse(readOnly.canSubmit)
        assertTrue(readOnly.statusMessage?.contains("read-only") == true)
    }

    @Test
    fun launchersAndApprovalUiPreserveScopeWithoutExposingProviderRequestIds() {
        val connectionProviderId = ConnectionProviderId("ssh.secure-shell")
        val profileId = ConnectionProfileId("trusted-server")
        val sessionLocator = locator(connectionProviderId, profileId)
        val connectionKey = SessionConnectionKey(connectionProviderId, profileId)
        val endpointKey = AgentEndpointKey(connectionKey, sessionLocator.agentProviderId)
        val pending = SessionActionRequest(
            id = "stable-action-key",
            providerApprovalId = "raw-private-provider-approval-id",
            locator = sessionLocator,
            turnId = "turn-1",
            type = AgentApprovalType.COMMAND,
            title = "Review workspace cleanup",
            description = "The provider wants to remove generated files.",
            command = "remove generated output",
            workingDirectory = "/workspace",
            questions = listOf(
                SessionQuestion(
                    id = "stable-question-key",
                    providerQuestionId = "raw-private-provider-question-id",
                    header = "Scope",
                    prompt = "Which output should be removed?",
                    options = listOf(
                        SessionQuestionOption(
                            label = "Generated output",
                            description = "Keep source files.",
                        ),
                    ),
                    allowsOther = true,
                ),
            ),
            availableDecisions = setOf(
                AgentApprovalDecision.SUBMIT,
                AgentApprovalDecision.CANCEL,
            ),
            riskReasons = setOf(
                SessionActionRisk.DESTRUCTIVE_COMMAND,
                SessionActionRisk.BROAD_FILESYSTEM_ACCESS,
            ),
            receivedAtEpochMillis = 30,
        )
        val resolved = pending.copy(
            id = "resolved-action-key",
            providerApprovalId = "raw-resolved-provider-id",
            title = "Previous request",
            questions = emptyList(),
            availableDecisions = setOf(AgentApprovalDecision.DECLINE),
            riskReasons = emptySet(),
            receivedAtEpochMillis = 20,
            state = SessionActionState.RESOLVED,
            decision = AgentApprovalDecision.DECLINE,
            decisionAtEpochMillis = 21,
        )
        val mapped = SessionHubUiMapper.map(
            coordinator = SessionCoordinatorSnapshot(
                profiles = listOf(profile(connectionProviderId, profileId, "Trusted server")),
                agentEndpoints = mapOf(
                    endpointKey to AgentEndpointStatus(
                        key = endpointKey,
                        descriptor = AgentProviderDescriptor(
                            id = sessionLocator.agentProviderId,
                            displayName = "Codex",
                            providerVersion = "1.0",
                            capabilities = setOf(
                                AgentCapability.SESSION_START,
                                AgentCapability.APPROVALS,
                            ),
                        ),
                        phase = AgentEndpointPhase.READY,
                        updatedAtEpochMillis = 40,
                    ),
                ),
            ),
            sessions = SessionHubSnapshot(
                sessions = listOf(record(sessionLocator, "Sensitive session", "Trusted server")),
                actionRequests = listOf(pending, resolved),
            ),
            connectionProviders = listOf(descriptor(connectionProviderId, "Secure Shell")),
            selectedSessionKey = sessionLocator.stableUiKey,
            operationError = null,
            busyConnectionKeys = emptySet(),
            busyActionKeys = setOf(pending.id),
        )

        val launcher = mapped.sessionLaunchers.single()
        assertEquals(64, launcher.stableKey.length)
        assertEquals("Secure Shell", launcher.connectionProviderName)
        assertEquals("Trusted server", launcher.connectionLabel)
        assertEquals("Codex", launcher.agentProviderLabel)
        assertEquals("/workspace", launcher.suggestedWorkingDirectory)

        val attention = mapped.attentionActions.single()
        assertEquals(pending.id, attention.stableKey)
        assertEquals("/workspace", attention.scope)
        assertEquals("remove generated output", attention.command)
        assertEquals(
            listOf(AgentApprovalDecision.SUBMIT, AgentApprovalDecision.CANCEL),
            attention.decisions.map { it.decision },
        )
        assertTrue(attention.decisions.first().requiresConfirmation)
        assertTrue(attention.isBusy)
        assertEquals(
            listOf(
                SessionActionRisk.DESTRUCTIVE_COMMAND,
                SessionActionRisk.BROAD_FILESYSTEM_ACCESS,
            ),
            attention.risks,
        )
        assertEquals("stable-question-key", attention.questions.single().stableKey)
        assertEquals(2, mapped.selectedSession?.actions?.size)
        assertEquals(
            SessionActionState.RESOLVED,
            mapped.selectedSession?.actions?.single { it.stableKey == resolved.id }?.state,
        )
        assertFalse(mapped.toString().contains("raw-private-provider-approval-id"))
        assertFalse(mapped.toString().contains("raw-private-provider-question-id"))
        assertFalse(mapped.toString().contains("raw-resolved-provider-id"))
    }

    @Test
    fun changedServerIdentityIsMappedAsAnExplicitReplacementDecision() {
        val providerId = ConnectionProviderId("ssh.secure-shell")
        val profileId = ConnectionProfileId("server")
        val key = SessionConnectionKey(providerId, profileId)
        val challenge = ConnectionIdentityChallenge(
            id = ConnectionChallengeId("challenge-1"),
            endpoint = "redacted.example",
            algorithm = "ssh-ed25519",
            sha256Fingerprint = "SHA256:abcdefghijklmnopqrstuv",
            disposition = ConnectionIdentityDisposition.CHANGED,
            previouslyTrustedFingerprints = listOf("SHA256:zyxwvutsrqponmlkjihgfe"),
        )

        val mapped = SessionHubUiMapper.map(
            coordinator = SessionCoordinatorSnapshot(
                profiles = listOf(profile(providerId, profileId, "Trusted server")),
                connectionStates = mapOf(
                    key to ConnectionState.AwaitingIdentityTrust(
                        challenge = challenge,
                        atEpochMillis = 200,
                    ),
                ),
            ),
            sessions = SessionHubSnapshot(),
            connectionProviders = listOf(descriptor(providerId, "Secure Shell")),
            selectedSessionKey = null,
            operationError = null,
            busyConnectionKeys = setOf(key.stableUiKey),
        )

        val connection = mapped.connections.single()
        assertEquals(ConnectionStatus.IDENTITY_REVIEW, connection.status)
        assertTrue(connection.identityChallenge?.isChangedIdentity == true)
        assertEquals(challenge.previouslyTrustedFingerprints, connection.identityChallenge?.previousFingerprints)
        assertTrue(connection.isBusy)
        assertTrue(connection.canDisconnect)
        assertFalse(connection.canConnect)
    }

    @Test
    fun connectionStatesAndFallbackLabelsRemainActionable() {
        val providerId = ConnectionProviderId("test.provider")
        val failure = ConnectionFailure(
            category = ConnectionFailureCategory.NETWORK,
            code = "TEST_FAILURE",
            actionableMessage = "Try the connection again.",
            recoverable = true,
        )
        val statesByProfileId = linkedMapOf(
            "user" to disconnected(ConnectionDisconnectReason.USER_REQUESTED),
            "authentication" to disconnected(ConnectionDisconnectReason.AUTHENTICATION_FAILED),
            "network" to disconnected(ConnectionDisconnectReason.NETWORK_LOST),
            "identity" to disconnected(ConnectionDisconnectReason.SERVER_IDENTITY_REJECTED),
            "credential" to disconnected(ConnectionDisconnectReason.CREDENTIAL_UNAVAILABLE),
            "background" to disconnected(ConnectionDisconnectReason.BACKGROUND_SUSPENDED),
            "retry" to disconnected(ConnectionDisconnectReason.RETRY_LIMIT_REACHED),
            "stopped" to disconnected(ConnectionDisconnectReason.PROVIDER_STOPPED),
            "connecting" to ConnectionState.Connecting(
                attempt = 1,
                phase = ConnectionPhase.OPENING_TRANSPORT,
                startedAtEpochMillis = 1,
            ),
            "connected" to ConnectionState.Connected(
                connectedAtEpochMillis = 1,
                lastHeartbeatAtEpochMillis = null,
                lastLatency = null,
            ),
            "reconnecting" to ConnectionState.Reconnecting(
                attempt = 2,
                delay = 1.seconds,
                retryAtEpochMillis = 2,
                lastFailure = failure,
            ),
            "failed" to ConnectionState.Failed(
                failure = failure,
                atEpochMillis = 2,
            ),
        )
        val profiles = statesByProfileId.keys.map { id ->
            profile(providerId, ConnectionProfileId(id), id)
        }
        val states = statesByProfileId.mapKeys { (id, _) ->
            SessionConnectionKey(providerId, ConnectionProfileId(id))
        }
        val sessionLocator = locator(providerId, ConnectionProfileId("session"))
        val fallbackSession = record(sessionLocator, null, "Fallback target")
        val mapped = SessionHubUiMapper.map(
            coordinator = SessionCoordinatorSnapshot(
                profiles = profiles,
                connectionStates = states,
                issues = mapOf(
                    "old" to issue("old", "Older issue", 1),
                    "new" to issue("new", "Newer issue", 2),
                ),
                isRefreshingProfiles = true,
            ),
            sessions = SessionHubSnapshot(sessions = listOf(fallbackSession)),
            connectionProviders = emptyList(),
            selectedSessionKey = sessionLocator.stableUiKey,
            operationError = "A safe operation failed.",
            busyConnectionKeys = setOf(
                SessionConnectionKey(
                    providerId,
                    ConnectionProfileId("connecting"),
                ).stableUiKey,
            ),
        )
        val connections = mapped.connections.associateBy(ConnectionUiModel::label)

        assertEquals("Disconnected by you", connections.getValue("user").statusDetail)
        assertEquals("Authentication failed", connections.getValue("authentication").statusDetail)
        assertEquals("Network connection lost", connections.getValue("network").statusDetail)
        assertEquals("Server identity rejected", connections.getValue("identity").statusDetail)
        assertEquals("Credential unavailable", connections.getValue("credential").statusDetail)
        assertEquals("Paused in the background", connections.getValue("background").statusDetail)
        assertEquals("Automatic retry limit reached", connections.getValue("retry").statusDetail)
        assertEquals("Connection provider stopped", connections.getValue("stopped").statusDetail)
        assertEquals(ConnectionStatus.CONNECTING, connections.getValue("connecting").status)
        assertEquals("Opening transport", connections.getValue("connecting").statusDetail)
        assertTrue(connections.getValue("connecting").isBusy)
        assertEquals(ConnectionStatus.ONLINE, connections.getValue("connected").status)
        assertEquals(ConnectionStatus.RECONNECTING, connections.getValue("reconnecting").status)
        assertEquals("Try the connection again.", connections.getValue("reconnecting").statusDetail)
        assertEquals(ConnectionStatus.FAILED, connections.getValue("failed").status)
        assertEquals("Try the connection again.", connections.getValue("failed").statusDetail)
        assertEquals("test.provider", connections.getValue("connected").providerName)
        assertEquals("Codex session", mapped.sessions.single().title)
        assertEquals("test.provider", mapped.selectedSession?.session?.connectionProviderName)
        assertEquals(listOf("new", "old"), mapped.issues.map(CoordinatorIssueUiModel::id))
        assertEquals("A safe operation failed.", mapped.operationError)
        assertTrue(mapped.isRefreshingProfiles)
    }

    @Test
    fun artifactUiExposesOnlyWorkspaceRelativePathsAndCapabilityDrivenActions() {
        val providerId = ConnectionProviderId("ssh.secure-shell")
        val profileId = ConnectionProfileId("test-profile")
        val sessionLocator = locator(providerId, profileId)
        val connectionKey = SessionConnectionKey(providerId, profileId)
        val endpointKey = AgentEndpointKey(connectionKey, sessionLocator.agentProviderId)
        val downloadable = SessionArtifact(
            id = "downloadable",
            locator = sessionLocator,
            providerPath = "/workspace/reports/result.txt",
            relativePath = "reports/result.txt",
            oldProviderPath = null,
            oldRelativePath = null,
            kind = AgentFileChangeKind.MODIFIED,
            turnId = "turn-1",
            availability = SessionArtifactAvailability.DOWNLOADABLE,
            observedAtEpochMillis = 20,
        )
        val outside = SessionArtifact(
            id = "outside",
            locator = sessionLocator,
            providerPath = "/private/provider/details/secret.txt",
            relativePath = null,
            oldProviderPath = null,
            oldRelativePath = null,
            kind = AgentFileChangeKind.ADDED,
            turnId = null,
            availability = SessionArtifactAvailability.OUTSIDE_WORKSPACE,
            observedAtEpochMillis = 10,
        )
        val mapped = SessionHubUiMapper.map(
            coordinator = SessionCoordinatorSnapshot(
                profiles = listOf(profile(providerId, profileId, "Test server")),
                agentEndpoints = mapOf(
                    endpointKey to AgentEndpointStatus(
                        key = endpointKey,
                        descriptor = AgentProviderDescriptor(
                            id = sessionLocator.agentProviderId,
                            displayName = "Codex",
                            providerVersion = "1.0",
                            capabilities = setOf(AgentCapability.FILE_CHANGES),
                        ),
                        phase = AgentEndpointPhase.READY,
                        fileAccessAvailable = true,
                        updatedAtEpochMillis = 30,
                    ),
                ),
            ),
            sessions = SessionHubSnapshot(
                sessions = listOf(record(sessionLocator, "Artifacts", "Test server")),
                artifacts = listOf(downloadable, outside),
            ),
            connectionProviders = listOf(descriptor(providerId, "Secure Shell")),
            selectedSessionKey = sessionLocator.stableUiKey,
            operationError = null,
            busyConnectionKeys = emptySet(),
        )

        val artifacts = checkNotNull(mapped.selectedSession).artifacts
        assertEquals(
            listOf("reports/result.txt", "File outside workspace"),
            artifacts.map(SessionArtifactUiModel::displayPath),
        )
        assertEquals(
            listOf(
                SessionArtifactAvailabilityStatus.READY,
                SessionArtifactAvailabilityStatus.OUTSIDE_WORKSPACE,
            ),
            artifacts.map(SessionArtifactUiModel::availabilityStatus),
        )
        assertTrue(artifacts.first().canSave)
        assertFalse(artifacts.last().isDownloadable)
        assertFalse(artifacts.any { it.displayPath.contains("private") })
        assertTrue(checkNotNull(mapped.selectedSession).canRefreshArtifacts)

        val withoutFileAccess = SessionArtifactUiMapper.map(
            artifact = downloadable,
            transfer = null,
            providerReady = true,
            fileAccessAvailable = false,
        )
        assertFalse(withoutFileAccess.canSave)
        assertEquals(
            SessionArtifactAvailabilityStatus.UNSUPPORTED,
            withoutFileAccess.availabilityStatus,
        )
    }
}

private fun disconnected(reason: ConnectionDisconnectReason) = ConnectionState.Disconnected(
    reason = reason,
    atEpochMillis = 1,
)

private fun issue(
    id: String,
    message: String,
    occurredAtEpochMillis: Long,
) = SessionCoordinatorIssue(
    id = id,
    kind = SessionCoordinatorIssueKind.CONNECTION_SETUP,
    connection = null,
    agentProviderId = null,
    actionableMessage = message,
    recoverable = true,
    occurredAtEpochMillis = occurredAtEpochMillis,
)

private fun locator(
    connectionProviderId: ConnectionProviderId,
    profileId: ConnectionProfileId,
) = SessionLocator(
    connectionProviderId = connectionProviderId,
    connectionProfileId = profileId,
    agentProviderId = AgentProviderId("agent.codex"),
    agentSessionId = AgentSessionId("same-agent-session"),
)

private fun record(
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

private fun profile(
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

private fun descriptor(
    id: ConnectionProviderId,
    name: String,
    capabilities: Set<ConnectionCapability> = setOf(ConnectionCapability.MULTIPLEXED_PROCESSES),
) = ConnectionProviderDescriptor(
    id = id,
    displayName = name,
    providerVersion = "1.0",
    capabilities = capabilities,
)
