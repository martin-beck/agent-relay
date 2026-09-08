/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.opendesk

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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test

class OpenDeskLiveIntegrationTest {
    @Test
    fun installedOpenDeskCompletesLocalOllamaTurnAndStreamsIt() = runBlocking {
        assumeTrue(System.getenv(LIVE_TEST_ENV) == "1")
        val model = System.getenv(LIVE_MODEL_ENV)?.takeIf(String::isNotBlank) ?: DEFAULT_MODEL
        val configDirectory = Files.createTempDirectory("agent-relay-opendesk-config-")
        val workspace = Files.createTempDirectory("agent-relay-opendesk-workspace-")
        writeOpenDeskConfig(configDirectory, model)
        val factory = OpenDeskAgentProviderFactory(configDirectory.toString())
        val runtime = LocalRuntime()
        var connection: AgentProviderConnection? = null
        var eventCollector: Job? = null
        var sessionId: AgentSessionId? = null

        try {
            assertIs<ProviderReadiness.Ready>(factory.probe(runtime))
            val activeConnection = factory.connect(runtime)
            connection = activeConnection
            val events = ConcurrentLinkedQueue<AgentEvent>()
            eventCollector = launch { activeConnection.events.collect(events::add) }
            val session = activeConnection.startSession(
                StartSessionOptions(
                    workingDirectory = workspace.toString(),
                    model = "ollama/$model",
                    providerOptions = mapOf("title" to "Agent Relay live validation"),
                ),
            )
            sessionId = session.id
            assertEquals(factory.descriptor.id, session.providerId)
            assertTrue(activeConnection.refreshSessions().any { it.id == session.id })

            delay(500.milliseconds)
            activeConnection.sendInput(session.id, LIVE_PROMPT)
            val transcript = withTimeout(5.minutes) {
                var current = activeConnection.transcript(session.id)
                while (current.none {
                        it.role == AgentTranscriptRole.AGENT &&
                            LIVE_MARKER in it.text
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
                events.any {
                    it.sessionId == session.id &&
                        (it is AgentEvent.TextDelta || it is AgentEvent.MessageCompleted)
                },
                "OpenDesk completed the turn without a mapped live-stream event",
            )
        } finally {
            eventCollector?.cancel()
            sessionId?.let { id -> runCatching { connection?.interrupt(id) } }
            try {
                connection?.close()
            } finally {
                val allProcessesStopped = runtime.processes.all(LocalProcess::isStopped)
                configDirectory.deleteTree()
                workspace.deleteTree()
                assertTrue(allProcessesStopped)
            }
        }
    }

    private fun writeOpenDeskConfig(directory: Path, model: String) {
        val config = buildJsonObject {
            put("default_model", model)
            put(
                "providers",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("name", "ollama")
                            put("provider_type", "openai")
                            put("base_url", DEFAULT_OLLAMA_URL)
                            put("api_key", "ollama")
                            put("no_proxy", true)
                            put("enabled", true)
                        },
                    )
                },
            )
            put(
                "models",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("alias", model)
                            put("provider_name", "ollama")
                            put("model_name", model)
                            put("model_type", "chat")
                            put("reasoning_effort", "none")
                            put("context_length", 40_960)
                            put("max_output_tokens", 512)
                        },
                    )
                },
            )
            put(
                "task_config",
                buildJsonObject {
                    put("auto_retry", false)
                    put("generate_taskname", false)
                    put("termination_check", false)
                    put("evidence_card", false)
                },
            )
        }
        Files.writeString(directory.resolve("setting.json"), config.toString())
    }

    private fun Path.deleteTree() {
        if (!Files.exists(this)) return
        Files.walk(this).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private class LocalRuntime : RemoteAgentRuntime {
        override val hostId: String = "local-live-test"
        val processes = mutableListOf<LocalProcess>()

        override suspend fun execute(
            command: RemoteCommand,
            timeout: Duration,
        ): RemoteCommandResult {
            val stdout = Files.createTempFile("agent-relay-opendesk-", ".stdout")
            val stderr = Files.createTempFile("agent-relay-opendesk-", ".stderr")
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
        const val LIVE_TEST_ENV = "AGENT_RELAY_LIVE_OPENDESK"
        const val LIVE_MODEL_ENV = "AGENT_RELAY_LIVE_OPENDESK_MODEL"
        const val DEFAULT_MODEL = "qwen3:0.6b-opendesk"
        const val DEFAULT_OLLAMA_URL = "http://127.0.0.1:11434/v1"
        const val LIVE_MARKER = "AGENT_RELAY_OPENDESK_LIVE_OK"
        const val LIVE_PROMPT =
            "/no_think\nReply once and include the token $LIVE_MARKER. Do not use tools."
    }
}
