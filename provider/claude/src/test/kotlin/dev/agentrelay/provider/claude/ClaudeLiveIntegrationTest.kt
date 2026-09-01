package dev.agentrelay.provider.claude

import dev.agentrelay.provider.api.AgentTranscriptRole
import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
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

class ClaudeLiveIntegrationTest {
    @Test
    fun installedClaudeResumesStoredSessionAndStopsCleanly() = runBlocking {
        assumeTrue(System.getenv(LIVE_TEST_ENV) == "1")
        val smokeSessionId = requiredEnvironment(LIVE_SESSION_ID_ENV)
        val factory = ClaudeAgentProviderFactory()
        val runtime = LocalRuntime()

        assertIs<ProviderReadiness.Ready>(factory.probe(runtime))
        val connection = factory.connect(runtime)
        try {
            val session = connection.refreshSessions()
                .firstOrNull { it.id.value == smokeSessionId }
            assertTrue(session != null, "Live test requires the durable Claude smoke session")

            val attached = connection.attach(session.id)
            assertEquals(session.id, attached.id)
            assertTrue(
                connection.transcript(session.id).any {
                    it.role == AgentTranscriptRole.AGENT && it.text.trim() == "READY"
                },
            )

            val opened = runtime.opened.single()
            assertTrue(opened.process.isRunning())
            assertTrue(opened.command.arguments.containsAll(listOf("--resume", smokeSessionId)))
            assertTrue(opened.command.arguments.containsAll(listOf("--permission-mode", "manual")))
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
            val stdout = Files.createTempFile("agent-relay-claude-", ".stdout")
            val stderr = Files.createTempFile("agent-relay-claude-", ".stderr")
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

        fun isRunning(): Boolean = process.isAlive

        fun isStopped(): Boolean = !process.isAlive

        private fun java.io.InputStream.lineFlow(): Flow<String> = channelFlow {
            launch(Dispatchers.IO) {
                bufferedReader().useLines { lines ->
                    lines.forEach { send(it) }
                }
            }
        }
    }

    private companion object {
        const val LIVE_TEST_ENV = "AGENT_RELAY_LIVE_CLAUDE"
        const val LIVE_SESSION_ID_ENV = "AGENT_RELAY_LIVE_CLAUDE_SESSION_ID"
    }
}
