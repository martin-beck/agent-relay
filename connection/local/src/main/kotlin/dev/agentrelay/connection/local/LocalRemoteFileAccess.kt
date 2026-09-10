/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.connection.local

import dev.agentrelay.provider.api.MAX_REMOTE_FILE_CHUNK_BYTES
import dev.agentrelay.provider.api.RemoteFileAccess
import dev.agentrelay.provider.api.RemoteFileAccessException
import dev.agentrelay.provider.api.RemoteFileReference
import dev.agentrelay.provider.api.RemoteFileRevision
import dev.agentrelay.provider.api.RemoteFileSnapshot
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

internal class LocalRemoteFileAccess(
    private val allowedRoot: Path,
    private val dispatcher: CoroutineDispatcher,
    private val ensureOpen: () -> Unit,
) : RemoteFileAccess {
    override suspend fun inspect(
        reference: RemoteFileReference,
        calculateSha256: Boolean,
    ): RemoteFileSnapshot = withContext(dispatcher) {
        val resolved = resolve(reference)
        val checksum = if (calculateSha256) {
            Files.newInputStream(resolved.path).use { input -> input.sha256() }
        } else {
            null
        }
        val after = if (checksum == null) resolved else resolve(reference)
        if (checksum != null) {
            requireUnchanged(after.revision, resolved.revision)
        }
        RemoteFileSnapshot(
            reference = reference,
            revision = after.revision.copy(sha256 = checksum),
        )
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
                val before = resolve(reference)
                requireUnchanged(before.revision, expectedRevision)
                val digest = expectedRevision.sha256?.let { MessageDigest.getInstance("SHA-256") }
                var total = 0L
                Files.newInputStream(before.path).use { input ->
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
                val after = resolve(reference)
                requireUnchanged(after.revision, expectedRevision)
                if (digest != null && digest.digest().toHex() != expectedRevision.sha256) {
                    changed()
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

    private fun resolve(reference: RemoteFileReference): ResolvedLocalFile {
        try {
            ensureOpen()
            val workspaceValue = Path.of(reference.workspaceRoot)
            val workspace = (
                if (workspaceValue.isAbsolute) {
                    workspaceValue
                } else {
                    allowedRoot.resolve(workspaceValue)
                }
                ).toRealPath()
            if (!workspace.startsWith(allowedRoot)) {
                denied()
            }
            val file = workspace.resolve(reference.relativePath).toRealPath()
            if (!file.startsWith(workspace)) {
                denied()
            }
            val attributes = Files.readAttributes(file, BasicFileAttributes::class.java)
            if (!attributes.isRegularFile) {
                throw RemoteFileAccessException(
                    code = "REMOTE_FILE_NOT_REGULAR",
                    actionableMessage = "Only regular workspace files can be downloaded.",
                )
            }
            return ResolvedLocalFile(
                path = file,
                revision = RemoteFileRevision(
                    sizeBytes = attributes.size(),
                    modifiedAtEpochMillis = attributes.lastModifiedTime().toMillis(),
                ),
            )
        } catch (failure: RemoteFileAccessException) {
            throw failure
        } catch (failure: Throwable) {
            throw RemoteFileAccessException(
                code = "REMOTE_FILE_UNAVAILABLE",
                actionableMessage = "The workspace file is unavailable.",
                cause = failure,
            )
        }
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

    private fun denied(): Nothing = throw RemoteFileAccessException(
        code = "REMOTE_FILE_PATH_DENIED",
        actionableMessage = "The requested file is outside the allowed workspace.",
    )

    private fun changed(): Nothing = throw RemoteFileAccessException(
        code = "REMOTE_FILE_CHANGED",
        actionableMessage = "The file changed after it was reviewed. Review it again before downloading.",
    )
}

private data class ResolvedLocalFile(
    val path: Path,
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

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    "%02x".format(byte.toInt() and 0xff)
}
