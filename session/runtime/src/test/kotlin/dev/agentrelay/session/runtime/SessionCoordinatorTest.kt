/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.runtime

import dev.agentrelay.connection.api.ConnectionFailureMessage
import dev.agentrelay.connection.api.ConnectionFailureMessageKind
import dev.agentrelay.connection.api.ConnectionProviderRegistry
import dev.agentrelay.connection.api.ConnectionState
import dev.agentrelay.provider.api.AgentApproval
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentApprovalId
import dev.agentrelay.provider.api.AgentApprovalType
import dev.agentrelay.provider.api.AgentEvent
import dev.agentrelay.provider.api.AgentMessageChannel
import dev.agentrelay.provider.api.AgentProviderRegistry
import dev.agentrelay.provider.api.AgentQuestion
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.StartSessionOptions
import dev.agentrelay.session.api.InMemorySessionHubStore
import dev.agentrelay.session.api.PersistentSessionHubRepository
import dev.agentrelay.session.api.SessionActivityType
import dev.agentrelay.session.api.SessionActionRisk
import dev.agentrelay.session.api.SessionActionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionCoordinatorTest {
    @Test
    fun oneProviderDiscoveryFailureDoesNotHideOtherConnectionProviders() = runTest {
        val broken = FakeConnectionProvider(
            providerId = "ssh",
            profileId = "remote",
            label = "Remote",
            initialRuntime = FakeRuntime("remote"),
            failProfileDiscovery = true,
        )
        val local = FakeConnectionProvider(
            providerId = "local",
            profileId = "device",
            label = "This device",
            initialRuntime = FakeRuntime("device"),
        )
        val coordinator = coordinator(listOf(broken, local), FakeAgentFactory())

        try {
            coordinator.refreshProfiles()
            runCurrent()

            assertEquals(listOf(local.summary), coordinator.snapshot.value.profiles)
            assertTrue(
                coordinator.snapshot.value.issues.values.any {
                    it.kind == SessionCoordinatorIssueKind.PROFILE_DISCOVERY
                },
            )
        } finally {
            coordinator.shutdown()
        }

        assertEquals(1, broken.closeCount)
        assertEquals(1, local.closeCount)
    }

    @Test
    fun controllerPreparationFailurePublishesTypedMessageAndIssue() = runTest {
        val broken = FakeConnectionProvider(
            providerId = "local",
            profileId = "device",
            label = "This device",
            initialRuntime = FakeRuntime("device"),
            failConnectionLookup = true,
        )
        val coordinator = coordinator(listOf(broken), FakeAgentFactory())

        try {
            coordinator.refreshProfiles()

            val snapshot = coordinator.snapshot.value
            val failure = assertIs<ConnectionState.Failed>(
                snapshot.connectionStates.getValue(broken.key()),
            ).failure
            assertEquals(
                ConnectionFailureMessage.Generated(
                    ConnectionFailureMessageKind.PROFILE_PREPARATION_FAILED,
                ),
                failure.message,
            )
            assertTrue(
                snapshot.issues.values.any { issue ->
                    issue.kind == SessionCoordinatorIssueKind.CONNECTION_SETUP &&
                        issue.connection == broken.key()
                },
            )
        } finally {
            coordinator.shutdown()
        }
    }

    @Test
    fun providerReadinessAndSynchronizationFailuresRemainClassified() = runTest {
        val provider = FakeConnectionProvider(
            providerId = "local",
            profileId = "device",
            label = "This device",
            initialRuntime = FakeRuntime("device"),
        )
        val agent = FakeAgentFactory()
        val coordinator = coordinator(listOf(provider), agent)

        try {
            coordinator.refreshProfiles()
            agent.readiness = ProviderReadiness.Incompatible("1.0.0", "bridge unavailable")
            coordinator.connect(provider.key())
            runCurrent()
            val endpoint = coordinator.snapshot.value.agentEndpoints.values.single()
            assertEquals(AgentEndpointPhase.UNAVAILABLE, endpoint.phase)
            assertEquals(
                ProviderSynchronizationClassification.UNSUPPORTED,
                endpoint.synchronizationClassification,
            )
            assertTrue(coordinator.snapshot.value.issues.isEmpty())

            agent.readiness = ProviderReadiness.Failed("timeout", recoverable = true)
            provider.managed.disconnect()
            runCurrent()
            coordinator.connect(provider.key())
            runCurrent()
            val transient = coordinator.snapshot.value.agentEndpoints.values.single()
            assertEquals(AgentEndpointPhase.FAILED, transient.phase)
            assertEquals(
                ProviderSynchronizationClassification.TRANSIENT_FAILURE,
                transient.synchronizationClassification,
            )
            assertTrue(coordinator.snapshot.value.issues.values.single().recoverable)

            agent.readiness = ProviderReadiness.Failed("malformed response", recoverable = false)
            provider.managed.disconnect()
            runCurrent()
            coordinator.connect(provider.key())
            runCurrent()
            val real = coordinator.snapshot.value.issues.values.single()
            assertEquals(ProviderSynchronizationClassification.REAL_FAILURE, real.classification)
            assertFalse(real.recoverable)
            assertEquals(ProviderSynchronizationRecovery.NONE, real.recovery)
        } finally {
            coordinator.shutdown()
        }
    }

    @Test
    fun sameAgentSessionIdOnSshAndLocalRemainsFullyScoped() = runTest {
        val ssh = FakeConnectionProvider(
            providerId = "ssh",
            profileId = "workstation",
            label = "Workstation",
            initialRuntime = FakeRuntime("ssh-host"),
        )
        val local = FakeConnectionProvider(
            providerId = "local",
            profileId = "device",
            label = "This device",
            initialRuntime = FakeRuntime("local-host"),
        )
        val agents = FakeAgentFactory()
        val coordinator = coordinator(listOf(ssh, local), agents)

        try {
            coordinator.refreshProfiles()
            runCurrent()
            coordinator.connect(ssh.key())
            coordinator.connect(local.key())
            runCurrent()

            val records = coordinator.repository.snapshot.value.sessions
            assertEquals(2, records.size)
            val sshLocator = records.single {
                it.locator.connectionProviderId == ssh.descriptor.id
            }.locator
            val localLocator = records.single {
                it.locator.connectionProviderId == local.descriptor.id
            }.locator
            assertEquals(AgentSessionId("shared-session"), sshLocator.agentSessionId)
            assertEquals(AgentSessionId("shared-session"), localLocator.agentSessionId)
            assertNotEquals(sshLocator, localLocator)
            assertEquals(
                setOf(AgentEndpointPhase.READY),
                coordinator.snapshot.value.agentEndpoints.values.mapTo(mutableSetOf()) { it.phase },
            )

            agents.latest("local-host").emit(
                AgentEvent.MessageCompleted(
                    sessionId = AgentSessionId("shared-session"),
                    turnId = null,
                    itemId = "message-live",
                    channel = AgentMessageChannel.FINAL,
                    text = "Local result",
                ),
            )
            runCurrent()

            val snapshot = coordinator.repository.snapshot.value
            assertEquals(0, snapshot.session(sshLocator)?.unreadCount)
            assertEquals(1, snapshot.session(localLocator)?.unreadCount)
            assertEquals(
                listOf(SessionActivityType.NEW_OUTPUT),
                snapshot.activities.filter { it.locator == localLocator }.map { it.type },
            )
            assertEquals(
                setOf("cached:local-host:shared-session", "message-live"),
                snapshot.transcripts.getValue(localLocator).mapTo(mutableSetOf()) { it.id },
            )
            assertTrue(snapshot.transcripts.values.flatten().none { it.id == "foreign-row" })
        } finally {
            coordinator.shutdown()
        }
    }

    @Test
    fun reconnectClosesStaleAgentConnectionAndRecordsDurableActivity() = runTest {
        val local = FakeConnectionProvider(
            providerId = "local",
            profileId = "device",
            label = "This device",
            initialRuntime = FakeRuntime("initial"),
        )
        val agents = FakeAgentFactory()
        val coordinator = coordinator(listOf(local), agents)

        try {
            coordinator.refreshProfiles()
            runCurrent()
            coordinator.connect(local.key())
            runCurrent()
            val first = agents.latest("initial")
            val locator = coordinator.repository.snapshot.value.sessions.single().locator

            local.managed.loseConnection()
            runCurrent()

            assertEquals(1, first.closeCount)
            assertIs<ConnectionState.Reconnecting>(
                coordinator.snapshot.value.connectionStates.getValue(local.key()),
            )
            assertEquals(
                AgentEndpointPhase.OFFLINE,
                coordinator.snapshot.value.agentEndpoints.values.single().phase,
            )

            local.managed.reconnectWith(FakeRuntime("replacement"))
            runCurrent()

            assertEquals(2, agents.connections.size)
            assertEquals(0, agents.latest("replacement").closeCount)
            assertTrue(
                coordinator.repository.snapshot.value.activities.any {
                    it.locator == locator && it.type == SessionActivityType.RECONNECTED
                },
            )
            assertEquals(
                AgentEndpointPhase.READY,
                coordinator.snapshot.value.agentEndpoints.values.single().phase,
            )
        } finally {
            coordinator.shutdown()
        }

        assertEquals(1, agents.latest("replacement").closeCount)
    }

    @Test
    fun typedActionsRouteOnlyToTheSelectedConnectionAndAgentEndpoint() = runTest {
        val ssh = FakeConnectionProvider(
            providerId = "ssh",
            profileId = "workstation",
            label = "Workstation",
            initialRuntime = FakeRuntime("ssh-host"),
        )
        val agents = FakeAgentFactory()
        val coordinator = coordinator(listOf(ssh), agents)

        try {
            coordinator.refreshProfiles()
            runCurrent()
            coordinator.connect(ssh.key())
            runCurrent()
            val endpoint = AgentEndpointKey(ssh.key(), agents.providerId)
            val existing = coordinator.repository.snapshot.value.sessions.single().locator
            val connection = agents.latest("ssh-host")

            assertEquals(existing, coordinator.attach(existing))
            assertEquals(
                listOf("cached:ssh-host:shared-session"),
                coordinator.transcript(existing).map { it.id },
            )
            coordinator.sendInput(existing, "continue")
            coordinator.steerActiveTurn(existing, "focus on tests")
            coordinator.interrupt(existing)
            val approvalId = AgentApprovalId("approval-1")
            connection.emit(
                AgentEvent.ApprovalRequested(
                    sessionId = existing.agentSessionId,
                    approval = AgentApproval(
                        id = approvalId,
                        sessionId = existing.agentSessionId,
                        turnId = null,
                        type = AgentApprovalType.USER_INPUT,
                        title = "Choose a scope",
                        description = "Provide the narrowest useful scope",
                        questions = listOf(
                            AgentQuestion(
                                id = "scope",
                                header = "Scope",
                                prompt = "Which scope should be used?",
                            ),
                        ),
                        availableDecisions = setOf(
                            AgentApprovalDecision.SUBMIT,
                            AgentApprovalDecision.CANCEL,
                        ),
                    ),
                ),
            )
            runCurrent()
            val request = coordinator.repository.snapshot.value.actionRequests.single()
            val privateAnswer = "temporary custom scope"
            val answers = mapOf(request.questions.single().id to listOf(privateAnswer))
            coordinator.respondToAction(
                locator = existing,
                requestId = request.id,
                decision = AgentApprovalDecision.SUBMIT,
                answers = answers,
            )
            val changes = coordinator.changedFiles(existing)
            val started = coordinator.startSession(
                endpoint,
                StartSessionOptions(
                    workingDirectory = "/workspace/new",
                    model = "test-model",
                ),
            )
            runCurrent()

            assertEquals(existing.agentProviderId, started.agentProviderId)
            assertEquals(existing.connectionProviderId, started.connectionProviderId)
            assertEquals(existing.connectionProfileId, started.connectionProfileId)
            assertEquals(listOf(AgentSessionId("shared-session") to "continue"), connection.sentInputs)
            assertEquals(
                listOf(AgentSessionId("shared-session") to "focus on tests"),
                connection.steeredInputs,
            )
            assertEquals(listOf(AgentSessionId("shared-session")), connection.interrupted)
            assertEquals(
                listOf(
                    Triple(
                        approvalId,
                        AgentApprovalDecision.SUBMIT,
                        mapOf("scope" to listOf(privateAnswer)),
                    ),
                ),
                connection.approvalResponses,
            )
            val resolved = assertNotNull(
                coordinator.repository.snapshot.value.actionRequest(existing, request.id),
            )
            assertEquals(SessionActionState.RESOLVED, resolved.state)
            assertEquals(setOf(request.questions.single().id), resolved.answeredQuestionIds)
            assertFalse(resolved.toString().contains(privateAnswer))
            assertTrue(
                coordinator.repository.snapshot.value.activities
                    .single { it.actionRequestId == request.id }
                    .isResolved,
            )
            assertEquals("/workspace/ssh-host/result.txt", changes.single().providerPath)
            assertEquals("result.txt", changes.single().relativePath)
            assertEquals(
                changes,
                coordinator.repository.snapshot.value.sessionArtifacts(existing),
            )
            assertTrue(coordinator.repository.snapshot.value.session(started) != null)
        } finally {
            coordinator.shutdown()
        }
    }

    @Test
    fun artifactDownloadUsesReviewedWorkspaceRelativeConnectionStream() = runTest {
        val content = "artifact body".encodeToByteArray()
        val fileAccess = FakeRemoteFileAccess(content)
        val local = FakeConnectionProvider(
            providerId = "local",
            profileId = "device",
            label = "This device",
            initialRuntime = FakeRuntime("artifact-host", fileAccess),
        )
        val coordinator = coordinator(listOf(local), FakeAgentFactory())

        try {
            coordinator.refreshProfiles()
            runCurrent()
            coordinator.connect(local.key())
            runCurrent()
            val locator = coordinator.repository.snapshot.value.sessions.single().locator
            assertTrue(coordinator.snapshot.value.agentEndpoints.values.single().fileAccessAvailable)
            val artifact = coordinator.changedFiles(locator).single()

            val download = coordinator.prepareArtifactDownload(locator, artifact.id)
            val received = download.chunks.toList()
                .fold(ByteArray(0)) { result, chunk -> result + chunk }

            assertEquals("/workspace/artifact-host", fileAccess.inspectedReference?.workspaceRoot)
            assertEquals("result.txt", fileAccess.inspectedReference?.relativePath)
            assertEquals(fileAccess.inspectedReference, fileAccess.readReference)
            assertEquals("0".repeat(64), download.sourceSnapshot.revision.sha256)
            assertContentEquals(content, received)
        } finally {
            coordinator.shutdown()
        }
    }

    @Test
    fun failedApprovalDeliveryRemainsUncertainWhenProviderEventIsReplayed() = runTest {
        val local = FakeConnectionProvider(
            providerId = "local",
            profileId = "device",
            label = "This device",
            initialRuntime = FakeRuntime("local-host"),
        )
        val agents = FakeAgentFactory()
        val coordinator = coordinator(listOf(local), agents)

        try {
            coordinator.refreshProfiles()
            runCurrent()
            coordinator.connect(local.key())
            runCurrent()
            val connection = agents.latest("local-host")
            val locator = coordinator.repository.snapshot.value.sessions.single().locator
            val approval = AgentApproval(
                id = AgentApprovalId("dangerous-command"),
                sessionId = locator.agentSessionId,
                turnId = null,
                type = AgentApprovalType.COMMAND,
                title = "Remove generated output",
                description = "Clean everything before rebuilding",
                command = "rm -rf /",
                workingDirectory = "/",
                availableDecisions = setOf(
                    AgentApprovalDecision.APPROVE_ONCE,
                    AgentApprovalDecision.CANCEL,
                ),
            )
            connection.emit(AgentEvent.ApprovalRequested(locator.agentSessionId, approval))
            runCurrent()
            val request = coordinator.repository.snapshot.value.actionRequests.single()
            assertTrue(SessionActionRisk.DESTRUCTIVE_COMMAND in request.riskReasons)
            assertTrue(SessionActionRisk.BROAD_FILESYSTEM_ACCESS in request.riskReasons)

            assertFailsWith<IllegalArgumentException> {
                coordinator.respondToAction(
                    locator = locator,
                    requestId = request.id,
                    decision = AgentApprovalDecision.APPROVE_ONCE,
                )
            }
            assertEquals(
                SessionActionState.PENDING,
                coordinator.repository.snapshot.value.actionRequests.single().state,
            )
            assertTrue(connection.approvalResponses.isEmpty())

            connection.failApprovalResponses = true
            val failure = assertFailsWith<SessionActionDeliveryUncertainException> {
                coordinator.respondToAction(
                    locator = locator,
                    requestId = request.id,
                    decision = AgentApprovalDecision.APPROVE_ONCE,
                    additionalConfirmationGiven = true,
                )
            }
            assertFalse(failure.message.orEmpty().contains("private transport"))
            assertEquals(
                SessionActionState.DELIVERING,
                coordinator.repository.snapshot.value.actionRequests.single().state,
            )
            assertFalse(coordinator.repository.snapshot.value.activities.single().isResolved)

            connection.failApprovalResponses = false
            connection.emit(AgentEvent.ApprovalRequested(locator.agentSessionId, approval))
            runCurrent()
            assertEquals(
                SessionActionState.DELIVERING,
                coordinator.repository.snapshot.value.actionRequests.single().state,
            )
            assertFailsWith<IllegalArgumentException> {
                coordinator.respondToAction(
                    locator = locator,
                    requestId = request.id,
                    decision = AgentApprovalDecision.CANCEL,
                )
            }
            assertTrue(connection.approvalResponses.isEmpty())
        } finally {
            coordinator.shutdown()
        }
    }

    private suspend fun kotlinx.coroutines.test.TestScope.coordinator(
        providers: List<FakeConnectionProvider>,
        agents: FakeAgentFactory,
    ): SessionCoordinator = SessionCoordinator(
        connectionRegistry = ConnectionProviderRegistry(providers),
        agentRegistry = AgentProviderRegistry(listOf(agents)),
        repository = PersistentSessionHubRepository.open(InMemorySessionHubStore()),
        dispatcher = StandardTestDispatcher(testScheduler),
        clock = TickingCoordinatorClock(),
    )

    private fun FakeConnectionProvider.key() = SessionConnectionKey(
        providerId = descriptor.id,
        profileId = summary.id,
    )
}
