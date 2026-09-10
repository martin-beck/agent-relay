/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.data

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal interface ArtifactExportDestination {
    suspend fun write(
        chunks: Flow<ByteArray>,
        onProgress: (Long) -> Unit,
    ): Long

    suspend fun discardPartial()
}

internal class AndroidSafArtifactExportDestination(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
) : ArtifactExportDestination {
    override suspend fun write(
        chunks: Flow<ByteArray>,
        onProgress: (Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val output = checkNotNull(contentResolver.openOutputStream(uri, "wt")) {
            "The selected document could not be opened"
        }
        output.use { stream ->
            var bytesWritten = 0L
            chunks.collect { chunk ->
                coroutineContext.ensureActive()
                stream.write(chunk)
                bytesWritten = Math.addExact(bytesWritten, chunk.size.toLong())
                onProgress(bytesWritten)
            }
            stream.flush()
            bytesWritten
        }
    }

    override suspend fun discardPartial() {
        withContext(Dispatchers.IO) {
            runCatching {
                contentResolver.delete(uri, null, null)
            }
        }
    }
}
