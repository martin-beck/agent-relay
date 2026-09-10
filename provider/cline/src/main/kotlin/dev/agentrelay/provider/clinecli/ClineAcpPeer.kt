/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.clinecli

import dev.agentrelay.provider.api.RemoteDuplexProcess
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
import kotlinx.serialization.json.put

internal data class ClineAcpCall(
    val method: String,
    val params: JsonObject?,
    val id: JsonElement?,
)

internal class ClineAcpException(
    message: String,
    val code: Int? = null,
    val data: JsonElement? = null,
) : IllegalStateException(message)

internal interface ClineAcpRpc {
    val calls: Flow<ClineAcpCall>

    suspend fun request(method: String, params: JsonElement): JsonElement

    suspend fun notify(method: String, params: JsonElement)

    suspend fun respond(id: JsonElement, result: JsonElement)

    suspend fun respondError(id: JsonElement, code: Int, message: String)

    suspend fun close()
}

internal class ClineAcpPeer(
    private val process: RemoteDuplexProcess,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val requestTimeout: Duration = 60.seconds,
) : ClineAcpRpc {
    private val json = Json { ignoreUnknownKeys = true }
    private val nextRequestId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonElement>>()
    private val writeMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableCalls = MutableSharedFlow<ClineAcpCall>(extraBufferCapacity = 256)
    private val closed = AtomicBoolean()

    override val calls: Flow<ClineAcpCall> = mutableCalls

    init {
        scope.launch {
            process.standardOutputLines.collect(::handleLine)
        }
        scope.launch {
            process.standardErrorLines.collect { /* Keep diagnostics separate from ACP stdout. */ }
        }
        scope.launch {
            process.exitCode.collect { code ->
                if (code != null && !closed.get()) {
                    val message = "Cline ACP process exited with code $code"
                    failPending(IllegalStateException(message))
                    mutableCalls.emit(
                        ClineAcpCall(
                            method = "agent_relay/error",
                            params = buildJsonObject { put("message", message) },
                            id = null,
                        ),
                    )
                }
            }
        }
    }

    override suspend fun request(method: String, params: JsonElement): JsonElement {
        check(!closed.get()) { "Cline ACP peer is closed" }
        val id = nextRequestId.getAndIncrement().toString()
        val waiter = CompletableDeferred<JsonElement>()
        check(pending.putIfAbsent(id, waiter) == null)
        try {
            write(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id.toLong())
                    put("method", method)
                    put("params", params)
                },
            )
            return withTimeout(requestTimeout) { waiter.await() }
        } finally {
            pending.remove(id, waiter)
        }
    }

    override suspend fun notify(method: String, params: JsonElement) {
        check(!closed.get()) { "Cline ACP peer is closed" }
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

    override suspend fun respondError(id: JsonElement, code: Int, message: String) {
        write(
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put(
                    "error",
                    buildJsonObject {
                        put("code", code)
                        put("message", message)
                    },
                )
            },
        )
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        failPending(IllegalStateException("Cline ACP peer closed"))
        process.close()
        scope.cancel()
    }

    private suspend fun write(message: JsonObject) {
        writeMutex.withLock {
            process.writeLine(json.encodeToString(JsonElement.serializer(), message))
        }
    }

    private suspend fun handleLine(line: String) {
        val message = runCatching { json.parseToJsonElement(line).clineObject() }.getOrNull()
        if (message == null) {
            mutableCalls.emit(
                ClineAcpCall(
                    method = "agent_relay/error",
                    params = buildJsonObject {
                        put("message", "Cline emitted a malformed ACP record")
                    },
                    id = null,
                ),
            )
            return
        }
        val method = message.string("method")
        if (method != null) {
            mutableCalls.emit(
                ClineAcpCall(
                    method = method,
                    params = message.objectValue("params"),
                    id = message["id"],
                ),
            )
            return
        }

        val key = message["id"].requestKey() ?: return
        val waiter = pending.remove(key) ?: return
        val error = message.objectValue("error")
        if (error != null) {
            waiter.completeExceptionally(
                ClineAcpException(
                    message = error.string("message") ?: "Cline ACP request failed",
                    code = error["code"].primitiveInt(),
                    data = error["data"],
                ),
            )
        } else {
            waiter.complete(message["result"] ?: JsonNull)
        }
    }

    private fun failPending(error: Throwable) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }
}

private fun JsonElement?.requestKey(): String? = (this as? JsonPrimitive)?.content

private fun JsonElement?.primitiveInt(): Int? =
    (this as? JsonPrimitive)?.content?.toIntOrNull()
