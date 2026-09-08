/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.continuecli

import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteDuplexProcess
import dev.agentrelay.provider.api.StartSessionOptions
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class ContinueServerClient private constructor(
    private val runtime: RemoteAgentRuntime,
    private val process: RemoteDuplexProcess,
    private val port: Int,
    dispatcher: CoroutineDispatcher,
) : ContinueClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val closed = AtomicBoolean()

    init {
        scope.launch {
            process.standardOutputLines.collect { /* Drain server diagnostics. */ }
        }
        scope.launch {
            process.standardErrorLines.collect { /* Drain server diagnostics. */ }
        }
    }

    override suspend fun state(): JsonObject =
        request("GET", "/state").objectOrNull()
            ?: error("Continue /state returned a non-object result")

    override suspend fun sendMessage(message: String) {
        require(message.isNotBlank()) { "Input must not be blank" }
        request(
            "POST",
            "/message",
            buildJsonObject { put("message", message) },
        )
    }

    override suspend fun respondToPermission(requestId: String, approved: Boolean) {
        request(
            "POST",
            "/permission",
            buildJsonObject {
                put("requestId", requestId)
                put("approved", approved)
            },
        )
    }

    override suspend fun pause() {
        request("POST", "/pause", buildJsonObject {})
    }

    override suspend fun diff(): String =
        request("GET", "/diff").objectOrNull()?.string("diff").orEmpty()

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        val exitRequested = runCatching {
            request("POST", "/exit", buildJsonObject {}, 5.seconds)
        }.isSuccess
        if (exitRequested) {
            withTimeoutOrNull(2.seconds) {
                process.exitCode.first { it != null }
            }
        }
        process.close()
        scope.cancel()
    }

    private suspend fun health(): JsonObject =
        request("GET", "/state", requestTimeout = 1.seconds).objectOrNull()
            ?: error("Continue /state returned a non-object result")

    private suspend fun request(
        method: String,
        path: String,
        body: JsonElement? = null,
        requestTimeout: Duration = 55.seconds,
    ): JsonElement {
        require(path.startsWith("/")) { "Continue path must be absolute" }
        val encodedBody = body?.let {
            json.encodeToString(JsonElement.serializer(), it)
        }
        val result = runtime.execute(
            RemoteCommand(
                program = "bash",
                arguments = listOf("-lc", REQUEST_SCRIPT),
                environment = buildMap {
                    put(METHOD_ENV, method)
                    put(URL_ENV, "http://127.0.0.1:$port$path")
                    put(TIMEOUT_ENV, requestTimeout.inWholeSeconds.coerceAtLeast(1).toString())
                    put(HAS_BODY_ENV, if (encodedBody == null) "0" else "1")
                    encodedBody?.let { put(BODY_ENV, it) }
                },
            ),
            timeout = requestTimeout + 5.seconds,
        )
        check(result.successful) {
            val details = result.standardError.trim().ifEmpty { result.standardOutput.trim() }
            "Continue $method $path failed: $details"
        }
        val output = result.standardOutput.trim()
        return if (output.isEmpty()) JsonNull else json.parseToJsonElement(output)
    }

    companion object {
        suspend fun start(
            runtime: RemoteAgentRuntime,
            executable: String,
            sessionId: AgentSessionId,
            workingDirectory: String?,
            options: StartSessionOptions = StartSessionOptions(workingDirectory),
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): ContinueServerClient {
            val portResult = runtime.execute(
                RemoteCommand("python3", listOf("-c", ALLOCATE_PORT_SCRIPT)),
            )
            check(portResult.successful) {
                "Unable to allocate a loopback port for Continue: " +
                    portResult.standardError.trim()
            }
            val port = checkNotNull(portResult.standardOutput.trim().toIntOrNull()) {
                "Continue port allocator returned invalid output"
            }
            val process = runtime.openProcess(
                RemoteCommand(
                    program = executable,
                    arguments = buildArguments(sessionId, options, port),
                    environment = mapOf("NODE_OPTIONS" to nodeOptions()),
                    workingDirectory = workingDirectory,
                ),
            )
            val client = ContinueServerClient(runtime, process, port, dispatcher)
            repeat(40) {
                val state = runCatching { client.health() }.getOrNull()
                val actualId = state?.objectValue("session")?.string("sessionId")
                if (actualId == sessionId.value) {
                    val listener = validateLoopback(runtime, port)
                    if (!listener.successful || listener.standardOutput.trim() != "loopback") {
                        client.close()
                        error(
                            "Continue server listener is missing or is not restricted to loopback",
                        )
                    }
                    return client
                }
                if (actualId != null) {
                    client.close()
                    error("Continue server loaded unexpected session $actualId")
                }
                if (process.exitCode.value != null) {
                    client.close()
                    error("Continue server exited before becoming ready")
                }
                delay(250.milliseconds)
            }
            client.close()
            error("Continue server did not become ready on loopback")
        }

        internal fun buildArguments(
            sessionId: AgentSessionId,
            options: StartSessionOptions,
            port: Int,
        ): List<String> = buildList {
            options.providerOptions["config"]?.takeIf(String::isNotBlank)?.let {
                addAll(listOf("--config", it))
            }
            options.providerOptions["agent"]?.takeIf(String::isNotBlank)?.let {
                addAll(listOf("--agent", it))
            }
            options.model?.takeIf(String::isNotBlank)?.let {
                addAll(listOf("--model", it))
            }
            when {
                options.providerOptions["readonly"].toBoolean() -> add("--readonly")
                options.providerOptions["auto"].toBoolean() -> add("--auto")
            }
            addAll(
                listOf(
                    "serve",
                    "--timeout",
                    "86400",
                    "--port",
                    port.toString(),
                    "--id",
                    sessionId.value,
                ),
            )
        }

        internal fun nodeOptions(): String {
            val encoded = Base64.getEncoder().encodeToString(
                LOOPBACK_PRELOAD.toByteArray(StandardCharsets.UTF_8),
            )
            return "--import=data:text/javascript;base64,$encoded"
        }

        private suspend fun validateLoopback(
            runtime: RemoteAgentRuntime,
            port: Int,
        ) = runtime.execute(
            RemoteCommand("python3", listOf("-c", VALIDATE_LOOPBACK_SCRIPT, port.toString())),
            timeout = 5.seconds,
        )

        private const val ALLOCATE_PORT_SCRIPT =
            "import socket; s=socket.socket(); s.bind(('127.0.0.1', 0)); " +
                "print(s.getsockname()[1]); s.close()"

        private const val METHOD_ENV = "AGENT_RELAY_CONTINUE_METHOD"
        private const val URL_ENV = "AGENT_RELAY_CONTINUE_URL"
        private const val TIMEOUT_ENV = "AGENT_RELAY_CONTINUE_REQUEST_TIMEOUT"
        private const val HAS_BODY_ENV = "AGENT_RELAY_CONTINUE_HAS_BODY"
        private const val BODY_ENV = "AGENT_RELAY_CONTINUE_BODY"

        private const val REQUEST_SCRIPT =
            "set -o pipefail; " +
                "if [ \"\$AGENT_RELAY_CONTINUE_HAS_BODY\" = 1 ]; then " +
                "printf '%s' \"\$AGENT_RELAY_CONTINUE_BODY\" | " +
                "curl --fail-with-body --silent --show-error --connect-timeout 5 " +
                "--max-time \"\$AGENT_RELAY_CONTINUE_REQUEST_TIMEOUT\" " +
                "--request \"\$AGENT_RELAY_CONTINUE_METHOD\" " +
                "--header 'Content-Type: application/json' --data-binary @- " +
                "\"\$AGENT_RELAY_CONTINUE_URL\"; " +
                "else curl --fail-with-body --silent --show-error --connect-timeout 5 " +
                "--max-time \"\$AGENT_RELAY_CONTINUE_REQUEST_TIMEOUT\" " +
                "--request \"\$AGENT_RELAY_CONTINUE_METHOD\" " +
                "\"\$AGENT_RELAY_CONTINUE_URL\"; fi"

        private const val LOOPBACK_PRELOAD =
            "import net from 'node:net';" +
                "delete process.env.NODE_OPTIONS;" +
                "const original=net.Server.prototype.listen;" +
                "net.Server.prototype.listen=function(...args){" +
                "if(typeof args[0]==='number'){args.splice(1,0,'127.0.0.1');}" +
                "else if(args[0]&&typeof args[0]==='object'&&!args[0].host){" +
                "args[0]={...args[0],host:'127.0.0.1'};}" +
                "return original.apply(this,args);};"

        private val VALIDATE_LOOPBACK_SCRIPT =
            """
            import pathlib
            import sys

            port = f"{int(sys.argv[1]):04X}"
            listeners = []
            families = (
                ("tcp", "0100007F"),
                ("tcp6", "00000000000000000000000001000000"),
            )
            for family, expected_address in families:
                table = pathlib.Path("/proc/net") / family
                if not table.exists():
                    continue
                for line in table.read_text().splitlines()[1:]:
                    fields = line.split()
                    address, seen_port = fields[1].rsplit(":", 1)
                    if fields[3] == "0A" and seen_port == port:
                        listeners.append(address == expected_address)
            safe = bool(listeners) and all(listeners)
            print("loopback" if safe else "unsafe")
            raise SystemExit(0 if safe else 1)
            """.trimIndent()
    }
}
