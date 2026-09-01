package dev.agentrelay.connection.local

import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

class LocalProcessException(
    val code: String,
    val actionableMessage: String,
    cause: Throwable? = null,
) : Exception(actionableMessage, cause)

class LocalProcessRuntime(
    workingRoot: File,
    override val hostId: String = "local-device",
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RemoteAgentRuntime, Closeable {
    private val canonicalRoot = workingRoot.canonicalFile
    private val activeProcesses = ConcurrentHashMap<Process, Unit>()
    private val activeDuplexProcesses = ConcurrentHashMap.newKeySet<LocalDuplexProcess>()
    private val closed = AtomicBoolean(false)

    init {
        require(hostId.isNotBlank()) { "Local host id must not be blank" }
        require(canonicalRoot.isDirectory) { "Local working root must be an existing directory" }
    }

    override suspend fun execute(
        command: RemoteCommand,
        timeout: Duration,
    ): RemoteCommandResult {
        require(timeout.isPositive()) { "Local command timeout must be positive" }
        validateCommand(command)
        return withContext(dispatcher) {
            coroutineScope {
                val process = start(command)
                try {
                    val stdout = async(dispatcher) {
                        process.inputStream.readBounded(MAX_CAPTURE_BYTES)
                    }
                    val stderr = async(dispatcher) {
                        process.errorStream.readBounded(MAX_CAPTURE_BYTES)
                    }
                    val exitCode = process.awaitExit(timeout)
                    RemoteCommandResult(
                        exitCode = exitCode,
                        standardOutput = stdout.await().toString(StandardCharsets.UTF_8),
                        standardError = stderr.await().toString(StandardCharsets.UTF_8),
                    )
                } catch (failure: TimeoutCancellationException) {
                    throw LocalProcessException(
                        code = "LOCAL_COMMAND_TIMEOUT",
                        actionableMessage = "The local command exceeded its time limit and was stopped.",
                        cause = failure,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: LocalProcessException) {
                    throw failure
                } catch (failure: OutputLimitExceededException) {
                    throw LocalProcessException(
                        code = "LOCAL_OUTPUT_LIMIT",
                        actionableMessage = "The local command exceeded the safe output limit and was stopped.",
                        cause = failure,
                    )
                } catch (failure: Throwable) {
                    throw LocalProcessException(
                        code = "LOCAL_COMMAND_FAILED",
                        actionableMessage = "The local command could not be completed.",
                        cause = failure,
                    )
                } finally {
                    activeProcesses.remove(process)
                    stopProcess(process)
                }
            }
        }
    }

    override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess {
        validateCommand(command)
        return withContext(dispatcher) {
            val process = start(command)
            val managed = LocalDuplexProcess(
                process = process,
                dispatcher = dispatcher,
                onClosed = {
                    activeDuplexProcesses.remove(it)
                    activeProcesses.remove(process)
                },
            )
            activeDuplexProcesses += managed
            if (closed.get()) {
                managed.closeImmediately()
                throw LocalProcessException(
                    code = "LOCAL_RUNTIME_CLOSED",
                    actionableMessage = "The local connection closed while starting a process.",
                )
            }
            managed.startMonitoring()
            managed
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) {
            return
        }
        activeDuplexProcesses.toList().forEach { it.closeImmediately() }
        activeDuplexProcesses.clear()
        activeProcesses.keys.forEach(::stopProcess)
        activeProcesses.clear()
    }

    private fun start(command: RemoteCommand): Process {
        if (closed.get()) {
            throw LocalProcessException(
                code = "LOCAL_RUNTIME_CLOSED",
                actionableMessage = "The local connection is closed. Reconnect before retrying.",
            )
        }
        val workingDirectory = resolveWorkingDirectory(command.workingDirectory)
        val process = try {
            ProcessBuilder(listOf(command.program) + command.arguments)
                .directory(workingDirectory)
                .also { builder -> builder.environment().putAll(command.environment) }
                .start()
        } catch (failure: Throwable) {
            throw LocalProcessException(
                code = "LOCAL_PROCESS_START_FAILED",
                actionableMessage = "The local agent process could not be started.",
                cause = failure,
            )
        }
        if (closed.get()) {
            stopProcess(process)
            throw LocalProcessException(
                code = "LOCAL_RUNTIME_CLOSED",
                actionableMessage = "The local connection closed while starting a process.",
            )
        }
        activeProcesses[process] = Unit
        return process
    }

    private fun resolveWorkingDirectory(value: String?): File {
        val candidate = if (value == null) {
            canonicalRoot
        } else {
            val requested = File(value)
            (if (requested.isAbsolute) requested else File(canonicalRoot, value)).canonicalFile
        }
        if (!candidate.isDirectory || !candidate.toPath().startsWith(canonicalRoot.toPath())) {
            throw LocalProcessException(
                code = "LOCAL_WORKING_DIRECTORY_DENIED",
                actionableMessage = "The requested local working directory is outside the app workspace.",
            )
        }
        return candidate
    }

    private fun validateCommand(command: RemoteCommand) {
        val values = buildList {
            add(command.program)
            addAll(command.arguments)
            addAll(command.environment.keys)
            addAll(command.environment.values)
            command.workingDirectory?.let(::add)
        }
        require(values.none { '\u0000' in it }) { "Local process values must not contain NUL" }
    }

    private suspend fun Process.awaitExit(timeout: Duration): Int =
        withTimeout(maxOf(1L, timeout.inWholeMilliseconds)) {
            while (isAlive) {
                delay(PROCESS_POLL_MILLIS)
            }
            exitValue()
        }

    private class LocalDuplexProcess(
        private val process: Process,
        private val dispatcher: CoroutineDispatcher,
        private val onClosed: (LocalDuplexProcess) -> Unit,
    ) : RemoteDuplexProcess {
        private val processJob = SupervisorJob()
        private val scope = CoroutineScope(processJob + dispatcher)
        private val closed = AtomicBoolean(false)
        private val writerMutex = Mutex()
        private val stdoutLines = kotlinx.coroutines.channels.Channel<String>(LINE_BUFFER_CAPACITY)
        private val stderrLines = kotlinx.coroutines.channels.Channel<String>(LINE_BUFFER_CAPACITY)
        private val mutableExitCode = MutableStateFlow<Int?>(null)

        override val standardOutputLines: Flow<String> = stdoutLines.receiveAsFlow()
        override val standardErrorLines: Flow<String> = stderrLines.receiveAsFlow()
        override val exitCode: StateFlow<Int?> = mutableExitCode.asStateFlow()

        fun startMonitoring() {
            val stdoutReader = readLines(process.inputStream, stdoutLines)
            val stderrReader = readLines(process.errorStream, stderrLines)
            scope.launch {
                try {
                    while (process.isAlive) {
                        delay(PROCESS_POLL_MILLIS)
                    }
                    mutableExitCode.compareAndSet(null, process.exitValue())
                    stdoutReader.join()
                    stderrReader.join()
                } finally {
                    if (closed.compareAndSet(false, true)) {
                        stdoutLines.close()
                        stderrLines.close()
                        onClosed(this@LocalDuplexProcess)
                        processJob.cancel()
                    }
                }
            }
        }

        override suspend fun writeLine(line: String) {
            require('\u0000' !in line) { "Local process input must not contain NUL" }
            check(!closed.get()) { "Local process is closed" }
            check(process.isAlive) { "Local process has exited" }
            writerMutex.withLock {
                withContext(dispatcher) {
                    process.outputStream.write(line.toByteArray(StandardCharsets.UTF_8))
                    process.outputStream.write('\n'.code)
                    process.outputStream.flush()
                }
            }
        }

        override suspend fun close() {
            withContext(dispatcher) {
                closeImmediately()
            }
        }

        fun closeImmediately(exitCode: Int = PROCESS_CLOSED_EXIT_CODE) {
            if (!closed.compareAndSet(false, true)) {
                return
            }
            stopProcess(process)
            mutableExitCode.compareAndSet(null, exitCode)
            stdoutLines.close()
            stderrLines.close()
            onClosed(this)
            scope.cancel()
        }

        private fun readLines(
            input: InputStream,
            destination: kotlinx.coroutines.channels.Channel<String>,
        ): Job = scope.launch {
            try {
                input.readBoundedLines(MAX_LINE_BYTES) { destination.send(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                destination.close(failure)
                closeImmediately(PROCESS_IO_FAILURE_EXIT_CODE)
            }
        }
    }

    companion object {
        private const val MAX_CAPTURE_BYTES = 8 * 1024 * 1024
        private const val MAX_LINE_BYTES = 1024 * 1024
        private const val LINE_BUFFER_CAPACITY = 256
        private const val PROCESS_POLL_MILLIS = 20L
        private const val PROCESS_CLOSED_EXIT_CODE = -1
        private const val PROCESS_IO_FAILURE_EXIT_CODE = -2
    }
}

private class OutputLimitExceededException : Exception()

private fun stopProcess(process: Process) {
    runCatching { process.outputStream.close() }
    runCatching { process.inputStream.close() }
    runCatching { process.errorStream.close() }
    if (process.isAlive) {
        process.destroy()
    }
    if (process.isAlive) {
        process.destroyForcibly()
    }
}

private fun InputStream.readBounded(maxBytes: Int): ByteArray {
    val result = ByteArrayOutputStream(minOf(maxBytes, 8192))
    val buffer = ByteArray(8192)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) {
            break
        }
        total += read
        if (total > maxBytes) {
            throw OutputLimitExceededException()
        }
        result.write(buffer, 0, read)
    }
    return result.toByteArray()
}

private suspend fun InputStream.readBoundedLines(
    maxLineBytes: Int,
    emit: suspend (String) -> Unit,
) {
    val line = ByteArrayOutputStream()
    while (true) {
        val next = read()
        if (next < 0) {
            if (line.size() > 0) {
                emit(line.toString(StandardCharsets.UTF_8))
            }
            return
        }
        if (next == '\n'.code) {
            val bytes = line.toByteArray()
            val length = if (bytes.lastOrNull() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
            emit(String(bytes, 0, length, StandardCharsets.UTF_8))
            line.reset()
        } else {
            if (line.size() >= maxLineBytes) {
                throw OutputLimitExceededException()
            }
            line.write(next)
        }
    }
}
