/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.opencode

import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import java.io.BufferedWriter
import java.nio.file.Files
import java.util.concurrent.TimeUnit
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
    }
}
