package dev.agentrelay.provider.claude

import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteDuplexProcess
import dev.agentrelay.provider.api.StartSessionOptions
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class ClaudeStreamClient private constructor(
    private val process: RemoteDuplexProcess,
    private val sessionId: AgentSessionId,
    dispatcher: CoroutineDispatcher,
) : ClaudeClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // Claude can emit system/init before initialize is acknowledged and a collector is attached.
    private val mutableMessages = MutableSharedFlow<JsonObject>(replay = 32, extraBufferCapacity = 224)
    private val controlWaiters = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val permissions = ConcurrentHashMap<String, JsonObject>()
    private val closed = AtomicBoolean()

    override val messages: Flow<JsonObject> = mutableMessages

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            process.standardOutputLines.collect(::handleLine)
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            process.standardErrorLines.collect { /* Drain diagnostics without mixing protocols. */ }
        }
        scope.launch {
            val code = process.exitCode.value
                ?: process.exitCode.firstNonNull()
            if (!closed.get()) {
                val message = "Claude stream process exited with code $code"
                val failure = IllegalStateException(message)
                controlWaiters.values.forEach { it.completeExceptionally(failure) }
                mutableMessages.emit(
                    buildJsonObject {
                        put("type", "agent_relay_error")
                        put("message", message)
                    },
                )
            }
        }
    }

    override suspend fun sendUserMessage(text: String) {
        require(text.isNotBlank()) { "Input must not be blank" }
        process.writeLine(
            buildJsonObject {
                put("type", "user")
                put("session_id", sessionId.value)
                put("parent_tool_use_id", kotlinx.serialization.json.JsonNull)
                put(
                    "message",
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", text)
                                    },
                                )
                            },
                        )
                    },
                )
            }.encoded(),
        )
    }

    override suspend fun interrupt() {
        sendControl(buildJsonObject { put("subtype", "interrupt") })
    }

    override suspend fun respondToPermission(
        requestId: String,
        allowed: Boolean,
        remember: Boolean,
    ) {
        val request = permissions[requestId]
            ?: throw NoSuchElementException("No pending Claude permission $requestId")
        val originalInput = request.objectValue("input") ?: buildJsonObject {}
        val suggestions = request.arrayValue("permission_suggestions") ?: JsonArray(emptyList())
        require(!remember || suggestions.isNotEmpty()) {
            "Claude did not provide a reusable permission suggestion"
        }
        check(permissions.remove(requestId, request)) {
            "Claude permission $requestId was already answered"
        }
        val response = if (allowed) {
            buildJsonObject {
                put("behavior", "allow")
                put("updatedInput", originalInput)
                if (remember && suggestions.isNotEmpty()) {
                    put("updatedPermissions", suggestions)
                }
            }
        } else {
            buildJsonObject {
                put("behavior", "deny")
                put("message", "Declined from Agent Relay")
            }
        }
        process.writeLine(
            buildJsonObject {
                put("type", "control_response")
                put(
                    "response",
                    buildJsonObject {
                        put("subtype", "success")
                        put("request_id", requestId)
                        put("response", response)
                    },
                )
            }.encoded(),
        )
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        controlWaiters.values.forEach { it.cancel() }
        controlWaiters.clear()
        permissions.clear()
        process.close()
        scope.cancel()
    }

    private suspend fun initialize() {
        sendControl(
            buildJsonObject {
                put("subtype", "initialize")
                put("hooks", kotlinx.serialization.json.JsonNull)
            },
        )
    }

    private suspend fun sendControl(request: JsonObject): JsonObject {
        check(!closed.get()) { "Claude stream is closed" }
        val requestId = "agent-relay-" + UUID.randomUUID()
        val waiter = CompletableDeferred<JsonObject>()
        check(controlWaiters.putIfAbsent(requestId, waiter) == null)
        try {
            process.writeLine(
                buildJsonObject {
                    put("type", "control_request")
                    put("request_id", requestId)
                    put("request", request)
                }.encoded(),
            )
            return withTimeout(60.seconds) { waiter.await() }
        } finally {
            controlWaiters.remove(requestId, waiter)
        }
    }

    private suspend fun handleLine(line: String) {
        val wire = runCatching { json.parseToJsonElement(line).objectOrNull() }.getOrNull()
        if (wire == null) {
            mutableMessages.emit(
                buildJsonObject {
                    put("type", "agent_relay_error")
                    put("message", "Claude emitted a malformed stream-json record")
                },
            )
            return
        }
        when (wire.string("type")) {
            "control_response" -> {
                val response = wire.objectValue("response") ?: return
                val requestId = response.string("request_id") ?: return
                val waiter = controlWaiters[requestId] ?: return
                if (response.string("subtype") == "error") {
                    waiter.completeExceptionally(
                        IllegalStateException(response.string("error") ?: "Claude control failed"),
                    )
                } else {
                    waiter.complete(response.objectValue("response") ?: buildJsonObject {})
                }
            }
            "control_request" -> {
                val request = wire.objectValue("request")
                if (request?.string("subtype") == "can_use_tool") {
                    wire.string("request_id")?.let { permissions[it] = request }
                    mutableMessages.emit(wire)
                } else {
                    respondUnsupportedControl(wire)
                }
            }
            else -> mutableMessages.emit(wire)
        }
    }

    private suspend fun respondUnsupportedControl(wire: JsonObject) {
        val requestId = wire.string("request_id") ?: return
        val subtype = wire.objectValue("request")?.string("subtype") ?: "unknown"
        process.writeLine(
            buildJsonObject {
                put("type", "control_response")
                put(
                    "response",
                    buildJsonObject {
                        put("subtype", "error")
                        put("request_id", requestId)
                        put("error", "Unsupported Claude control request: $subtype")
                    },
                )
            }.encoded(),
        )
    }

    private fun JsonObject.encoded(): String =
        json.encodeToString(JsonObject.serializer(), this)

    companion object {
        suspend fun start(
            runtime: RemoteAgentRuntime,
            executable: String,
            sessionId: AgentSessionId,
            resume: Boolean,
            workingDirectory: String?,
            options: StartSessionOptions,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): ClaudeStreamClient {
            val process = runtime.openProcess(
                RemoteCommand(
                    program = executable,
                    arguments = buildArguments(sessionId, resume, options),
                    workingDirectory = workingDirectory,
                ),
            )
            val client = ClaudeStreamClient(process, sessionId, dispatcher)
            return try {
                client.initialize()
                client
            } catch (error: Throwable) {
                client.close()
                throw error
            }
        }

        internal fun buildArguments(
            sessionId: AgentSessionId,
            resume: Boolean,
            options: StartSessionOptions,
        ): List<String> = buildList {
            addAll(
                listOf(
                    "-p",
                    "--input-format",
                    "stream-json",
                    "--output-format",
                    "stream-json",
                    "--verbose",
                    "--include-partial-messages",
                    "--replay-user-messages",
                    "--permission-mode",
                    options.providerOptions["permissionMode"]?.validatedPermissionMode()
                        ?: "manual",
                ),
            )
            if (resume) {
                addAll(listOf("--resume", sessionId.value))
            } else {
                addAll(listOf("--session-id", sessionId.value))
            }
            options.model?.takeIf(String::isNotBlank)?.let { addAll(listOf("--model", it)) }
            options.providerOptions["tools"]?.let { addAll(listOf("--tools", it)) }
            options.providerOptions["name"]?.takeIf(String::isNotBlank)?.let {
                addAll(listOf("--name", it))
            }
            if (options.providerOptions["safeMode"].toBoolean()) add("--safe-mode")
        }

        private fun String.validatedPermissionMode(): String {
            require(this in SAFE_PERMISSION_MODES) {
                "Unsupported Claude permission mode: $this"
            }
            return this
        }

        private val SAFE_PERMISSION_MODES =
            setOf("acceptEdits", "auto", "manual", "dontAsk", "plan")
    }
}

private suspend fun kotlinx.coroutines.flow.StateFlow<Int?>.firstNonNull(): Int =
    first { it != null }!!
