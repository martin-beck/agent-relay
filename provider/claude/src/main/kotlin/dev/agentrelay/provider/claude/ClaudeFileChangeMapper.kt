/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.claude

import dev.agentrelay.provider.api.AgentChangedFile
import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.provider.api.AgentTranscriptEntry
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal object ClaudeFileChangeMapper {
    fun fromTranscript(
        entries: List<AgentTranscriptEntry>,
        workingDirectory: String?,
    ): List<AgentChangedFile> {
        val files = linkedMapOf<String, AgentChangedFile>()
        entries.forEach { entry ->
            val path = entry.metadata["claude.filePath"] ?: return@forEach
            val tool = entry.metadata["claude.tool"]
            fromTool(tool, path, workingDirectory)?.let { files[it.remotePath] = it }
        }
        return files.values.toList()
    }

    fun fromTool(
        tool: String?,
        rawPath: String?,
        workingDirectory: String?,
    ): AgentChangedFile? {
        if (tool !in DIRECT_FILE_TOOLS) return null
        val path = normalizedWorkspacePath(rawPath, workingDirectory) ?: return null
        return AgentChangedFile(
            remotePath = path.toString(),
            kind = when (tool) {
                "Edit", "MultiEdit", "NotebookEdit" -> AgentFileChangeKind.MODIFIED
                else -> AgentFileChangeKind.UNKNOWN
            },
        )
    }

    private fun normalizedWorkspacePath(rawPath: String?, workingDirectory: String?): Path? {
        if (rawPath.isNullOrBlank()) return null
        return try {
            val workspace = workingDirectory
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?.takeIf(Path::isAbsolute)
                ?.normalize()
                ?: return null
            val candidate = Path.of(rawPath)
            val resolved = if (candidate.isAbsolute) {
                candidate.normalize()
            } else {
                workspace.resolve(candidate).normalize()
            }
            resolved.takeIf { it.startsWith(workspace) }
        } catch (_: InvalidPathException) {
            null
        }
    }

    private val DIRECT_FILE_TOOLS = setOf("Write", "Edit", "MultiEdit", "NotebookEdit")
}
