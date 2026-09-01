package dev.agentrelay.provider.aider

import dev.agentrelay.provider.api.AgentEvent
import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.provider.api.AgentSession
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import dev.agentrelay.provider.api.StartSessionOptions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AiderAgentConnectionTest {
    @Test
    fun discoversAttachesReadsHistoryAndMapsCompletedPrompt() = runTest {
        val runtime = FakeRuntime()
        val starter = FakeStarter()
        val connection = AiderAgentConnection.create(
            descriptor = AiderAgentProviderFactory().descriptor,
            runtime = runtime,
            interpreter = "/tools/aider/python",
            stateRoot = STATE_ROOT,
            clientStarter = starter,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        assertEquals(listOf(EXISTING_ID), connection.sessions.value.map { it.id.value })

        connection.attach(AgentSessionId(EXISTING_ID))
        assertEquals(EXISTING_ID, starter.resumed.single().id.value)
        val transcript = connection.transcript(AgentSessionId(EXISTING_ID))
        assertEquals(listOf("hello", "world"), transcript.map { it.text })

        val events = mutableListOf<AgentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            connection.events.toList(events)
        }
        val started = connection.startSession(
            StartSessionOptions(
                workingDirectory = "/work/new",
                model = "test/model",
                providerOptions = mapOf("files" to "[\"Main.kt\"]"),
            ),
        )
        val client = starter.clients.getValue(started.id)
        client.result = AiderPromptResult(
            text = "finished",
            files = listOf(
                AiderFileResult("/work/new/Main.kt", AgentFileChangeKind.MODIFIED),
            ),
        )
        connection.sendInput(started.id, "make the change")
        advanceUntilIdle()

        assertEquals(
            AgentSessionState.IDLE,
            connection.sessions.value.first {
                it.id == started.id
            }.state,
        )
        assertTrue(
            events.any {
                it is AgentEvent.MessageCompleted && it.sessionId == started.id &&
                    it.text == "finished"
            },
        )
        assertTrue(
            events.any {
                it is AgentEvent.FileChanged && it.file.remotePath == "/work/new/Main.kt"
            },
        )
        assertTrue(
            events.any {
                it is AgentEvent.TurnCompleted && it.sessionId == started.id && it.successful
            },
        )
        assertEquals(
            listOf("/work/new/Main.kt"),
            connection.changedFiles(started.id).map { it.remotePath },
        )
        connection.close()
    }

    @Test
    fun interruptStopsOnlyTheTargetProcessAndLeavesSessionReusable() = runTest {
        val runtime = FakeRuntime()
        val starter = FakeStarter(blocking = true)
        val connection = AiderAgentConnection.create(
            descriptor = AiderAgentProviderFactory().descriptor,
            runtime = runtime,
            interpreter = "/tools/aider/python",
            stateRoot = STATE_ROOT,
            clientStarter = starter,
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        val events = mutableListOf<AgentEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            connection.events.toList(events)
        }
        val first = connection.startSession(StartSessionOptions(workingDirectory = "/work/one"))
        val second = connection.startSession(StartSessionOptions(workingDirectory = "/work/two"))
        connection.sendInput(first.id, "wait")
        runCurrent()
        assertEquals(
            AgentSessionState.RUNNING,
            connection.sessions.value.first {
                it.id == first.id
            }.state,
        )

        connection.interrupt(first.id)
        advanceUntilIdle()
        assertTrue(starter.clients.getValue(first.id).closed)
        assertTrue(!starter.clients.getValue(second.id).closed)
        assertEquals(
            AgentSessionState.IDLE,
            connection.sessions.value.first {
                it.id == first.id
            }.state,
        )
        assertTrue(
            events.any {
                it is AgentEvent.TurnCompleted && it.sessionId == first.id &&
                    !it.successful && it.errorMessage == "Aider turn interrupted"
            },
        )
        connection.close()
    }

    private class FakeRuntime : RemoteAgentRuntime {
        override val hostId = "test"

        override suspend fun execute(
            command: RemoteCommand,
            timeout: Duration,
        ): RemoteCommandResult = when {
            command.arguments.contains(AIDER_DISCOVER_SCRIPT) ->
                result(
                    """
                    [{
                      "id":"$EXISTING_ID",
                      "title":"existing",
                      "preview":"old",
                      "workingDirectory":"/work/existing",
                      "model":"test/model",
                      "createdAt":1,
                      "updatedAt":2,
                      "stateDirectory":"$STATE_ROOT/$EXISTING_ID",
                      "chatHistoryPath":"$STATE_ROOT/$EXISTING_ID/chat-history.md",
                      "files":"[]",
                      "changedFiles":{}
                    }]
                    """.trimIndent(),
                )
            command.arguments.contains(AIDER_READ_TRANSCRIPT_SCRIPT) ->
                result(
                    """
                    [
                      {"id":"u","role":"user","text":"hello"},
                      {"id":"a","role":"assistant","text":"world"}
                    ]
                    """.trimIndent(),
                )
            else -> error("Unexpected command: $command")
        }

        override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess =
            error("Unexpected process: $command")

        private fun result(output: String) = RemoteCommandResult(0, output, "")
    }

    private class FakeStarter(
        private val blocking: Boolean = false,
    ) : AiderClientStarter {
        val clients = linkedMapOf<AgentSessionId, FakeClient>()
        val resumed = mutableListOf<AgentSession>()

        override suspend fun startNew(
            sessionId: AgentSessionId,
            options: StartSessionOptions,
        ): AiderClient =
            FakeClient(
                sessionId,
                options.model,
                options.workingDirectory ?: "/work",
                blocking,
            ).also { clients[sessionId] = it }

        override suspend fun resume(session: AgentSession): AiderClient {
            resumed += session
            return clients.getOrPut(session.id) {
                FakeClient(
                    session.id,
                    session.model,
                    session.workingDirectory ?: "/work",
                    blocking,
                )
            }
        }
    }

    private class FakeClient(
        override val sessionId: AgentSessionId,
        override val currentModel: String?,
        workspace: String,
        private val blocking: Boolean,
    ) : AiderClient {
        override val stateDirectory = "$STATE_ROOT/${sessionId.value}"
        override val chatHistoryPath = "$stateDirectory/chat-history.md"
        override val filesJson = "[]"
        var result = AiderPromptResult("", emptyList())
        val gate = CompletableDeferred<AiderPromptResult>()
        var closed = false

        override suspend fun prompt(text: String): AiderPromptResult =
            if (blocking) gate.await() else result

        override suspend fun close() {
            closed = true
            if (!gate.isCompleted) {
                gate.completeExceptionally(IllegalStateException("process stopped"))
            }
        }
    }

    private companion object {
        const val STATE_ROOT = "/home/test/.local/state/agent-relay/aider"
        const val EXISTING_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
    }
}
