/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.aider

import dev.agentrelay.provider.api.AgentProviderId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal val AIDER_PROVIDER_ID = AgentProviderId("aider.cli")

internal fun JsonElement?.aiderObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.aiderArray(): JsonArray? = this as? JsonArray

internal fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.long(name: String): Long? =
    (this[name] as? JsonPrimitive)?.content?.toLongOrNull()

internal fun JsonObject.objectValue(name: String): JsonObject? = this[name].aiderObject()

internal fun JsonObject.arrayValue(name: String): JsonArray? = this[name].aiderArray()

internal fun JsonElement?.primitiveString(): String? =
    (this as? JsonPrimitive)?.contentOrNull

internal fun JsonElement?.primitiveLong(): Long? =
    (this as? JsonPrimitive)?.content?.toLongOrNull()
