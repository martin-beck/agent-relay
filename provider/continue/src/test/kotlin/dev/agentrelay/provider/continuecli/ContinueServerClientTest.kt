package dev.agentrelay.provider.continuecli

import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import dev.agentrelay.provider.api.StartSessionOptions
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContinueServerClientTest {
    @Test
    fun startsLoopbackServerAndKeepsRequestBodiesOutOfArguments() = runTest {
        val process = FakeProcess()
        val runtime = FakeRuntime(process)
        val sessionId = AgentSessionId("session-1")
        val client = ContinueServerClient.start(
            runtime = runtime,
            executable = "/home/test/.local/bin/cn",
            sessionId = sessionId,
            workingDirectory = "/workspace with spaces",
            options = StartSessionOptions(
                workingDirectory = "/workspace with spaces",
                model = "owner/model",
                providerOptions = mapOf(
                    "config" to "owner/config",
                    "readonly" to "true",
                ),
            ),
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val server = runtime.opened.single()
        assertEquals("/home/test/.local/bin/cn", server.program)
        assertEquals("/workspace with spaces", server.workingDirectory)
        assertTrue(server.arguments.containsAll(listOf("serve", "--id", "session-1")))
        assertTrue(server.arguments.containsAll(listOf("--model", "owner/model")))
        assertTrue(server.arguments.containsAll(listOf("--config", "owner/config")))
        assertTrue("--readonly" in server.arguments)
        assertFalse("--auto" in server.arguments)

        val nodeOptions = server.environment.getValue("NODE_OPTIONS")
        val preload = String(Base64.getDecoder().decode(nodeOptions.substringAfter("base64,")))
        assertTrue(preload.contains("127.0.0.1"))
        assertTrue(preload.contains("delete process.env.NODE_OPTIONS"))

        client.sendMessage("secret prompt value")
        val messageCommand = runtime.executed.last().command
        assertFalse(messageCommand.arguments.joinToString(" ").contains("secret prompt value"))
        assertEquals(
            """{"message":"secret prompt value"}""",
            messageCommand.environment["AGENT_RELAY_CONTINUE_BODY"],
        )
        assertEquals("55", messageCommand.environment["AGENT_RELAY_CONTINUE_REQUEST_TIMEOUT"])

        client.respondToPermission("permission-1", approved = false)
        assertTrue(
            runtime.executed.last().command.environment
                .getValue("AGENT_RELAY_CONTINUE_BODY")
                .contains("\"approved\":false"),
        )
        assertEquals("diff --git a/a b/a", client.diff())

        client.close()
        assertTrue(process.closed)
        assertTrue(runtime.requestPaths.contains("/exit"))
    }

    @Test
    fun rejectsWrongSessionUnsafeListenerAndHealthFailure() = runTest {
        val wrongProcess = FakeProcess()
        val wrongRuntime = FakeRuntime(wrongProcess).apply {
            returnedSessionId = "different-session"
        }
        assertFailsWith<IllegalStateException> {
            ContinueServerClient.start(
                runtime = wrongRuntime,
                executable = "/usr/bin/cn",
                sessionId = AgentSessionId("expected-session"),
                workingDirectory = null,
                dispatcher = UnconfinedTestDispatcher(testScheduler),
            )
        }
        assertTrue(wrongProcess.closed)

        val unsafeProcess = FakeProcess()
        val unsafeRuntime = FakeRuntime(unsafeProcess).apply {
            validationSafe = false
        }
        assertFailsWith<IllegalStateException> {
            ContinueServerClient.start(
                runtime = unsafeRuntime,
                executable = "/usr/bin/cn",
                sessionId = AgentSessionId("session-1"),
                workingDirectory = null,
                dispatcher = UnconfinedTestDispatcher(testScheduler),
            )
        }
        assertTrue(unsafeProcess.closed)

        val failedProcess = FakeProcess()
        val failedRuntime = FakeRuntime(failedProcess).apply {
            healthFailuresRemaining = Int.MAX_VALUE
        }
        assertFailsWith<IllegalStateException> {
            ContinueServerClient.start(
                runtime = failedRuntime,
                executable = "/usr/bin/cn",
                sessionId = AgentSessionId("session-1"),
                workingDirectory = null,
                dispatcher = UnconfinedTestDispatcher(testScheduler),
            )
        }
        assertEquals(40, failedRuntime.healthCalls)
        assertTrue(failedProcess.closed)
    }

    private data class Executed(
        val command: RemoteCommand,
        val timeout: Duration,
    )

    private class FakeRuntime(private val process: FakeProcess) : RemoteAgentRuntime {
        override val hostId: String = "test-host"
        val executed = mutableListOf<Executed>()
        val opened = mutableListOf<RemoteCommand>()
        val requestPaths = mutableListOf<String>()
        var returnedSessionId = "session-1"
        var healthFailuresRemaining = 0
        var healthCalls = 0
        var validationSafe = true

        override suspend fun execute(
            command: RemoteCommand,
            timeout: Duration,
        ): RemoteCommandResult {
            executed += Executed(command, timeout)
            if (command.program == "python3") {
                if (command.arguments.size == 3) {
                    return if (validationSafe) {
                        RemoteCommandResult(0, "loopback\n", "")
                    } else {
                        RemoteCommandResult(1, "unsafe\n", "")
                    }
                }
                return RemoteCommandResult(0, "43124\n", "")
            }
            check(command.program == "bash")
            val path = command.environment.getValue("AGENT_RELAY_CONTINUE_URL")
                .substringAfter("43124")
            requestPaths += path
            if (path == "/state") {
                healthCalls += 1
                if (healthFailuresRemaining > 0) {
                    healthFailuresRemaining -= 1
                    return RemoteCommandResult(28, "", "timed out")
                }
                return RemoteCommandResult(
                    0,
                    """
                    {
                      "session":{"sessionId":"$returnedSessionId","history":[]},
                      "isProcessing":false,
                      "messageQueueLength":0,
                      "pendingPermission":null
                    }
                    """.trimIndent(),
                    "",
                )
            }
            return when (path) {
                "/diff" -> RemoteCommandResult(
                    0,
                    """{"diff":"diff --git a/a b/a"}""",
                    "",
                )
                else -> RemoteCommandResult(0, "{}", "")
            }
        }

        override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess {
            opened += command
            return process
        }
    }

    private class FakeProcess : RemoteDuplexProcess {
        override val standardOutputLines = MutableSharedFlow<String>()
        override val standardErrorLines = MutableSharedFlow<String>()
        override val exitCode = MutableStateFlow<Int?>(null)
        var closed = false

        override suspend fun writeLine(line: String) = error("Not used")

        override suspend fun close() {
            closed = true
        }
    }
}
