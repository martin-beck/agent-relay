/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.runtime

import dev.agentrelay.provider.api.AgentChangedFile
import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.provider.api.RemoteFileReference
import dev.agentrelay.session.api.SessionArtifact
import dev.agentrelay.session.api.SessionArtifactAvailability
import dev.agentrelay.session.api.SessionLocator
import java.security.MessageDigest

internal object SessionArtifactMapper {
    fun map(
        file: AgentChangedFile,
        locator: SessionLocator,
        workspaceRoot: String?,
        now: Long,
    ): SessionArtifact {
        val providerPath = boundedProviderPath(file.remotePath)
        val pathWasAccepted =
            providerPath == file.remotePath && providerPath != INVALID_PROVIDER_PATH
        val relativePath = if (pathWasAccepted && workspaceRoot != null) {
            relativePath(workspaceRoot, providerPath)
        } else {
            null
        }
        val oldProviderPath = file.oldRemotePath?.let(::boundedProviderPath)
        val oldRelativePath = oldProviderPath
            ?.takeIf { it != INVALID_PROVIDER_PATH && workspaceRoot != null }
            ?.let { relativePath(checkNotNull(workspaceRoot), it) }
        val availability = availability(file.kind, workspaceRoot, relativePath)
        val turnId = file.turnId?.value
            ?.takeIf(String::isNotBlank)
            ?.let { boundedIdentifier("turn", it) }
        return SessionArtifact(
            id = "artifact:" + digest(
                locator.stableKey + "\u0000" +
                    (turnId ?: "") + "\u0000" +
                    file.kind.name + "\u0000" + providerPath,
            ),
            locator = locator,
            providerPath = providerPath,
            relativePath = relativePath,
            oldProviderPath = oldProviderPath,
            oldRelativePath = oldRelativePath,
            kind = file.kind,
            turnId = turnId,
            availability = availability,
            observedAtEpochMillis = now.coerceAtLeast(0L),
        )
    }

    private fun availability(
        kind: AgentFileChangeKind,
        workspaceRoot: String?,
        relativePath: String?,
    ): SessionArtifactAvailability = when {
        kind == AgentFileChangeKind.DELETED -> SessionArtifactAvailability.DELETED
        workspaceRoot == null -> SessionArtifactAvailability.WORKSPACE_UNKNOWN
        relativePath == null -> SessionArtifactAvailability.OUTSIDE_WORKSPACE
        else -> SessionArtifactAvailability.DOWNLOADABLE
    }

    private fun relativePath(
        workspaceRoot: String,
        providerPath: String,
    ): String? {
        val candidate = if (providerPath.startsWith('/')) {
            val normalizedRoot = normalizeAbsolutePath(workspaceRoot) ?: return null
            val normalizedPath = normalizeAbsolutePath(providerPath) ?: return null
            if (normalizedPath == normalizedRoot || !normalizedPath.startsWith("$normalizedRoot/")) {
                return null
            }
            normalizedPath.removePrefix("$normalizedRoot/")
        } else {
            providerPath
        }
        return runCatching {
            RemoteFileReference(workspaceRoot, candidate).relativePath
        }.getOrNull()
    }

    private fun normalizeAbsolutePath(value: String): String? {
        if (!value.startsWith('/') || '\\' in value || '\u0000' in value) {
            return null
        }
        val segments = value.split('/').filter(String::isNotEmpty)
        if (segments.any { it == "." || it == ".." }) {
            return null
        }
        return if (segments.isEmpty()) "/" else "/" + segments.joinToString("/")
    }

    private fun boundedProviderPath(value: String): String =
        value.takeIf { it.isNotBlank() && it.length <= MAX_PATH_CHARS && '\u0000' !in it }
            ?: INVALID_PROVIDER_PATH

    private fun boundedIdentifier(prefix: String, value: String): String =
        value.trim().takeIf { it.isNotEmpty() && it.length <= MAX_ID_CHARS }
            ?: "$prefix:" + digest(value)

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }

    private const val MAX_ID_CHARS = 512
    private const val MAX_PATH_CHARS = 4_096
    private const val INVALID_PROVIDER_PATH = "<invalid provider path>"
}
