package dev.agentrelay.provider.continuecli

import dev.agentrelay.provider.api.AgentChangedFile
import dev.agentrelay.provider.api.AgentFileChangeKind
import java.nio.file.Path
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal object ContinueDiffMapper {
    fun fromPatch(patch: String, workspaceDirectory: String): List<AgentChangedFile> {
        val root = Path.of(workspaceDirectory).normalize()
        return splitFileDiffs(patch).mapNotNull { block ->
            val paths = block.firstOrNull()?.let(::parseHeader) ?: return@mapNotNull null
            val renameFrom = block.firstStringAfter("rename from ")?.let(::decodeGitPath)
            val renameTo = block.firstStringAfter("rename to ")?.let(::decodeGitPath)
            val oldRelative = renameFrom ?: paths.first
            val newRelative = renameTo ?: paths.second
            val kind = when {
                renameFrom != null || renameTo != null -> AgentFileChangeKind.RENAMED
                block.any { it.startsWith("new file mode ") } -> AgentFileChangeKind.ADDED
                block.any { it.startsWith("deleted file mode ") } -> AgentFileChangeKind.DELETED
                else -> AgentFileChangeKind.MODIFIED
            }
            val relative = if (kind == AgentFileChangeKind.DELETED) oldRelative else newRelative
            val remotePath = resolveWithin(root, relative) ?: return@mapNotNull null
            AgentChangedFile(
                remotePath = remotePath,
                kind = kind,
                oldRemotePath = if (kind == AgentFileChangeKind.RENAMED) {
                    resolveWithin(root, oldRelative)
                } else {
                    null
                },
            )
        }
    }

    private fun splitFileDiffs(patch: String): List<List<String>> {
        val result = mutableListOf<MutableList<String>>()
        patch.lineSequence().forEach { line ->
            if (line.startsWith("diff --git ")) {
                result.add(mutableListOf(line))
            } else {
                result.lastOrNull()?.add(line)
            }
        }
        return result
    }

    private fun parseHeader(header: String): Pair<String, String>? {
        val match = HEADER.matchEntire(header) ?: return null
        val oldPath = decodeGitPath(match.groupValues[1])
            ?.takeIf { it.startsWith("a/") }
            ?.removePrefix("a/") ?: return null
        val newPath = decodeGitPath(match.groupValues[2])
            ?.takeIf { it.startsWith("b/") }
            ?.removePrefix("b/") ?: return null
        return oldPath to newPath
    }

    private fun List<String>.firstStringAfter(prefix: String): String? =
        firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)

    private fun resolveWithin(root: Path, relative: String): String? {
        val resolved = root.resolve(relative).normalize()
        return resolved.takeIf { it.startsWith(root) }?.toString()
    }

    private fun decodeGitPath(value: String): String? =
        if (value.startsWith('"')) {
            runCatching { Json.decodeFromString<String>(value) }.getOrNull()
        } else {
            value
        }

    private val HEADER = Regex("""diff --git ("(?:\\.|[^"])*"|\S+) ("(?:\\.|[^"])*"|\S+)""")
}
