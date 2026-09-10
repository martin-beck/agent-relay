/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.api

import kotlinx.coroutines.flow.Flow

data class RemoteFileReference(
    val workspaceRoot: String,
    val relativePath: String,
) {
    init {
        require(workspaceRoot.isNotBlank()) { "Workspace root must not be blank" }
        require(workspaceRoot.length <= MAX_REMOTE_PATH_CHARS) { "Workspace root is too large" }
        require('\u0000' !in workspaceRoot) { "Workspace root must not contain NUL" }
        require(relativePath.isNotBlank()) { "Relative file path must not be blank" }
        require(relativePath.length <= MAX_REMOTE_PATH_CHARS) { "Relative file path is too large" }
        require('\u0000' !in relativePath) { "Relative file path must not contain NUL" }
        require(!relativePath.startsWith('/') && !relativePath.startsWith('\\')) {
            "File path must be relative to its workspace"
        }
        require(!WINDOWS_ABSOLUTE_PATH.matches(relativePath)) {
            "File path must be relative to its workspace"
        }
        require('\\' !in relativePath) {
            "Remote file paths must use forward slashes"
        }
        val segments = relativePath.split('/')
        require(segments.all { it.isNotEmpty() && it != "." && it != ".." }) {
            "Relative file path must not contain empty, current, or parent segments"
        }
    }
}

data class RemoteFileRevision(
    val sizeBytes: Long,
    val modifiedAtEpochMillis: Long,
    val sha256: String? = null,
) {
    init {
        require(sizeBytes >= 0L) { "Remote file size must not be negative" }
        require(modifiedAtEpochMillis >= 0L) { "Remote file modification time must not be negative" }
        require(sha256 == null || SHA256.matches(sha256)) {
            "Remote file SHA-256 must be lowercase hexadecimal"
        }
    }
}

data class RemoteFileSnapshot(
    val reference: RemoteFileReference,
    val revision: RemoteFileRevision,
)

class RemoteFileAccessException(
    val code: String,
    val actionableMessage: String,
    cause: Throwable? = null,
) : Exception(actionableMessage, cause) {
    init {
        require(code.matches(Regex("[A-Z][A-Z0-9_]{2,63}"))) {
            "Remote file failure code must be stable and redacted"
        }
        require(actionableMessage.isNotBlank()) {
            "Remote file failure message must not be blank"
        }
    }
}

interface RemoteFileAccess {
    suspend fun inspect(
        reference: RemoteFileReference,
        calculateSha256: Boolean = false,
    ): RemoteFileSnapshot

    fun read(
        reference: RemoteFileReference,
        expectedRevision: RemoteFileRevision,
        chunkSizeBytes: Int = DEFAULT_REMOTE_FILE_CHUNK_BYTES,
    ): Flow<ByteArray>
}

const val DEFAULT_REMOTE_FILE_CHUNK_BYTES = 64 * 1024
const val MAX_REMOTE_FILE_CHUNK_BYTES = 1024 * 1024

private const val MAX_REMOTE_PATH_CHARS = 4_096
private val WINDOWS_ABSOLUTE_PATH = Regex("^[A-Za-z]:[/\\\\].*")
private val SHA256 = Regex("[0-9a-f]{64}")
