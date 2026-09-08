/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.clinecli

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun JsonElement?.clineObject(): JsonObject? = this as? JsonObject

internal fun JsonElement?.clineArray(): JsonArray? = this as? JsonArray

internal fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)
    ?.takeUnless { it is JsonNull }
    ?.content

internal fun JsonObject.long(name: String): Long? =
    (get(name) as? JsonPrimitive)?.content?.toLongOrNull()

internal fun JsonObject.boolean(name: String): Boolean? =
    (get(name) as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

internal fun JsonObject.objectValue(name: String): JsonObject? = get(name).clineObject()

internal fun JsonObject.arrayValue(name: String): JsonArray? = get(name).clineArray()

internal fun JsonElement.readableValue(): String =
    (this as? JsonPrimitive)?.content ?: toString()
