/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.aider

import dev.agentrelay.provider.api.AgentChangedFile
import dev.agentrelay.provider.api.AgentFileChangeKind
import kotlinx.serialization.json.Json

internal object AiderFileChangeMapper {
    fun fromMetadata(value: String?): List<AgentChangedFile> {
        if (value.isNullOrBlank()) return emptyList()
        val rows = runCatching { Json.parseToJsonElement(value).aiderObject() }.getOrNull()
            ?: return emptyList()
        return rows.mapNotNull { (path, kindValue) ->
            if (!path.startsWith("/")) return@mapNotNull null
            AgentChangedFile(
                remotePath = path,
                kind = when (kindValue.primitiveString()) {
                    "added" -> AgentFileChangeKind.ADDED
                    "deleted" -> AgentFileChangeKind.DELETED
                    "renamed" -> AgentFileChangeKind.RENAMED
                    "modified" -> AgentFileChangeKind.MODIFIED
                    else -> AgentFileChangeKind.UNKNOWN
                },
            )
        }
    }
}
