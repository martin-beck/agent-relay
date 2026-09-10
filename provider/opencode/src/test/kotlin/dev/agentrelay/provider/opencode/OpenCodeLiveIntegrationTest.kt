/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.opencode

import dev.agentrelay.provider.api.AgentEvent
import dev.agentrelay.provider.api.AgentProviderConnection
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.AgentTranscriptRole
import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import dev.agentrelay.provider.api.StartSessionOptions
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.concurrent.TimeUnit
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test

class OpenCodeLiveIntegrationTest {
    @Test
    fun installedOpenCodeServesSessionsAndShutsDownCleanly() = runBlocking {
        assumeTrue(System.getenv(LIVE_TEST_ENV) == "1")
        val factory = OpenCodeAgentProviderFactory()
        val runtime = LocalRuntime()

        assertIs<ProviderReadiness.Ready>(factory.probe(runtime))
        val connection = factory.connect(runtime)
        try {
            val sessions = connection.refreshSessions()
            assertTrue(sessions.all { it.providerId == factory.descriptor.id })
            sessions.firstOrNull()?.let { session ->
                val attached = connection.attach(session.id)
                assertTrue(attached.id == session.id)
                connection.transcript(session.id)
                connection.changedFiles(session.id)
            }
        } finally {
            connection.close()
        }
        assertTrue(runtime.processes.all(LocalProcess::isStopped))
    }

    @Test
    fun installedOpenCodeCompletesConfiguredLocalInferenceTurnAndStreamsIt() = runBlocking {
        assumeTrue(
            System.getenv(LIVE_INFERENCE_TEST_ENV) == "1" ||
                System.getenv(LEGACY_LIVE_OLLAMA_TEST_ENV) == "1",
        )
        val provider = System.getenv(LIVE_PROVIDER_ENV)?.takeIf(String::isNotBlank) ?: DEFAULT_PROVIDER
        val model = System.getenv(LIVE_MODEL_ENV)?.takeIf(String::isNotBlank) ?: DEFAULT_MODEL
        val workspace = Files.createTempDirectory("agent-relay-opencode-local-inference-")
        val factory = OpenCodeAgentProviderFactory()
        val runtime = LocalRuntime()
        var connection: AgentProviderConnection? = null
        var liveEvent: Deferred<AgentEvent>? = null
        var sessionId: AgentSessionId? = null

        try {
            initializeGitWorkspace(workspace)
            assertIs<ProviderReadiness.Ready>(factory.probe(runtime))
            val activeConnection = factory.connect(runtime)
            connection = activeConnection
            val session = activeConnection.startSession(
                StartSessionOptions(
                    workingDirectory = workspace.toString(),
                    model = "$provider/$model",
                    providerOptions = mapOf("title" to "Agent Relay local inference validation"),
                ),
            )
            sessionId = session.id
            assertTrue(activeConnection.refreshSessions().any { it.id == session.id })
            val matchingLiveEvent = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(5.minutes) {
                    activeConnection.events.first {
                        it.sessionId == session.id &&
                            (it is AgentEvent.TextDelta || it is AgentEvent.MessageCompleted)
                    }
                }
            }
            liveEvent = matchingLiveEvent

            delay(500.milliseconds)
            activeConnection.sendInput(session.id, LIVE_PROMPT)
            val transcript = withTimeout(5.minutes) {
                var current = activeConnection.transcript(session.id)
                while (current.none {
                        it.role == AgentTranscriptRole.AGENT && LIVE_MARKER in it.text
                    }
                ) {
                    delay(1_000.milliseconds)
                    current = activeConnection.transcript(session.id)
                }
                current
            }

            assertTrue(
                transcript.any {
                    it.role == AgentTranscriptRole.AGENT && LIVE_MARKER in it.text
                },
            )
            assertTrue(
                matchingLiveEvent.await().sessionId == session.id,
                "OpenCode completed the local-inference turn without a mapped live-stream event",
            )
        } finally {
            liveEvent?.cancel()
            sessionId?.let { id -> runCatching { connection?.interrupt(id) } }
            try {
                connection?.close()
            } finally {
                val allProcessesStopped = runtime.processes.all(LocalProcess::isStopped)
                workspace.deleteTree()
                assertTrue(allProcessesStopped)
            }
        }
    }

    private fun Path.deleteTree() {
        if (!Files.exists(this)) return
        Files.walk(this).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun initializeGitWorkspace(workspace: Path) {
        val process = ProcessBuilder("git", "init", "--quiet", workspace.toString()).start()
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor()
            }
        }
        assertTrue(finished, "Git workspace initialization timed out")
        assertTrue(process.exitValue() == 0, "Git workspace initialization failed")
    }

    private class LocalRuntime : RemoteAgentRuntime {
        override val hostId: String = "local-live-test"
        val processes = mutableListOf<LocalProcess>()

        override suspend fun execute(
            command: RemoteCommand,
            timeout: Duration,
        ): RemoteCommandResult {
            val stdout = Files.createTempFile("agent-relay-opencode-", ".stdout")
            val stderr = Files.createTempFile("agent-relay-opencode-", ".stderr")
            try {
                val process = command.toProcessBuilder()
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile())
                    .start()
                val finished = withContext(Dispatchers.IO) {
                    process.waitFor(timeout.inWholeMilliseconds.coerceAtLeast(1), TimeUnit.MILLISECONDS)
                }
                if (!finished) {
                    process.destroyForcibly()
                    withContext(Dispatchers.IO) { process.waitFor() }
                    error("Local live-test command timed out: " + command.program)
                }
                return RemoteCommandResult(
                    process.exitValue(),
                    Files.readString(stdout),
                    Files.readString(stderr),
                )
            } finally {
                Files.deleteIfExists(stdout)
                Files.deleteIfExists(stderr)
            }
        }

        override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess =
            LocalProcess(command.toProcessBuilder().start()).also(processes::add)

        private fun RemoteCommand.toProcessBuilder(): ProcessBuilder =
            ProcessBuilder(listOf(program) + arguments).also { builder ->
                builder.environment().putAll(environment)
                workingDirectory?.let { builder.directory(java.io.File(it)) }
            }
    }

    private class LocalProcess(private val process: Process) : RemoteDuplexProcess {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val writer: BufferedWriter = process.outputStream.bufferedWriter()
        override val standardOutputLines: Flow<String> = process.inputStream.lineFlow()
        override val standardErrorLines: Flow<String> = process.errorStream.lineFlow()
        override val exitCode = MutableStateFlow<Int?>(null)

        init {
            scope.launch {
                exitCode.value = process.waitFor()
            }
        }

        override suspend fun writeLine(line: String) {
            withContext(Dispatchers.IO) {
                writer.write(line)
                writer.newLine()
                writer.flush()
            }
        }

        override suspend fun close() {
            withContext(Dispatchers.IO) {
                runCatching { writer.close() }
                if (process.isAlive) {
                    process.destroy()
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        process.waitFor()
                    }
                }
            }
            scope.cancel()
        }

        fun isStopped(): Boolean = !process.isAlive

        private fun java.io.InputStream.lineFlow(): Flow<String> = flow {
            bufferedReader().useLines { lines ->
                lines.forEach { emit(it) }
            }
        }.flowOn(Dispatchers.IO)
    }

    private companion object {
        const val LIVE_TEST_ENV = "AGENT_RELAY_LIVE_OPENCODE"
        const val LIVE_INFERENCE_TEST_ENV = "AGENT_RELAY_LIVE_OPENCODE_INFERENCE"
        const val LEGACY_LIVE_OLLAMA_TEST_ENV = "AGENT_RELAY_LIVE_OPENCODE_OLLAMA"
        const val LIVE_PROVIDER_ENV = "AGENT_RELAY_LIVE_OPENCODE_PROVIDER"
        const val LIVE_MODEL_ENV = "AGENT_RELAY_LIVE_OPENCODE_MODEL"
        const val DEFAULT_PROVIDER = "ollama"
        const val DEFAULT_MODEL = "qwen3:0.6b"
        const val LIVE_MARKER = "AGENT_RELAY_OPENCODE_LOCAL_INFERENCE_OK"
        const val LIVE_PROMPT =
            "/no_think Output only this exact token: $LIVE_MARKER. Do not use tools."
    }
}
