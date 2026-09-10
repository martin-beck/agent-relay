/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.opencode

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

internal interface OpenCodeClient {
    suspend fun get(path: String, directory: String? = null): JsonElement

    suspend fun post(
        path: String,
        body: JsonElement = buildJsonObject {},
        directory: String? = null,
    ): JsonElement

    suspend fun events(directory: String? = null): Flow<JsonObject>

    suspend fun close()
}
