package dev.agentrelay.ssh.jsch

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import dev.agentrelay.provider.api.MAX_REMOTE_FILE_CHUNK_BYTES
import dev.agentrelay.provider.api.RemoteFileAccess
import dev.agentrelay.provider.api.RemoteFileAccessException
import dev.agentrelay.provider.api.RemoteFileReference
import dev.agentrelay.provider.api.RemoteFileRevision
import dev.agentrelay.provider.api.RemoteFileSnapshot
import java.io.InputStream
import java.security.MessageDigest
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

internal class JschRemoteFileAccess(
    private val session: Session,
    private val channelConnectTimeout: Duration,
    private val dispatcher: CoroutineDispatcher,
) : RemoteFileAccess {
    override suspend fun inspect(
        reference: RemoteFileReference,
        calculateSha256: Boolean,
    ): RemoteFileSnapshot = withContext(dispatcher) {
        withSftpChannel { channel ->
            val resolved = resolve(channel, reference)
            val checksum = if (calculateSha256) {
                channel.get(resolved.canonicalPath).use { input -> input.sha256() }
            } else {
                null
            }
            val after = if (checksum == null) resolved else resolve(channel, reference)
            if (checksum != null) {
                requireUnchanged(after.revision, resolved.revision)
            }
            RemoteFileSnapshot(
                reference = reference,
                revision = after.revision.copy(sha256 = checksum),
            )
        }
    }

    override fun read(
        reference: RemoteFileReference,
        expectedRevision: RemoteFileRevision,
        chunkSizeBytes: Int,
    ): Flow<ByteArray> {
        require(chunkSizeBytes in 1..MAX_REMOTE_FILE_CHUNK_BYTES) {
            "Remote file chunk size is outside the safe range"
        }
        return flow {
            try {
                withSftpChannel { channel ->
                    val before = resolve(channel, reference)
                    requireUnchanged(before.revision, expectedRevision)
                    val digest = expectedRevision.sha256?.let { MessageDigest.getInstance("SHA-256") }
                    var total = 0L
                    channel.get(before.canonicalPath).use { input ->
                        val buffer = ByteArray(chunkSizeBytes)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) {
                                break
                            }
                            total += read
                            digest?.update(buffer, 0, read)
                            emit(buffer.copyOf(read))
                        }
                    }
                    if (total != expectedRevision.sizeBytes) {
                        changed()
                    }
                    val after = resolve(channel, reference)
                    requireUnchanged(after.revision, expectedRevision)
                    if (digest != null && digest.digest().toHex() != expectedRevision.sha256) {
                        changed()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: RemoteFileAccessException) {
                throw failure
            } catch (failure: Throwable) {
                throw RemoteFileAccessException(
                    code = "REMOTE_FILE_TRANSFER_FAILED",
                    actionableMessage = "The file could not be read from this connection.",
                    cause = failure,
                )
            }
        }.flowOn(dispatcher)
    }

    private suspend fun <T> withSftpChannel(block: suspend (ChannelSftp) -> T): T {
        val channel = openSftpChannel()
        try {
            return block(channel)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: RemoteFileAccessException) {
            throw failure
        } catch (failure: Throwable) {
            throw RemoteFileAccessException(
                code = "REMOTE_FILE_UNAVAILABLE",
                actionableMessage = "The workspace file is unavailable.",
                cause = failure,
            )
        } finally {
            channel.disconnect()
        }
    }

    private fun openSftpChannel(): ChannelSftp {
        if (!session.isConnected) {
            throw RemoteFileAccessException(
                code = "REMOTE_FILE_CONNECTION_CLOSED",
                actionableMessage = "Reconnect before accessing workspace files.",
            )
        }
        val channel = try {
            session.openChannel("sftp") as ChannelSftp
        } catch (failure: JSchException) {
            throw channelFailure(failure)
        }
        try {
            channel.connect(channelConnectTimeout.inWholeMilliseconds.toInt())
            return channel
        } catch (failure: JSchException) {
            channel.disconnect()
            throw channelFailure(failure)
        }
    }

    private fun resolve(
        channel: ChannelSftp,
        reference: RemoteFileReference,
    ): ResolvedSftpFile {
        val workspace = normalizeCanonicalSftpPath(channel.realpath(reference.workspaceRoot))
        val requested = if (workspace == "/") {
            "/" + reference.relativePath
        } else {
            workspace + "/" + reference.relativePath
        }
        val canonicalFile = normalizeCanonicalSftpPath(channel.realpath(requested))
        if (!isWithinSftpWorkspace(workspace, canonicalFile)) {
            denied()
        }
        val attributes = channel.lstat(canonicalFile)
        if (!attributes.isReg) {
            throw RemoteFileAccessException(
                code = "REMOTE_FILE_NOT_REGULAR",
                actionableMessage = "Only regular workspace files can be downloaded.",
            )
        }
        return ResolvedSftpFile(
            canonicalPath = canonicalFile,
            revision = RemoteFileRevision(
                sizeBytes = attributes.size,
                modifiedAtEpochMillis = Integer.toUnsignedLong(attributes.mTime) * 1_000L,
            ),
        )
    }

    private fun requireUnchanged(
        actual: RemoteFileRevision,
        expected: RemoteFileRevision,
    ) {
        if (
            actual.sizeBytes != expected.sizeBytes ||
            actual.modifiedAtEpochMillis != expected.modifiedAtEpochMillis
        ) {
            changed()
        }
    }

    private fun channelFailure(cause: Throwable): RemoteFileAccessException =
        RemoteFileAccessException(
            code = "REMOTE_FILE_CHANNEL_FAILURE",
            actionableMessage = "The workspace file channel could not be opened.",
            cause = cause,
        )

    private fun denied(): Nothing = throw RemoteFileAccessException(
        code = "REMOTE_FILE_PATH_DENIED",
        actionableMessage = "The requested file is outside the allowed workspace.",
    )

    private fun changed(): Nothing = throw RemoteFileAccessException(
        code = "REMOTE_FILE_CHANGED",
        actionableMessage = "The file changed after it was reviewed. Review it again before downloading.",
    )
}

internal fun normalizeCanonicalSftpPath(path: String): String {
    require(path.startsWith('/')) { "SFTP canonical path must be absolute" }
    val segments = path.split('/').filter(String::isNotEmpty)
    require(segments.none { it == "." || it == ".." }) {
        "SFTP canonical path must not contain traversal segments"
    }
    return if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")
}

internal fun isWithinSftpWorkspace(
    workspace: String,
    file: String,
): Boolean = workspace == "/" || file == workspace || file.startsWith("$workspace/")

private data class ResolvedSftpFile(
    val canonicalPath: String,
    val revision: RemoteFileRevision,
)

private suspend fun InputStream.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = read(buffer)
        if (read < 0) {
            return digest.digest().toHex()
        }
        digest.update(buffer, 0, read)
    }
}

private fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    return buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0x0f])
        }
    }
}
