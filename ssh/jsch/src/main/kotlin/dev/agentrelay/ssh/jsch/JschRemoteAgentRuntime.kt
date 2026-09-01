package dev.agentrelay.ssh.jsch

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteCommandResult
import dev.agentrelay.provider.api.RemoteDuplexProcess
import dev.agentrelay.ssh.api.SshConnectionException
import dev.agentrelay.ssh.api.SshFailure
import dev.agentrelay.ssh.api.SshFailureCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

class JschRemoteAgentRuntime internal constructor(
    private val session: Session,
    override val hostId: String,
    private val channelConnectTimeout: Duration,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RemoteAgentRuntime {
    override val fileAccess: dev.agentrelay.provider.api.RemoteFileAccess =
        JschRemoteFileAccess(session, channelConnectTimeout, dispatcher)
    init {
        require(channelConnectTimeout.isPositive()) { "SSH channel connect timeout must be positive" }
        require(channelConnectTimeout.inWholeMilliseconds <= Int.MAX_VALUE)
    }

    override suspend fun execute(
        command: RemoteCommand,
        timeout: Duration,
    ): RemoteCommandResult {
        require(timeout.isPositive()) { "Remote command timeout must be positive" }
        return withContext(dispatcher) {
            coroutineScope {
                val channel = openExecChannel(command)
                try {
                    val stdout = channel.inputStream
                    val stderr = channel.errStream
                    channel.connect(channelConnectTimeout.inWholeMilliseconds.toInt())
                    val stdoutResult = async(dispatcher) { stdout.readBounded(MAX_CAPTURE_BYTES) }
                    val stderrResult = async(dispatcher) { stderr.readBounded(MAX_CAPTURE_BYTES) }
                    withTimeout(timeout.inWholeMilliseconds) {
                        while (!channel.isClosed) {
                            delay(CHANNEL_POLL_MILLIS)
                        }
                    }
                    RemoteCommandResult(
                        exitCode = channel.exitStatus,
                        standardOutput = stdoutResult.await().toString(StandardCharsets.UTF_8),
                        standardError = stderrResult.await().toString(StandardCharsets.UTF_8),
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: JSchException) {
                    throw channelFailure(failure)
                } finally {
                    channel.disconnect()
                }
            }
        }
    }

    override suspend fun openProcess(command: RemoteCommand): RemoteDuplexProcess = withContext(dispatcher) {
        val channel = openExecChannel(command)
        try {
            val stdout = channel.inputStream
            val stderr = channel.errStream
            val stdin = channel.outputStream
            channel.connect(channelConnectTimeout.inWholeMilliseconds.toInt())
            JschDuplexProcess(
                channel = channel,
                stdin = stdin,
                stdout = stdout,
                stderr = stderr,
                dispatcher = dispatcher,
            )
        } catch (cancelled: CancellationException) {
            channel.disconnect()
            throw cancelled
        } catch (failure: Throwable) {
            channel.disconnect()
            throw channelFailure(failure)
        }
    }

    private fun openExecChannel(command: RemoteCommand): ChannelExec {
        if (!session.isConnected) {
            throw channelFailure(IllegalStateException("SSH session is disconnected"))
        }
        val channel = try {
            session.openChannel("exec") as ChannelExec
        } catch (failure: JSchException) {
            throw channelFailure(failure)
        }
        channel.setPty(false)
        channel.setAgentForwarding(false)
        channel.setCommand(PosixCommandEncoder.encode(command))
        return channel
    }

    private fun channelFailure(cause: Throwable): SshConnectionException = SshConnectionException(
        failure = SshFailure(
            category = SshFailureCategory.NETWORK,
            code = "SSH_CHANNEL_FAILURE",
            actionableMessage =
            "The remote SSH command channel could not be opened or was interrupted.",
            recoverable = true,
        ),
        cause = cause,
    )

    private class JschDuplexProcess(
        private val channel: ChannelExec,
        private val stdin: OutputStream,
        stdout: InputStream,
        stderr: InputStream,
        dispatcher: CoroutineDispatcher,
    ) : RemoteDuplexProcess {
        private val processJob = SupervisorJob()
        private val scope = CoroutineScope(processJob + dispatcher)
        private val closed = AtomicBoolean(false)
        private val writerMutex = Mutex()
        private val stdoutLines = kotlinx.coroutines.channels.Channel<String>(
            capacity = LINE_BUFFER_CAPACITY,
        )
        private val stderrLines = kotlinx.coroutines.channels.Channel<String>(
            capacity = LINE_BUFFER_CAPACITY,
        )
        private val mutableExitCode = MutableStateFlow<Int?>(null)

        override val standardOutputLines: Flow<String> = stdoutLines.receiveAsFlow()
        override val standardErrorLines: Flow<String> = stderrLines.receiveAsFlow()
        override val exitCode: StateFlow<Int?> = mutableExitCode.asStateFlow()

        init {
            val stdoutReader = readLines(stdout, stdoutLines)
            val stderrReader = readLines(stderr, stderrLines)
            scope.launch {
                try {
                    while (!channel.isClosed) {
                        delay(CHANNEL_POLL_MILLIS)
                    }
                    stdoutReader.join()
                    stderrReader.join()
                    mutableExitCode.compareAndSet(null, channel.exitStatus)
                } finally {
                    stdoutLines.close()
                    stderrLines.close()
                    channel.disconnect()
                }
            }
        }

        override suspend fun writeLine(line: String) {
            require('\u0000' !in line) { "Remote process input must not contain NUL" }
            check(!closed.get()) { "Remote process is closed" }
            writerMutex.withLock {
                withContext(Dispatchers.IO) {
                    stdin.write(line.toByteArray(StandardCharsets.UTF_8))
                    stdin.write('\n'.code)
                    stdin.flush()
                }
            }
        }

        override suspend fun close() {
            if (!closed.compareAndSet(false, true)) {
                return
            }
            withContext(Dispatchers.IO) {
                runCatching { stdin.close() }
                channel.disconnect()
            }
            mutableExitCode.compareAndSet(null, PROCESS_CLOSED_EXIT_CODE)
            stdoutLines.close()
            stderrLines.close()
            scope.cancel()
        }

        private fun readLines(
            input: InputStream,
            destination: kotlinx.coroutines.channels.Channel<String>,
        ): Job = scope.launch {
            try {
                input.readBoundedLines(MAX_LINE_BYTES) {
                    destination.send(it)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Throwable) {
                destination.close(failure)
                mutableExitCode.compareAndSet(null, PROCESS_IO_FAILURE_EXIT_CODE)
                channel.disconnect()
            }
        }
    }

    companion object {
        private const val MAX_CAPTURE_BYTES = 8 * 1024 * 1024
        private const val MAX_LINE_BYTES = 1024 * 1024
        private const val LINE_BUFFER_CAPACITY = 256
        private const val CHANNEL_POLL_MILLIS = 20L
        private const val PROCESS_CLOSED_EXIT_CODE = -1
        private const val PROCESS_IO_FAILURE_EXIT_CODE = -2
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
        check(total <= maxBytes) { "Remote command output exceeded the safe capture limit" }
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
            check(line.size() < maxLineBytes) { "Remote process line exceeded the safe limit" }
            line.write(next)
        }
    }
}
