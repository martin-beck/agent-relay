/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.workbuddy

import dev.agentrelay.provider.api.AgentCapability
import dev.agentrelay.provider.api.AgentProviderConnection
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.AgentTranscriptRole
import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import dev.agentrelay.provider.api.StartSessionOptions
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class WorkBuddyAgentProviderTest {
    @Test
    fun descriptorAdvertisesOnlyVerifiedOperations() {
        val descriptor = WorkBuddyAgentProviderFactory { FakeClient() }.descriptor

        assertEquals("workbuddy.localassistant", descriptor.id.value)
        assertEquals(
            setOf(
                AgentCapability.SESSION_DISCOVERY,
                AgentCapability.SESSION_START,
                AgentCapability.SESSION_HISTORY,
            ),
            descriptor.capabilities,
        )
        assertFalse(AgentCapability.SESSION_RESUME in descriptor.capabilities)
        assertFalse(AgentCapability.LIVE_STREAMING in descriptor.capabilities)
        assertFalse(AgentCapability.APPROVALS in descriptor.capabilities)
        assertFalse(AgentCapability.TURN_INTERRUPT in descriptor.capabilities)
        assertFalse(AgentCapability.FILE_CHANGES in descriptor.capabilities)
    }

    @Test
    fun probeRequiresAuthenticationWithoutPublishingDetails() = runTest {
        val factory = WorkBuddyAgentProviderFactory {
            FakeClient(online = WorkBuddyOnlineResult.AuthenticationRequired)
        }

        val readiness = factory.probe(FakeRuntime())

        assertIs<ProviderReadiness.NeedsAuthentication>(readiness)
        assertContains(readiness.loginHint, "explicit consent")
    }

    @Test
    fun singletonSessionMapsOnlineAndOfflineStatus() = runTest {
        val client = FakeClient(online = WorkBuddyOnlineResult.Available(online = true))
        val connection = connection(client)

        val online = connection.refreshSessions().single()
        assertEquals("local-assistant", online.id.value)
        assertEquals(AgentSessionState.IDLE, online.state)
        assertTrue(online.canAcceptInput)

        client.online = WorkBuddyOnlineResult.Available(online = false)
        val offline = connection.refreshSessions().single()
        assertEquals(AgentSessionState.UNKNOWN, offline.state)
        assertFalse(offline.canAcceptInput)
    }

    @Test
    fun historyMapsOnlyBoundedNormalizedText() = runTest {
        val client = FakeClient(
            messages = listOf(
                WorkBuddyMessage("msg-1", AgentTranscriptRole.USER, "hello", 1_786_000_000),
                WorkBuddyMessage("msg-2", AgentTranscriptRole.AGENT, "done", 1_786_000_001),
            ),
        )

        val transcript = connection(client).transcript(AgentSessionId("local-assistant"))

        assertEquals(listOf("msg-1", "msg-2"), transcript.map { it.id })
        assertEquals(listOf("hello", "done"), transcript.map { it.text })
        assertTrue(transcript.all { it.metadata.isEmpty() })
    }

    @Test
    fun sendTimeoutIsExplicitUnknownOutcomeAndNeverRetried() = runTest {
        val client = FakeClient(send = WorkBuddySendResult.UnknownOutcome)
        val connection = connection(client)

        assertFailsWith<WorkBuddyUnknownOutcomeException> {
            connection.sendInput(AgentSessionId("local-assistant"), "bounded request")
        }
        assertEquals(1, client.sendCalls)
    }

    @Test
    fun unsupportedOperationsFailClosed() = runTest {
        val connection = connection(FakeClient())
        val sessionId = AgentSessionId("local-assistant")

        assertFailsWith<UnsupportedOperationException> { connection.interrupt(sessionId) }
        assertFailsWith<UnsupportedOperationException> { connection.steerActiveTurn(sessionId, "more") }
        assertFailsWith<UnsupportedOperationException> { connection.changedFiles(sessionId) }
    }

    @Test
    fun unsupportedSessionOptionsFailBeforeProviderIo() = runTest {
        val client = FakeClient()

        assertFailsWith<IllegalArgumentException> {
            connection(client).startSession(StartSessionOptions(model = "undocumented"))
        }
        assertEquals(0, client.onlineCalls)
    }

    @Test
    fun remoteClientKeepsTokenAndContentOutOfArguments() = runTest {
        val runtime = FakeRuntime(
            RemoteCommandResult(
                0,
                """{"code":0,"msg":"success","request_id":"req-1","data":{"message_id":"msg-1"}}""",
                "",
            ),
        )

        val result = RemoteWorkBuddyClient(runtime).sendText("synthetic request")

        assertIs<WorkBuddySendResult.Accepted>(result)
        val command = runtime.commands.single()
        assertTrue(command.arguments.none { "synthetic request" in it })
        assertTrue(command.environment.isEmpty())
        assertContains(runtime.inputs.single(), "synthetic request")
        val helper = command.arguments.single { "urllib.request" in it }
        assertContains(helper, "https://www.workbuddy.cn/openapi/v2/localassistant")
        assertContains(helper, "user.localassistant.readable")
        assertContains(helper, "user.localassistant.invokable")
        assertContains(helper, "AGENT_RELAY_WORKBUDDY_CONSENT")
        assertContains(helper, "scopes.issubset(allowed)")
        assertContains(helper, "HTTPRedirectHandler")
        assertContains(helper, "return None")
    }

    @Test
    fun successfulPostWithMalformedReceiptPreservesUnknownOutcome() = runTest {
        val runtime = FakeRuntime(
            RemoteCommandResult(
                0,
                """{"code":0,"msg":"success","request_id":"req-1","data":{"message_id":"bad id"}}""",
                "",
            ),
        )

        assertIs<WorkBuddySendResult.UnknownOutcome>(
            RemoteWorkBuddyClient(runtime).sendText("bounded request"),
        )
        assertEquals(1, runtime.commands.size)
    }

    @Test
    fun historyBoundsFailBeforeProviderIo() = runTest {
        val runtime = FakeRuntime()

        assertFailsWith<IllegalArgumentException> {
            RemoteWorkBuddyClient(runtime).history(limit = 101)
        }
        assertTrue(runtime.commands.isEmpty())
    }

    @Test
    fun remoteClientRejectsMalformedAndDuplicateHistory() = runTest {
        val runtime = FakeRuntime(
            RemoteCommandResult(
                0,
                """
                {"code":0,"msg":"success","request_id":"req-1","data":{"messages":[
                  {"message_id":"msg-1","role":"user","content":["one"],"msg_type":"text",
                   "created_at":"2026-07-30T10:00:00Z","attachments":[],"metadata":{}},
                  {"message_id":"msg-1","role":"assistant","content":["two"],"msg_type":"text",
                   "created_at":"2026-07-30T10:00:01Z","attachments":[],"metadata":{}}
                ]}}
                """.trimIndent(),
                "",
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            RemoteWorkBuddyClient(runtime).history()
        }
    }

    @Test
    fun postTransportTimeoutMapsToUnknownOutcome() = runTest {
        val runtime = FakeRuntime(RemoteCommandResult(44, "", ""))

        assertIs<WorkBuddySendResult.UnknownOutcome>(
            RemoteWorkBuddyClient(runtime).sendText("bounded request"),
        )
        assertEquals(1, runtime.commands.size)
    }

    @Test
    fun postProtocolFailureAfterSubmissionPreservesUnknownOutcome() = runTest {
        val runtime = FakeRuntime(RemoteCommandResult(45, "", ""))

        assertIs<WorkBuddySendResult.UnknownOutcome>(
            RemoteWorkBuddyClient(runtime).sendText("bounded request"),
        )
        assertEquals(1, runtime.commands.size)
    }

    @Test
    fun safeStatusReadRetriesOnceAfterTransportFailure() = runTest {
        val runtime = FakeRuntime(
            RemoteCommandResult(45, "", ""),
            RemoteCommandResult(
                0,
                """{"code":0,"msg":"success","request_id":"req-2","data":{"online":true}}""",
                "",
            ),
        )

        assertEquals(
            WorkBuddyOnlineResult.Available(online = true),
            RemoteWorkBuddyClient(runtime).onlineStatus(),
        )
        assertEquals(2, runtime.commands.size)
    }

    @Test
    fun malformedStatusTypesFailClosed() = runTest {
        val runtime = FakeRuntime(
            RemoteCommandResult(
                0,
                """{"code":0,"msg":"success","request_id":"req-1","data":{"online":"true"}}""",
                "",
            ),
        )

        assertEquals(WorkBuddyOnlineResult.Unavailable, RemoteWorkBuddyClient(runtime).onlineStatus())
    }

    private fun connection(client: WorkBuddyClient): AgentProviderConnection =
        WorkBuddyAgentConnection(
            descriptor = WorkBuddyAgentProviderFactory { client }.descriptor,
            client = client,
        )

    private class FakeClient(
        var online: WorkBuddyOnlineResult = WorkBuddyOnlineResult.Available(online = true),
        private val messages: List<WorkBuddyMessage> = emptyList(),
        private val send: WorkBuddySendResult = WorkBuddySendResult.Accepted("msg-1"),
    ) : WorkBuddyClient {
        var onlineCalls = 0
        var sendCalls = 0

        override suspend fun onlineStatus(): WorkBuddyOnlineResult {
            onlineCalls += 1
            return online
        }

        override suspend fun history(limit: Int, offset: Int): List<WorkBuddyMessage> = messages

        override suspend fun sendText(text: String): WorkBuddySendResult {
            sendCalls += 1
            return send
        }
    }

    private class FakeRuntime(vararg results: RemoteCommandResult) : RemoteAgentRuntime {
        private val results = ArrayDeque(results.toList())
        val commands = mutableListOf<RemoteCommand>()
        override val hostId = "synthetic-host"

        override suspend fun execute(
            command: RemoteCommand,
            timeout: Duration,
        ): RemoteCommandResult = error("WorkBuddy requests require stdin")

        override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess {
            commands += command
            return FakeProcess(results.removeFirst()) { inputs += it }
        }

        val inputs = mutableListOf<String>()
    }

    private class FakeProcess(
        result: RemoteCommandResult,
        private val onInput: (String) -> Unit,
    ) : RemoteDuplexProcess {
        override val standardOutputLines: Flow<String> =
            if (result.standardOutput.isEmpty()) flowOf() else flowOf(result.standardOutput)
        override val standardErrorLines: Flow<String> =
            if (result.standardError.isEmpty()) flowOf() else flowOf(result.standardError)
        override val exitCode = MutableStateFlow<Int?>(result.exitCode)

        override suspend fun writeLine(line: String) = onInput(line)

        override suspend fun close() = Unit
    }
}
