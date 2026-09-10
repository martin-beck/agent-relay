/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.aider

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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class AiderReady(
    val model: String?,
)

internal class AiderProcessException(message: String) : IllegalStateException(message)

internal interface AiderProcessRpc {
    suspend fun awaitReady(): AiderReady

    suspend fun request(method: String, text: String): JsonObject

    suspend fun close()
}

internal class AiderProcessPeer(
    private val process: RemoteDuplexProcess,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val startupTimeout: Duration = 60.seconds,
    private val requestTimeout: Duration = 900.seconds,
) : AiderProcessRpc {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val writeMutex = Mutex()
    private val nextRequestId = AtomicLong(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val ready = CompletableDeferred<AiderReady>()
    private val closed = AtomicBoolean()

    init {
        scope.launch {
            process.standardOutputLines.collect(::handleLine)
        }
        scope.launch {
            process.standardErrorLines.collect {
                // The signed helper captures Aider diagnostics. Raw stderr is not protocol data.
            }
        }
        scope.launch {
            process.exitCode.collect { code ->
                if (code != null && !closed.get()) {
                    fail(AiderProcessException("Aider helper exited with code $code"))
                }
            }
        }
    }

    override suspend fun awaitReady(): AiderReady =
        withTimeout(startupTimeout) { ready.await() }

    override suspend fun request(method: String, text: String): JsonObject {
        check(!closed.get()) { "Aider helper is closed" }
        val id = nextRequestId.getAndIncrement().toString()
        val waiter = CompletableDeferred<JsonObject>()
        check(pending.putIfAbsent(id, waiter) == null)
        try {
            write(
                buildJsonObject {
                    put("id", id.toLong())
                    put("method", method)
                    put("text", text)
                },
            )
            return withTimeout(requestTimeout) { waiter.await() }
        } finally {
            pending.remove(id, waiter)
        }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        failPending(AiderProcessException("Aider helper closed"))
        if (!ready.isCompleted) {
            ready.completeExceptionally(AiderProcessException("Aider helper closed before ready"))
        }
        process.close()
        scope.cancel()
    }

    private suspend fun write(message: JsonObject) {
        writeMutex.withLock {
            process.writeLine(json.encodeToString(JsonElement.serializer(), message))
        }
    }

    private fun handleLine(line: String) {
        val message = runCatching { json.parseToJsonElement(line).aiderObject() }.getOrNull()
        if (message == null) {
            fail(AiderProcessException("Aider helper emitted a malformed protocol record"))
            return
        }
        when (message.string("type")) {
            "ready" -> {
                if (!ready.isCompleted) {
                    ready.complete(AiderReady(message.string("model")))
                }
            }
            "result" -> complete(message, null)
            "error" -> {
                val error =
                    AiderProcessException(message.string("message") ?: "Aider request failed")
                if (message["id"].primitiveLong() == null) {
                    fail(error)
                } else {
                    complete(message, error)
                }
            }
            "diagnostic" -> Unit
            else -> fail(AiderProcessException("Aider helper emitted an unknown record"))
        }
    }

    private fun complete(message: JsonObject, error: Throwable?) {
        val key = message["id"].primitiveLong()?.toString() ?: return
        val waiter = pending.remove(key) ?: return
        if (error == null) {
            waiter.complete(message)
        } else {
            waiter.completeExceptionally(error)
        }
    }

    private fun fail(error: Throwable) {
        if (!ready.isCompleted) ready.completeExceptionally(error)
        failPending(error)
    }

    private fun failPending(error: Throwable) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }
}
