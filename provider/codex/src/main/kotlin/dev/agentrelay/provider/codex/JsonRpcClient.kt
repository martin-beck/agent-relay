package dev.agentrelay.provider.codex

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject

internal interface JsonRpcClient {
    val calls: SharedFlow<JsonRpcCall>

    suspend fun request(method: String, params: JsonElement = buildJsonObject {}): JsonElement

    suspend fun notify(method: String, params: JsonElement = buildJsonObject {})

    suspend fun respond(id: JsonElement, result: JsonElement = JsonNull)

    suspend fun close()
}
