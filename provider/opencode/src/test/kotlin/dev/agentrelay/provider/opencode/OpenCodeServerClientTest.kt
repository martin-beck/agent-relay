package dev.agentrelay.provider.opencode

import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OpenCodeServerClientTest {
    @Test
    fun startsAuthenticatedLoopbackServerAndKeepsSecretsOutOfArguments() = runTest {
        val server = FakeProcess()
        val eventStream = FakeProcess()
        val replacementEventStream = FakeProcess()
        val runtime = FakeRuntime(server, eventStream, replacementEventStream)
        val client = OpenCodeServerClient.start(
            runtime = runtime,
            executable = ResolvedOpenCodeExecutable("/home/test/.local/bin/opencode"),
            configuration = testConfiguration(),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val serverCommand = runtime.opened.first()
        assertEquals("/home/test/.local/bin/opencode", serverCommand.program)
        assertTrue(serverCommand.arguments.containsAll(listOf("serve", "127.0.0.1", "43123")))
        val password = serverCommand.environment.getValue("OPENCODE_SERVER_PASSWORD")
        assertTrue(password.length >= 40)
        assertFalse(serverCommand.arguments.joinToString(" ").contains(password))

        client.post(
            "/session",
            buildJsonObject { put("title", "secret prompt value") },
            "/workspace with spaces",
        )
        val request = runtime.executed.last()
        assertFalse(request.command.arguments.joinToString(" ").contains("secret prompt value"))
        assertEquals(
            """{"title":"secret prompt value"}""",
            request.command.environment["AGENT_RELAY_OPENCODE_BODY"],
        )
        assertEquals("55", request.command.environment["AGENT_RELAY_OPENCODE_REQUEST_TIMEOUT"])
        assertTrue(
            request.command.environment.getValue("AGENT_RELAY_OPENCODE_URL")
                .endsWith("?directory=%2Fworkspace+with+spaces"),
        )

        val nextEvent = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) {
            client.events("/workspace").first()
        }
        eventStream.standardOutput.emit("data: not-json")
        eventStream.standardOutput.emit(
            """data: {"type":"session.status","properties":{"sessionID":"ses-1","status":{"type":"busy"}}}""",
        )
        assertEquals("session.status", nextEvent.await().string("type"))

        eventStream.exitCode.value = 0
        testScheduler.runCurrent()
        client.events("/workspace")
        assertEquals(3, runtime.opened.size)

        client.close()
        assertTrue(server.closed)
        assertTrue(replacementEventStream.closed)
    }

    @Test
    fun retriesHealthFailuresAndCleansUpWhenStartupNeverSucceeds() = runTest {
        val recoveringServer = FakeProcess()
        val recoveringRuntime = FakeRuntime(recoveringServer).apply {
            healthFailuresRemaining = 2
        }
        val client = OpenCodeServerClient.start(
            runtime = recoveringRuntime,
            executable = ResolvedOpenCodeExecutable("/usr/bin/opencode"),
            configuration = testConfiguration(),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        assertEquals(3, recoveringRuntime.healthCalls)
        client.close()
        assertTrue(recoveringServer.closed)

        val failedServer = FakeProcess()
        val failedRuntime = FakeRuntime(failedServer).apply {
            healthFailuresRemaining = Int.MAX_VALUE
        }
        assertFailsWith<IllegalStateException> {
            OpenCodeServerClient.start(
                runtime = failedRuntime,
                executable = ResolvedOpenCodeExecutable("/usr/bin/opencode"),
                configuration = testConfiguration(),
                dispatcher = UnconfinedTestDispatcher(testScheduler),
            )
        }
        assertEquals(40, failedRuntime.healthCalls)
        assertTrue(failedServer.closed)
    }

    private fun testConfiguration() = OpenCodeCompatibleProviderConfiguration(
        descriptor = OpenCodeAgentProviderFactory().descriptor,
        executableName = "OpenCode",
        installHint = "test",
        executableLookupScript = "test",
        serveArguments = listOf("serve"),
        readinessDetails = "test",
    )

    private data class Executed(
        val command: RemoteCommand,
        val timeout: Duration,
    )

    private class FakeRuntime(
        private val server: FakeProcess,
        vararg eventStreams: FakeProcess,
    ) : RemoteAgentRuntime {
        override val hostId: String = "test-host"
        val executed = mutableListOf<Executed>()
        val opened = mutableListOf<RemoteCommand>()
        var healthFailuresRemaining = 0
        var healthCalls = 0
        private val eventStreams = ArrayDeque(eventStreams.toList())

        override suspend fun execute(
            command: RemoteCommand,
            timeout: Duration,
        ): RemoteCommandResult {
            executed += Executed(command, timeout)
            return when (command.program) {
                "python3" -> RemoteCommandResult(0, "43123\n", "")
                "bash" -> if (
                    command.environment.getValue("AGENT_RELAY_OPENCODE_URL")
                        .endsWith("/global/health")
                ) {
                    healthCalls += 1
                    if (healthFailuresRemaining > 0) {
                        healthFailuresRemaining -= 1
                        RemoteCommandResult(28, "", "timed out")
                    } else {
                        RemoteCommandResult(0, """{"healthy":true,"version":"1.18.23"}""", "")
                    }
                } else {
                    RemoteCommandResult(0, "{}", "")
                }
                else -> error("Unexpected command " + command.program)
            }
        }

        override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess {
            opened += command
            return if (opened.size == 1) server else eventStreams.removeFirst()
        }
    }

    private class FakeProcess : RemoteDuplexProcess {
        val standardOutput = MutableSharedFlow<String>(extraBufferCapacity = 16)
        val standardError = MutableSharedFlow<String>(extraBufferCapacity = 16)
        override val standardOutputLines = standardOutput
        override val standardErrorLines = standardError
        override val exitCode = MutableStateFlow<Int?>(null)
        var closed = false

        override suspend fun writeLine(line: String) {
            error("Not used")
        }

        override suspend fun close() {
            closed = true
        }
    }
}
