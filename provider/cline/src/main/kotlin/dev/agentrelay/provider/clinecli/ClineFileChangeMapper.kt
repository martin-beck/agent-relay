/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.clinecli

import dev.agentrelay.provider.api.AgentChangedFile
import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.provider.api.AgentTranscriptEntry
import java.nio.file.InvalidPathException
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject

internal object ClineFileChangeMapper {
    fun fromTranscript(
        entries: List<AgentTranscriptEntry>,
        workingDirectory: String?,
    ): List<AgentChangedFile> {
        val files = linkedMapOf<String, AgentChangedFile>()
        entries.forEach { entry ->
            val rawPath = entry.metadata["cline.filePath"] ?: return@forEach
            val kind = entry.metadata["cline.toolKind"]
            normalized(kind, rawPath, workingDirectory)?.let { files[it.remotePath] = it }
        }
        return files.values.toList()
    }

    fun fromTool(
        kind: String?,
        input: JsonObject?,
        workingDirectory: String?,
    ): AgentChangedFile? {
        if (kind !in FILE_KINDS) return null
        val rawPath = input?.string("path")
            ?: input?.string("file_path")
            ?: input?.string("filePath")
            ?: input?.string("target_file")
            ?: input?.string("targetFile")
            ?: return null
        return normalized(kind, rawPath, workingDirectory)
    }

    private fun normalized(
        kind: String?,
        rawPath: String,
        workingDirectory: String?,
    ): AgentChangedFile? = try {
        val workspace = workingDirectory
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.takeIf(Path::isAbsolute)
            ?.normalize()
            ?: return null
        val candidate = Path.of(rawPath)
        val path = if (candidate.isAbsolute) {
            candidate.normalize()
        } else {
            workspace.resolve(candidate).normalize()
        }
        if (!path.startsWith(workspace)) return null
        AgentChangedFile(
            remotePath = path.toString(),
            kind = when (kind) {
                "delete" -> AgentFileChangeKind.DELETED
                "move" -> AgentFileChangeKind.RENAMED
                else -> AgentFileChangeKind.MODIFIED
            },
        )
    } catch (_: InvalidPathException) {
        null
    }

    private val FILE_KINDS = setOf("edit", "delete", "move")
}
