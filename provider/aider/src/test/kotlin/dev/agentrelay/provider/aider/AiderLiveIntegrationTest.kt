/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.aider

import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import dev.agentrelay.provider.api.StartSessionOptions
import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.UUID
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

class AiderLiveIntegrationTest {
    @Test
    fun installedAiderStartsSafeHelperWithoutInferenceAndStopsCleanly() = runBlocking {
        assumeTrue(System.getenv(LIVE_TEST_ENV) == "1")
        val runtime = LocalRuntime()
        val smokeModel = requiredEnvironment(LIVE_MODEL_ENV)
        assertIs<ProviderReadiness.Ready>(AiderAgentProviderFactory().probe(runtime))

        val executable = resolveAiderExecutable()
        val shebang = Files.newBufferedReader(executable).use { it.readLine() }
        val interpreter = shebang.removePrefix("#!").substringBefore(' ')
        assertTrue(Path.of(interpreter).isAbsolute)

        val stateRoot = Files.createTempDirectory("agent-relay-aider-live-")
        val sessionId = UUID.randomUUID().toString()
        try {
            val client = AiderProcessClient.startNew(
                runtime = runtime,
                interpreter = interpreter,
                stateRoot = stateRoot.toString(),
                sessionId = dev.agentrelay.provider.api.AgentSessionId(sessionId),
                options = StartSessionOptions(
                    workingDirectory = stateRoot.toString(),
                    model = smokeModel,
                ),
            )
            try {
                assertEquals(smokeModel, client.currentModel)
                assertTrue(Files.isRegularFile(Path.of(client.stateDirectory, "session.json")))
                assertTrue(runtime.opened.single().process.isRunning())
                assertTrue(
                    runtime.opened.single().command.arguments.none {
                        it.contains("Reply with")
                    },
                )
            } finally {
                client.close()
            }
            assertTrue(runtime.opened.all { it.process.isStopped() })
        } finally {
            Files.walk(stateRoot).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun installedAiderCompletesBoundedPromptThroughSafeHelper() = runBlocking {
        assumeTrue(System.getenv(LIVE_INFERENCE_ENV) == "1")
        val runtime = LocalRuntime()
        val smokeModel = requiredEnvironment(LIVE_MODEL_ENV)
        val executable = resolveAiderExecutable()
        val shebang = Files.newBufferedReader(executable).use { it.readLine() }
        val interpreter = shebang.removePrefix("#!").substringBefore(' ')
        val stateRoot = Files.createTempDirectory("agent-relay-aider-inference-")
        val sessionId = UUID.randomUUID().toString()
        try {
            val client = AiderProcessClient.startNew(
                runtime = runtime,
                interpreter = interpreter,
                stateRoot = stateRoot.toString(),
                sessionId = dev.agentrelay.provider.api.AgentSessionId(sessionId),
                options = StartSessionOptions(
                    workingDirectory = stateRoot.toString(),
                    model = smokeModel,
                ),
            )
            try {
                val result = client.prompt(
                    "Do not modify files. Include the exact marker $LIVE_MARKER in your response.",
                )
                assertTrue(LIVE_MARKER in result.text)
                assertEquals(emptyList(), result.files)
            } finally {
                client.close()
            }
            assertTrue(runtime.opened.all { it.process.isStopped() })
        } finally {
            Files.walk(stateRoot).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun resolveAiderExecutable(): Path {
        val configured = System.getenv(AIDER_EXECUTABLE_ENV)
            ?.takeIf { it.isNotBlank() }
            ?.let(Path::of)
        val discovered = System.getenv("PATH")
            .orEmpty()
            .split(File.pathSeparatorChar)
            .asSequence()
            .filter { it.isNotBlank() }
            .map { Path.of(it).resolve("aider") }
            .firstOrNull(Files::isExecutable)
        return requireNotNull(configured ?: discovered) {
            "Aider executable was not configured or found on PATH"
        }.toRealPath()
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
        override val hostId = "local-live-test"
        val opened = mutableListOf<OpenedProcess>()

        override suspend fun execute(
            command: RemoteCommand,
            timeout: Duration,
        ): RemoteCommandResult {
            val stdout = Files.createTempFile("agent-relay-aider-", ".stdout")
            val stderr = Files.createTempFile("agent-relay-aider-", ".stderr")
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
        const val LIVE_TEST_ENV = "AGENT_RELAY_LIVE_AIDER"
        const val LIVE_INFERENCE_ENV = "AGENT_RELAY_LIVE_AIDER_INFERENCE"
        const val AIDER_EXECUTABLE_ENV = "AGENT_RELAY_LIVE_AIDER_EXECUTABLE"
        const val LIVE_MODEL_ENV = "AGENT_RELAY_LIVE_AIDER_MODEL"
        const val LIVE_MARKER = "AGENT_RELAY_AIDER_LOCAL_INFERENCE_OK"
    }
}
