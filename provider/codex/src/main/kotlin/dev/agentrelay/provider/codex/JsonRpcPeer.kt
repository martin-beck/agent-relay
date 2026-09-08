/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.codex

import dev.agentrelay.provider.api.RemoteDuplexProcess
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

internal data class JsonRpcCall(val method: String, val params: JsonElement?, val id: JsonElement?)

internal class JsonRpcException(message: String, val code: Int? = null, val data: JsonElement? = null) :
    IllegalStateException(message)

internal class JsonRpcPeer(
    private val process: RemoteDuplexProcess,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val requestTimeout: Duration = 30.seconds,
) : JsonRpcClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val nextRequestId = AtomicLong(1)
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val writeMutex = Mutex()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + dispatcher)
    private val mutableCalls = MutableSharedFlow<JsonRpcCall>(extraBufferCapacity = 64)

    override val calls: SharedFlow<JsonRpcCall> = mutableCalls.asSharedFlow()

    init {
        scope.launch {
            try {
                process.standardOutputLines.collect(::handleLine)
            } catch (error: Throwable) {
                failPending(error)
            }
        }
        scope.launch {
            process.standardErrorLines.collect { /* Drain stderr so the remote process cannot block. */ }
        }
        scope.launch {
            process.exitCode.collect { exitCode ->
                if (exitCode != null) {
                    failPending(IllegalStateException("JSON-RPC process exited with code $exitCode"))
                }
            }
        }
    }

    override suspend fun request(method: String, params: JsonElement): JsonElement {
        val id = nextRequestId.getAndIncrement().toString()
        val response = CompletableDeferred<JsonElement>()
        pendingRequests[id] = response
        try {
            write(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id.toLong())
                    put("method", method)
                    put("params", params)
                },
            )
            return withTimeout(requestTimeout) { response.await() }
        } finally {
            pendingRequests.remove(id, response)
        }
    }

    override suspend fun notify(method: String, params: JsonElement) {
        write(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("method", method)
                put("params", params)
            },
        )
    }

    override suspend fun respond(id: JsonElement, result: JsonElement) {
        write(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("result", result)
            },
        )
    }

    override suspend fun close() {
        failPending(IllegalStateException("JSON-RPC peer closed"))
        process.close()
        scope.cancel()
    }

    private suspend fun write(message: JsonObject) {
        writeMutex.withLock {
            process.writeLine(json.encodeToString(JsonElement.serializer(), message))
        }
    }

    private suspend fun handleLine(line: String) {
        val message = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        val method = message["method"]?.asString()
        if (method != null) {
            mutableCalls.emit(
                JsonRpcCall(
                    method = method,
                    params = message["params"],
                    id = message["id"],
                ),
            )
            return
        }

        val id = message["id"]?.requestKey() ?: return
        val response = pendingRequests.remove(id) ?: return
        val error = message["error"]?.jsonObject
        if (error != null) {
            response.completeExceptionally(
                JsonRpcException(
                    message = error["message"]?.asString() ?: "JSON-RPC request failed",
                    code = error["code"]?.asInt(),
                    data = error["data"],
                ),
            )
        } else {
            response.complete(message["result"] ?: JsonNull)
        }
    }

    private fun failPending(error: Throwable) {
        pendingRequests.values.forEach { it.completeExceptionally(error) }
        pendingRequests.clear()
    }
}

private fun JsonElement.requestKey(): String? = (this as? JsonPrimitive)?.content

internal fun JsonElement.asString(): String? = (this as? JsonPrimitive)
    ?.takeUnless { it is JsonNull }
    ?.content

internal fun JsonElement.asInt(): Int? = (this as? JsonPrimitive)?.content?.toIntOrNull()
