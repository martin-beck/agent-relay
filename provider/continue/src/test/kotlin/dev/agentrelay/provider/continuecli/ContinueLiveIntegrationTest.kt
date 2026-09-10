/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.continuecli

import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import java.io.BufferedWriter
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assume.assumeTrue
import org.junit.Test

class ContinueLiveIntegrationTest {
    @Test
    fun installedContinueAttachesStoredSessionOnLoopbackAndStops() {
        runBlocking {
            assumeTrue(System.getenv(LIVE_TEST_ENV) == "1")
            val factory = ContinueAgentProviderFactory()
            val runtime = LocalRuntime()

            assertIs<ProviderReadiness.Ready>(factory.probe(runtime))
            val connection = factory.connect(runtime)
            var port: String? = null
            try {
                val sessions = connection.refreshSessions()
                assertTrue(sessions.isNotEmpty(), "Live test requires one stored Continue session")
                val session = sessions.first()
                val attached = connection.attach(session.id)
                assertTrue(attached.id == session.id)
                assertTrue(connection.transcript(session.id).isNotEmpty())

                val serverCommand = runtime.opened.single().command
                port = serverCommand.arguments[serverCommand.arguments.indexOf("--port") + 1]
                val listeners = runtime.execute(
                    RemoteCommand("ss", listOf("-ltnH", "sport = :$port")),
                )
                assertTrue(listeners.successful)
                assertTrue(listeners.standardOutput.contains("127.0.0.1:$port"))
                assertFalse(listeners.standardOutput.contains("0.0.0.0:$port"))
                assertFalse(listeners.standardOutput.contains("*:$port"))
                assertFalse(listeners.standardOutput.contains("[::]:$port"))
            } finally {
                connection.close()
            }

            assertTrue(runtime.opened.all { it.process.isStopped() })
            val stoppedPort = checkNotNull(port)
            val listeners = runtime.execute(
                RemoteCommand("ss", listOf("-ltnH", "sport = :$stoppedPort")),
            )
            assertTrue(listeners.standardOutput.isBlank())
        }
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
            val stdout = Files.createTempFile("agent-relay-continue-", ".stdout")
            val stderr = Files.createTempFile("agent-relay-continue-", ".stderr")
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
        const val LIVE_TEST_ENV = "AGENT_RELAY_LIVE_CONTINUE"
    }
}
