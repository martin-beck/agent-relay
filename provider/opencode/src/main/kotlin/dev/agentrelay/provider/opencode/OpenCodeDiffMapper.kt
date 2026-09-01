package dev.agentrelay.provider.opencode

import dev.agentrelay.provider.api.AgentChangedFile
import dev.agentrelay.provider.api.AgentFileChangeKind
import kotlinx.serialization.json.JsonArray

internal object OpenCodeDiffMapper {
    fun fromJson(diff: JsonArray): List<AgentChangedFile> = diff.mapNotNull { element ->
        val file = element.objectOrNull() ?: return@mapNotNull null
        val path = file.string("file") ?: return@mapNotNull null
        val before = file.string("before").orEmpty()
        val after = file.string("after").orEmpty()
        AgentChangedFile(
            remotePath = path,
            kind = when {
                before.isEmpty() && after.isNotEmpty() -> AgentFileChangeKind.ADDED
                before.isNotEmpty() && after.isEmpty() -> AgentFileChangeKind.DELETED
                before != after -> AgentFileChangeKind.MODIFIED
                else -> AgentFileChangeKind.UNKNOWN
            },
        )
    }
}
