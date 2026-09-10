/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.session.android

import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.session.api.SessionArtifact
import dev.agentrelay.session.api.SessionArtifactAvailability

internal fun SessionArtifact.toDocument() = SessionArtifactDocument(
    id = id,
    locator = locator.toDocument(),
    providerPath = providerPath,
    relativePath = relativePath,
    oldProviderPath = oldProviderPath,
    oldRelativePath = oldRelativePath,
    kind = kind.name,
    turnId = turnId,
    availability = availability.name,
    observedAtEpochMillis = observedAtEpochMillis,
)

internal fun SessionArtifactDocument.toDomain() = SessionArtifact(
    id = id,
    locator = locator.toDomain(),
    providerPath = providerPath,
    relativePath = relativePath,
    oldProviderPath = oldProviderPath,
    oldRelativePath = oldRelativePath,
    kind = artifactEnumValue<AgentFileChangeKind>(kind),
    turnId = turnId,
    availability = artifactEnumValue<SessionArtifactAvailability>(availability),
    observedAtEpochMillis = observedAtEpochMillis,
)

private inline fun <reified T : Enum<T>> artifactEnumValue(name: String): T =
    enumValues<T>().firstOrNull { it.name == name }
        ?: throw IllegalArgumentException("Unknown persisted artifact enum value")
