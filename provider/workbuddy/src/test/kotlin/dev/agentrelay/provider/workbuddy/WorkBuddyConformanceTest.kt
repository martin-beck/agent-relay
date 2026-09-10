/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.workbuddy

import dev.agentrelay.provider.api.AgentProviderConnection
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class WorkBuddyConformanceTest {
    @Test
    fun syntheticReplayIsCredentialFreeAndProvenancePinned() = runTest {
        val fixture = fixture()
        val provenance = fixture.objectValue("provenance")
        assertEquals("synthetic-credential-free", provenance.string("classification"))
        assertEquals("docs/contracts/workbuddy-provider-v1.json", provenance.string("contract"))
        assertTrue(provenance.string("open_api_reference_digest").matches(SHA256))
        assertTrue(provenance.string("third_party_app_digest").matches(SHA256))
        val serialized = fixture.toString()
        listOf("access_token", "authorization", "bearer ", "private key").forEach {
            assertFalse(serialized.contains(it, ignoreCase = true))
        }

        val runtime = ScriptedRuntime(
            success(fixture.objectValue("status_online").toString()),
            success(fixture.objectValue("history_gap").toString()),
            success(fixture.objectValue("send_accepted").toString()),
        )
        val client = RemoteWorkBuddyClient(runtime)

        assertEquals(WorkBuddyOnlineResult.Available(true), client.onlineStatus())
        assertEquals(listOf("msg-100", "msg-900"), client.history().map { it.id })
        assertIs<WorkBuddySendResult.Accepted>(client.sendText("synthetic prompt"))
        assertEquals(3, runtime.commands.size)
        assertTrue(runtime.commands.all { it.environment.isEmpty() })
        assertTrue(
            runtime.commands.all { command ->
                command.arguments.none { argument -> "synthetic prompt" in argument }
            },
        )
    }

    @Test
    fun authenticationAuthorizationAndRateLimitsFailClosed() = runTest {
        assertEquals(
            WorkBuddyOnlineResult.AuthenticationRequired,
            RemoteWorkBuddyClient(ScriptedRuntime(failure(41))).onlineStatus(),
        )
        assertEquals(
            WorkBuddyOnlineResult.AuthorizationDenied,
            RemoteWorkBuddyClient(ScriptedRuntime(failure(42))).onlineStatus(),
        )
        assertEquals(
            WorkBuddySendResult.RateLimited,
            RemoteWorkBuddyClient(ScriptedRuntime(failure(43))).sendText("synthetic prompt"),
        )
    }

    @Test
    fun onlineOfflineLifecycleAndCloseAreExplicit() = runTest {
        val runtime = ScriptedRuntime(
            success(status(false)),
            success(status(true)),
        )
        val connection = connection(RemoteWorkBuddyClient(runtime))

        val offline = connection.refreshSessions().single()
        assertEquals(AgentSessionState.UNKNOWN, offline.state)
        assertFalse(offline.canAcceptInput)
        val online = connection.refreshSessions().single()
        assertEquals(AgentSessionState.IDLE, online.state)
        assertTrue(online.canAcceptInput)

        connection.close()
        assertTrue(connection.sessions.value.isEmpty())
        assertFailsWith<IllegalStateException> { connection.refreshSessions() }
        assertFailsWith<IllegalStateException> {
            connection.transcript(AgentSessionId("local-assistant"))
        }
    }

    @Test
    fun historyGapIsPreservedAndDuplicatesFailClosed() = runTest {
        val gap = fixture().objectValue("history_gap").toString()
        assertEquals(
            listOf("msg-100", "msg-900"),
            RemoteWorkBuddyClient(ScriptedRuntime(success(gap))).history().map { it.id },
        )
        val duplicate = history("msg-1", "msg-1")
        assertFailsWith<IllegalArgumentException> {
            RemoteWorkBuddyClient(ScriptedRuntime(success(duplicate))).history()
        }
    }

    @Test
    fun malformedReadIsNotReplayedAndOversizedTransportRetriesOnce() = runTest {
        val malformedRuntime = ScriptedRuntime(success("not-json"), success("not-json"))
        assertEquals(
            WorkBuddyOnlineResult.Unavailable,
            RemoteWorkBuddyClient(malformedRuntime).onlineStatus(),
        )
        assertEquals(1, malformedRuntime.commands.size)

        val oversizedRuntime = ScriptedRuntime(
            success("x".repeat(1_048_577)),
            success("x".repeat(1_048_577)),
        )
        assertEquals(
            WorkBuddyOnlineResult.Unavailable,
            RemoteWorkBuddyClient(oversizedRuntime).onlineStatus(),
        )
        assertEquals(2, oversizedRuntime.commands.size)
        assertTrue(oversizedRuntime.processes.all { it.closed.get() })
    }

    @Test
    fun repeatedSubmissionIsCallerDrivenAndUnknownOutcomeIsNeverRetried() = runTest {
        val runtime = ScriptedRuntime(
            success(fixture().objectValue("send_accepted").toString()),
            failure(45),
        )
        val client = RemoteWorkBuddyClient(runtime)

        assertIs<WorkBuddySendResult.Accepted>(client.sendText("first synthetic prompt"))
        assertEquals(WorkBuddySendResult.UnknownOutcome, client.sendText("second synthetic prompt"))
        assertEquals(2, runtime.commands.size)
        assertEquals(2, runtime.inputs.size)
    }

    @Test
    fun cancellationAfterSubmissionBecomesUnknownOutcomeAndClosesProcess() = runTest {
        val process = ScriptedProcess(success(""), cancelWrite = true)
        val runtime = ScriptedRuntime(processes = arrayOf(process))

        val failure = assertFailsWith<WorkBuddyUnknownOutcomeException> {
            RemoteWorkBuddyClient(runtime).sendText("synthetic prompt")
        }

        assertIs<CancellationException>(failure.cause)
        assertTrue(process.closed.get())
        assertEquals(1, runtime.commands.size)
    }

    @Test
    fun timeoutAfterSubmissionBecomesUnknownOutcomeAndClosesProcess() = runTest {
        val process = ScriptedProcess(success(""), hang = true)
        val runtime = ScriptedRuntime(processes = arrayOf(process))

        assertEquals(
            WorkBuddySendResult.UnknownOutcome,
            RemoteWorkBuddyClient(runtime).sendText("synthetic prompt"),
        )
        assertTrue(process.closed.get())
        assertEquals(1, runtime.commands.size)
    }

    @Test
    fun hostileInputsFailBeforeRemoteIo() = runTest {
        val runtime = ScriptedRuntime()
        val client = RemoteWorkBuddyClient(runtime)

        assertFailsWith<IllegalArgumentException> { client.sendText(" ") }
        assertFailsWith<IllegalArgumentException> { client.sendText("x".repeat(32_769)) }
        assertFailsWith<IllegalArgumentException> { client.history(limit = 0) }
        assertFailsWith<IllegalArgumentException> { client.history(offset = 1_000_001) }
        assertTrue(runtime.commands.isEmpty())
    }

    @Test
    fun commandBoundaryHasFixedHttpsRouteAndNoCredentialSerialization() = runTest {
        val runtime = ScriptedRuntime(success(status(true)))
        RemoteWorkBuddyClient(runtime).onlineStatus()

        val command = runtime.commands.single()
        assertEquals("python3", command.program)
        assertTrue(command.environment.isEmpty())
        val helper = command.arguments.single { "urllib.request" in it }
        assertContains(helper, "https://www.workbuddy.cn/openapi/v2/localassistant")
        assertContains(helper, "HTTPRedirectHandler")
        assertContains(helper, "scopes.issubset(allowed)")
        assertTrue(runtime.inputs.single() == "{}")
    }

    private fun connection(client: WorkBuddyClient): AgentProviderConnection =
        WorkBuddyAgentConnection(WorkBuddyAgentProviderFactory { client }.descriptor, client)

    private fun fixture(): JsonObject {
        val stream = checkNotNull(javaClass.classLoader.getResourceAsStream(FIXTURE))
        return stream.bufferedReader().use { JSON.parseToJsonElement(it.readText()).jsonObject }
    }

    private fun status(online: Boolean): String =
        """{"code":0,"msg":"synthetic","request_id":"req-status","data":{"online":$online}}"""

    private fun history(firstId: String, secondId: String): String =
        """
        {"code":0,"msg":"synthetic","request_id":"req-history","data":{"messages":[
          {"message_id":"$firstId","role":"user","content":["one"],"msg_type":"text",
           "created_at":"2026-09-10T10:00:00Z","attachments":[],"metadata":{}},
          {"message_id":"$secondId","role":"assistant","content":["two"],"msg_type":"text",
           "created_at":"2026-09-10T10:00:01Z","attachments":[],"metadata":{}}
        ]}}
        """.trimIndent()

    private class ScriptedRuntime(
        vararg results: RemoteCommandResult,
        processes: Array<ScriptedProcess> = results.map(::ScriptedProcess).toTypedArray(),
    ) : RemoteAgentRuntime {
        private val pending = ArrayDeque(processes.toList())
        val commands = mutableListOf<RemoteCommand>()
        val inputs = mutableListOf<String>()
        val processes = processes.toList()
        override val hostId = "synthetic-host"

        override suspend fun execute(command: RemoteCommand, timeout: Duration): RemoteCommandResult =
            error("WorkBuddy conformance requires stdin")

        override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess {
            commands += command
            return pending.removeFirst().also { process -> process.onInput = inputs::add }
        }
    }

    private class ScriptedProcess(
        result: RemoteCommandResult,
        private val cancelWrite: Boolean = false,
        hang: Boolean = false,
    ) : RemoteDuplexProcess {
        var onInput: (String) -> Unit = {}
        val closed = AtomicBoolean(false)
        override val standardOutputLines: Flow<String> = lines(result.standardOutput)
        override val standardErrorLines: Flow<String> = lines(result.standardError)
        override val exitCode = MutableStateFlow<Int?>(if (hang) null else result.exitCode)

        override suspend fun writeLine(line: String) {
            onInput(line)
            if (cancelWrite) throw CancellationException("synthetic cancellation")
        }

        override suspend fun close() {
            closed.set(true)
        }

        private fun lines(value: String): Flow<String> =
            if (value.isEmpty()) flowOf() else flow { emit(value) }
    }

    private fun JsonObject.objectValue(name: String): JsonObject = getValue(name).jsonObject
    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private companion object {
        const val FIXTURE = "workbuddy-synthetic-replay-v1.json"
        val JSON = Json { isLenient = false }
        val SHA256 = Regex("^sha256:[0-9a-f]{64}$")
    }
}

private fun success(output: String) = RemoteCommandResult(0, output, "")
private fun failure(exitCode: Int) = RemoteCommandResult(exitCode, "", "")
