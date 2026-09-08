/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.clinecli

import dev.agentrelay.provider.api.AgentTranscriptRole
import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import dev.agentrelay.provider.api.StartSessionOptions
import java.io.BufferedWriter
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Test

class ClineLiveIntegrationTest {
    @Test
    fun installedClineReadsHistoryStartsAcpSessionAndStopsCleanly() = runBlocking {
        assumeTrue(System.getenv(LIVE_TEST_ENV) == "1")
        val smokeSessionId = requiredEnvironment(LIVE_SESSION_ID_ENV)
        val smokeDirectory = requiredEnvironment(LIVE_DIRECTORY_ENV)
        val smokeModel = requiredEnvironment(LIVE_MODEL_ENV)
        val smokeProvider = requiredEnvironment(LIVE_PROVIDER_ENV)
        val factory = ClineAgentProviderFactory()
        val runtime = LocalRuntime()

        assertIs<ProviderReadiness.Ready>(factory.probe(runtime))
        val connection = factory.connect(runtime)
        try {
            val smoke = connection.refreshSessions()
                .firstOrNull { it.id.value == smokeSessionId }
            assertTrue(smoke != null, "Live test requires the durable Cline smoke session")
            assertTrue(
                connection.transcript(smoke.id).any {
                    it.role == AgentTranscriptRole.AGENT && it.text.trim() == "READY"
                },
            )

            val started = connection.startSession(
                StartSessionOptions(
                    workingDirectory = smokeDirectory,
                    model = smokeModel,
                    providerOptions = mapOf(
                        "provider" to smokeProvider,
                        "mode" to "plan",
                    ),
                ),
            )
            assertEquals(smokeModel, started.model)
            val opened = runtime.opened.single()
            assertTrue(opened.process.isRunning())
            assertEquals(listOf("--acp", "--auto-approve", "false"), opened.command.arguments)
            assertEquals(smokeProvider, opened.command.environment["CLINE_PROVIDER"])
        } finally {
            connection.close()
        }

        assertTrue(runtime.opened.all { it.process.isStopped() })
    }

    private fun requiredEnvironment(name: String): String =
        requireNotNull(System.getenv(name)?.takeIf { it.isNotBlank() }) {
            "$name must be set for the opt-in live test"
        }

    private data class OpenedProcess(
        val command: RemoteCommand,
        val process: LocalProcess,
    )

    private class LocalRuntime : RemoteAgentRuntime {
        override val hostId: String = "local-live-test"
        val opened = mutableListOf<OpenedProcess>()

        override suspend fun execute(
            command: RemoteCommand,
            timeout: Duration,
        ): RemoteCommandResult {
            val stdout = Files.createTempFile("agent-relay-cline-", ".stdout")
            val stderr = Files.createTempFile("agent-relay-cline-", ".stderr")
            try {
                val process = command.toProcessBuilder()
                    .redirectOutput(stdout.toFile())
                    .redirectError(stderr.toFile())
                    .start()
                val finished = withContext(Dispatchers.IO) {
                    process.waitFor(
                        timeout.inWholeMilliseconds.coerceAtLeast(1),
                        TimeUnit.MILLISECONDS,
                    )
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

        override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess {
            val process = LocalProcess(command.toProcessBuilder().start())
            opened += OpenedProcess(command, process)
            return process
        }

        private fun RemoteCommand.toProcessBuilder(): ProcessBuilder =
            ProcessBuilder(listOf(program) + arguments).also { builder ->
                builder.environment().putAll(environment)
                workingDirectory?.let { builder.directory(java.io.File(it)) }
            }
    }

    private class LocalProcess(private val process: Process) : RemoteDuplexProcess {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val writer: BufferedWriter = process.outputStream.bufferedWriter()
        private val descendants = mutableListOf<ProcessHandle>()
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
                descendants += process.toHandle().descendants().toList()
                runCatching { writer.close() }
                descendants.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy)
                if (process.isAlive) process.destroy()
                if (process.isAlive && !process.waitFor(5, TimeUnit.SECONDS)) {
                    descendants.filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly)
                    process.destroyForcibly()
                    process.waitFor()
                }
                awaitDescendants(2, TimeUnit.SECONDS)
                descendants.filter(ProcessHandle::isAlive)
                    .forEach(ProcessHandle::destroyForcibly)
                awaitDescendants(3, TimeUnit.SECONDS)
            }
            scope.cancel()
        }

        private fun awaitDescendants(timeout: Long, unit: TimeUnit) {
            val deadline = System.nanoTime() + unit.toNanos(timeout)
            while (descendants.any(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
                Thread.sleep(50)
            }
        }

        fun isRunning(): Boolean = process.isAlive

        fun isStopped(): Boolean =
            !process.isAlive && descendants.none(ProcessHandle::isAlive)

        private fun java.io.InputStream.lineFlow(): Flow<String> = channelFlow {
            launch(Dispatchers.IO) {
                bufferedReader().useLines { lines ->
                    lines.forEach { send(it) }
                }
            }
        }
    }

    private companion object {
        const val LIVE_TEST_ENV = "AGENT_RELAY_LIVE_CLINE"
        const val LIVE_SESSION_ID_ENV = "AGENT_RELAY_LIVE_CLINE_SESSION_ID"
        const val LIVE_DIRECTORY_ENV = "AGENT_RELAY_LIVE_CLINE_DIRECTORY"
        const val LIVE_MODEL_ENV = "AGENT_RELAY_LIVE_CLINE_MODEL"
        const val LIVE_PROVIDER_ENV = "AGENT_RELAY_LIVE_CLINE_PROVIDER"
    }
}
