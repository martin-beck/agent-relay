/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun JsonElement?.objectOrNull(): JsonObject? = this as? JsonObject

internal fun JsonElement?.arrayOrNull(): JsonArray? = this as? JsonArray

internal fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)
    ?.takeUnless { it is JsonNull }
    ?.content

internal fun JsonObject.long(name: String): Long? = (get(name) as? JsonPrimitive)?.content?.toLongOrNull()

internal fun JsonObject.boolean(name: String): Boolean? =
    (get(name) as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

internal fun JsonObject.objectValue(name: String): JsonObject? = get(name).objectOrNull()

internal fun JsonObject.arrayValue(name: String): JsonArray? = get(name).arrayOrNull()
