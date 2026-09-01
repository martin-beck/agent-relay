package dev.agentrelay.provider.claude

import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import dev.agentrelay.provider.api.StartSessionOptions
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClaudeStreamClientTest {
    @Test
    fun initializesStreamsControlsAndKeepsPromptsOutOfArguments() = runTest {
        val process = FakeProcess()
        val runtime = FakeRuntime(process)
        val starting = async(start = CoroutineStart.UNDISPATCHED) {
            ClaudeStreamClient.start(
                runtime = runtime,
                executable = "/home/test/.local/bin/claude",
                sessionId = AgentSessionId("session-1"),
                resume = true,
                workingDirectory = "/workspace with spaces",
                options = StartSessionOptions(
                    workingDirectory = "/workspace with spaces",
                    model = "claude-sonnet",
                    providerOptions = mapOf(
                        "permissionMode" to "manual",
                        "tools" to "Read,Edit",
                        "name" to "Agent Relay",
                        "safeMode" to "true",
                    ),
                ),
                dispatcher = UnconfinedTestDispatcher(testScheduler),
            )
        }

        val initialize = process.writes.single().asObject()
        assertEquals("initialize", initialize.request().string("subtype"))
        process.emit(
            """{"type":"system","subtype":"init","cwd":"/workspace with spaces"}""",
        )
        process.emit(controlSuccess(initialize.string("request_id")!!))
        val client = starting.await()

        val opened = runtime.opened.single()
        assertEquals("/home/test/.local/bin/claude", opened.program)
        assertEquals("/workspace with spaces", opened.workingDirectory)
        assertTrue(opened.arguments.containsAll(listOf("--resume", "session-1")))
        assertTrue(opened.arguments.containsAll(listOf("--model", "claude-sonnet")))
        assertTrue(opened.arguments.containsAll(listOf("--tools", "Read,Edit")))
        assertTrue("--safe-mode" in opened.arguments)
        assertEquals(
            "/workspace with spaces",
            client.messages.first { it.string("subtype") == "init" }.string("cwd"),
        )

        client.sendUserMessage("secret prompt value")
        assertFalse(opened.arguments.joinToString(" ").contains("secret prompt value"))
        val user = process.writes.last().asObject()
        assertEquals("user", user.string("type"))
        assertTrue(user.toString().contains("secret prompt value"))

        process.emit(
            """
            {
              "type":"control_request",
              "request_id":"permission-1",
              "request":{
                "subtype":"can_use_tool",
                "tool_name":"Edit",
                "input":{"file_path":"/workspace with spaces/Main.kt"},
                "permission_suggestions":[{"type":"addRules","rules":[{"toolName":"Edit"}]}]
              }
            }
            """.trimIndent(),
        )
        assertEquals(
            "permission-1",
            client.messages.first { it.string("request_id") == "permission-1" }
                .string("request_id"),
        )
        client.respondToPermission("permission-1", allowed = true, remember = true)
        val permissionResponse = process.writes.last().asObject().response()
        assertEquals("success", permissionResponse.string("subtype"))
        assertTrue(permissionResponse.objectValue("response")!!.containsKey("updatedPermissions"))

        process.emit(
            """
            {
              "type":"control_request",
              "request_id":"permission-once",
              "request":{
                "subtype":"can_use_tool",
                "tool_name":"Bash",
                "input":{"command":"pwd"}
              }
            }
            """.trimIndent(),
        )
        runCurrent()
        assertFailsWith<IllegalArgumentException> {
            client.respondToPermission("permission-once", allowed = true, remember = true)
        }
        client.respondToPermission("permission-once", allowed = true, remember = false)

        val interrupting = async(start = CoroutineStart.UNDISPATCHED) { client.interrupt() }
        val interrupt = process.writes.last().asObject()
        assertEquals("interrupt", interrupt.request().string("subtype"))
        process.emit(controlSuccess(interrupt.string("request_id")!!))
        interrupting.await()

        process.emit(
            """{"type":"control_request","request_id":"unknown-1","request":{"subtype":"mystery"}}""",
        )
        runCurrent()
        val unsupported = process.writes.last().asObject().response()
        assertEquals("error", unsupported.string("subtype"))
        assertEquals("unknown-1", unsupported.string("request_id"))

        process.emit("not-json")
        val malformed = client.messages.first {
            it.string("type") == "agent_relay_error" &&
                it.string("message")?.contains("malformed") == true
        }
        assertTrue(malformed.string("message")!!.contains("stream-json"))

        process.exitCode.value = 17
        runCurrent()
        val exit = client.messages.first {
            it.string("type") == "agent_relay_error" &&
                it.string("message")?.contains("exited") == true
        }
        assertTrue(exit.string("message")!!.contains("17"))

        client.close()
        assertTrue(process.closed)
    }

    @Test
    fun initializationFailureClosesProcessAndUnsafePermissionModesAreRejected() = runTest {
        val process = FakeProcess()
        val starting = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                ClaudeStreamClient.start(
                    runtime = FakeRuntime(process),
                    executable = "/usr/bin/claude",
                    sessionId = AgentSessionId("session-1"),
                    resume = false,
                    workingDirectory = null,
                    options = StartSessionOptions(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            }
        }
        val requestId = process.writes.single().asObject().string("request_id")!!
        process.emit(
            """
            {
              "type":"control_response",
              "response":{"subtype":"error","request_id":"$requestId","error":"bad init"}
            }
            """.trimIndent(),
        )
        val failure = starting.await().exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure.message!!.contains("bad init"))
        assertTrue(process.closed)

        assertFailsWith<IllegalArgumentException> {
            ClaudeStreamClient.buildArguments(
                AgentSessionId("session-2"),
                resume = false,
                options = StartSessionOptions(
                    providerOptions = mapOf("permissionMode" to "bypassPermissions"),
                ),
            )
        }
    }

    @Test
    fun processExitFailsPendingInitializationAndClosesTheProcess() = runTest {
        val process = FakeProcess()
        val starting = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                ClaudeStreamClient.start(
                    runtime = FakeRuntime(process),
                    executable = "/usr/bin/claude",
                    sessionId = AgentSessionId("session-1"),
                    resume = true,
                    workingDirectory = "/workspace",
                    options = StartSessionOptions(),
                    dispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            }
        }

        assertEquals("initialize", process.writes.single().asObject().request().string("subtype"))
        process.exitCode.value = 23
        runCurrent()

        val failure = starting.await().exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure.message!!.contains("23"))
        assertTrue(process.closed)
    }

    private class FakeRuntime(private val process: FakeProcess) : RemoteAgentRuntime {
        override val hostId: String = "test-host"
        val opened = mutableListOf<RemoteCommand>()

        override suspend fun execute(
            command: RemoteCommand,
            timeout: Duration,
        ): RemoteCommandResult = error("Not used")

        override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess {
            opened += command
            return process
        }
    }

    private class FakeProcess : RemoteDuplexProcess {
        override val standardOutputLines = MutableSharedFlow<String>(extraBufferCapacity = 32)
        override val standardErrorLines = MutableSharedFlow<String>(extraBufferCapacity = 8)
        override val exitCode = MutableStateFlow<Int?>(null)
        val writes = mutableListOf<String>()
        var closed = false

        override suspend fun writeLine(line: String) {
            writes += line
        }

        override suspend fun close() {
            closed = true
        }

        suspend fun emit(line: String) {
            standardOutputLines.emit(line)
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun String.asObject(): JsonObject = json.parseToJsonElement(this).jsonObject

        fun JsonObject.request(): JsonObject = objectValue("request")!!

        fun JsonObject.response(): JsonObject = objectValue("response")!!

        fun controlSuccess(requestId: String): String =
            """
            {
              "type":"control_response",
              "response":{"subtype":"success","request_id":"$requestId","response":{}}
            }
            """.trimIndent()
    }
}
