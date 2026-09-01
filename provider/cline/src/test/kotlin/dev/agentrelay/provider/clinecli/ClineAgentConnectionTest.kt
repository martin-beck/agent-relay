package dev.agentrelay.provider.clinecli

import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentEvent
import dev.agentrelay.provider.api.AgentSession
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import dev.agentrelay.provider.api.StartSessionOptions
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ClineAgentConnectionTest {
    @Test
    fun attachesHistoryRoutesApprovalsAndCompletesPrompt() = runTest {
        val runtime = FakeRuntime(listOf(history("session-1", "/workspace")))
        val client = FakeClient("session-1", "qwen")
        val starter = RecordingStarter(
            newFactory = { error("Not used") },
            resumeFactory = { client },
        )
        val connection = ClineAgentConnection.create(
            descriptor = ClineAgentProviderFactory().descriptor,
            runtime = runtime,
            executable = "/home/test/.local/bin/cline",
            clientStarter = starter,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        val attached = connection.attach(AgentSessionId("session-1"))
        assertEquals(AgentSessionState.IDLE, attached.state)
        assertEquals("Fix parser", connection.transcript(attached.id).first().text)

        connection.sendInput(attached.id, "Try another approach")
        assertEquals(listOf("Try another approach"), client.prompts)
        assertEquals(AgentSessionState.RUNNING, connection.sessions.value.single().state)

        val approvalEvent = async(start = CoroutineStart.UNDISPATCHED) {
            connection.events.first { it is AgentEvent.ApprovalRequested }
        }
        client.emit(permissionCall("session-1"))
        val approval = assertIs<AgentEvent.ApprovalRequested>(approvalEvent.await()).approval
        assertEquals(AgentSessionState.WAITING_FOR_APPROVAL, connection.sessions.value.single().state)
        connection.respondToApproval(approval.id, AgentApprovalDecision.APPROVE_ONCE)
        assertEquals(listOf(AgentApprovalDecision.APPROVE_ONCE), client.permissionDecisions)
        assertEquals(AgentSessionState.RUNNING, connection.sessions.value.single().state)

        val changedEvent = async(start = CoroutineStart.UNDISPATCHED) {
            connection.events.first { it is AgentEvent.FileChanged }
        }
        client.emit(
            call(
                "session/update",
                """
                {
                  "sessionId":"session-1",
                  "update":{
                    "sessionUpdate":"tool_call",
                    "toolCallId":"tool-live",
                    "title":"Edit live file",
                    "kind":"edit",
                    "rawInput":{"path":"src/Live.kt"}
                  }
                }
                """.trimIndent(),
            ),
        )
        assertEquals(
            "/workspace/src/Live.kt",
            assertIs<AgentEvent.FileChanged>(changedEvent.await()).file.remotePath,
        )

        val completed = async(start = CoroutineStart.UNDISPATCHED) {
            connection.events.first { it is AgentEvent.TurnCompleted }
        }
        client.complete("end_turn")
        assertTrue(assertIs<AgentEvent.TurnCompleted>(completed.await()).successful)
        runCurrent()
        assertEquals(AgentSessionState.IDLE, connection.sessions.value.single().state)
        assertEquals(
            setOf("/workspace/src/Live.kt", "/workspace/src/Durable.kt"),
            connection.changedFiles(attached.id).map { it.remotePath }.toSet(),
        )

        connection.sendInput(attached.id, "Stop this")
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            connection.events.first {
                it is AgentEvent.TurnCompleted && !it.successful
            }
        }
        connection.interrupt(attached.id)
        assertEquals(1, client.cancellations)
        client.complete("cancelled")
        assertEquals("Cline turn cancelled", assertIs<AgentEvent.TurnCompleted>(cancelled.await()).errorMessage)

        connection.close()
        assertTrue(client.closed)
    }

    @Test
    fun isolatesConcurrentSessionsAndPassesStartOptions() = runTest {
        val runtime = FakeRuntime(
            listOf(
                history("session-1", "/one"),
                history("session-2", "/two"),
            ),
        )
        val starter = RecordingStarter(
            newFactory = { options ->
                FakeClient("session-new", options.model)
            },
            resumeFactory = { session ->
                FakeClient(session.id.value, session.model)
            },
        )
        val connection = ClineAgentConnection.create(
            descriptor = ClineAgentProviderFactory().descriptor,
            runtime = runtime,
            executable = "/home/test/.local/bin/cline",
            clientStarter = starter,
            dispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        listOf("session-1", "session-2", "session-1").map {
            async { connection.attach(AgentSessionId(it)) }
        }.awaitAll()
        assertEquals(2, starter.resumed.size)

        val options = StartSessionOptions(
            workingDirectory = "/new",
            model = "qwen",
            providerOptions = mapOf("provider" to "ollama", "mode" to "plan"),
        )
        val started = connection.startSession(options)
        assertEquals("session-new", started.id.value)
        assertEquals(options, starter.started.single())
        assertEquals(3, starter.clients.size)

        connection.close()
        assertTrue(starter.clients.all(FakeClient::closed))
    }

    private class RecordingStarter(
        private val newFactory: (StartSessionOptions) -> FakeClient,
        private val resumeFactory: (AgentSession) -> FakeClient,
    ) : ClineClientStarter {
        val started = mutableListOf<StartSessionOptions>()
        val resumed = mutableListOf<AgentSession>()
        val clients = mutableListOf<FakeClient>()

        override suspend fun startNew(options: StartSessionOptions): ClineClient {
            started += options
            return newFactory(options).also(clients::add)
        }

        override suspend fun resume(session: AgentSession): ClineClient {
            resumed += session
            return resumeFactory(session).also(clients::add)
        }
    }

    private class FakeClient(
        override val sessionId: String,
        override val currentModel: String?,
    ) : ClineClient {
        private val mutableCalls = MutableSharedFlow<ClineAcpCall>(extraBufferCapacity = 32)
        override val calls: Flow<ClineAcpCall> = mutableCalls
        val prompts = mutableListOf<String>()
        val permissionDecisions = mutableListOf<AgentApprovalDecision>()
        var promptResult = CompletableDeferred<JsonObject>()
        var cancellations = 0
        var closed = false

        override suspend fun prompt(text: String): JsonObject {
            prompts += text
            return promptResult.await()
        }

        override suspend fun cancel() {
            cancellations += 1
        }

        override suspend fun respondToPermission(
            call: ClineAcpCall,
            decision: AgentApprovalDecision,
            optionIds: Map<AgentApprovalDecision, String>,
        ) {
            permissionDecisions += decision
        }

        override suspend fun rejectUnsupported(call: ClineAcpCall) = Unit

        override suspend fun close() {
            closed = true
        }

        suspend fun emit(call: ClineAcpCall) {
            mutableCalls.emit(call)
        }

        fun complete(reason: String) {
            promptResult.complete(buildJsonObject { put("stopReason", reason) })
            promptResult = CompletableDeferred()
        }
    }

    private class FakeRuntime(private val discoveries: List<String>) : RemoteAgentRuntime {
        override val hostId: String = "test-host"

        override suspend fun execute(
            command: RemoteCommand,
            timeout: Duration,
        ): RemoteCommandResult = if (command.program.endsWith("cline")) {
            RemoteCommandResult(0, "[" + discoveries.joinToString(",") + "]", "")
        } else {
            RemoteCommandResult(0, transcript, "")
        }

        override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess =
            error("Not used")
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun history(id: String, directory: String): String =
            """
            {
              "sessionId":"$id",
              "status":"completed",
              "startedAt":"2026-08-31T23:33:16.054Z",
              "updatedAt":"2026-08-31T23:34:37.511Z",
              "provider":"ollama",
              "model":"qwen",
              "cwd":"$directory",
              "prompt":"Fix parser",
              "messagesPath":"/home/test/.cline/data/sessions/$id/$id.messages.json"
            }
            """.trimIndent()

        val transcript =
            """
            {
              "messages":[
                {"id":"user","role":"user","content":"Fix parser"},
                {
                  "id":"agent",
                  "role":"assistant",
                  "content":[{
                    "type":"tool_use",
                    "id":"tool-durable",
                    "name":"write_to_file",
                    "input":{"path":"/workspace/src/Durable.kt"}
                  }]
                }
              ]
            }
            """.trimIndent()

        fun call(method: String, params: String, id: Int? = null) = ClineAcpCall(
            method,
            json.parseToJsonElement(params).jsonObject,
            id?.let(::JsonPrimitive),
        )

        fun permissionCall(sessionId: String) = call(
            "session/request_permission",
            """
            {
              "sessionId":"$sessionId",
              "toolCall":{
                "toolCallId":"approval-1",
                "title":"Run tests",
                "kind":"execute",
                "rawInput":{"command":"./gradlew test"}
              },
              "options":[
                {"optionId":"once","kind":"allow_once"},
                {"optionId":"reject","kind":"reject_once"}
              ]
            }
            """.trimIndent(),
            17,
        )
    }
}
