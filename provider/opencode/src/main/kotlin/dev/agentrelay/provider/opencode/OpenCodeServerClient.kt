package dev.agentrelay.provider.opencode

import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.RemoteDuplexProcess
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

internal class OpenCodeServerClient private constructor(
    private val runtime: RemoteAgentRuntime,
    private val serverProcess: RemoteDuplexProcess,
    private val port: Int,
    private val password: String,
    dispatcher: CoroutineDispatcher,
) : OpenCodeClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val eventProcesses = ConcurrentHashMap<String, RemoteDuplexProcess>()

    init {
        scope.launch {
            serverProcess.standardOutputLines.collect { /* Drain server diagnostics. */ }
        }
        scope.launch {
            serverProcess.standardErrorLines.collect { /* Drain server diagnostics. */ }
        }
    }

    override suspend fun get(path: String, directory: String?): JsonElement =
        request("GET", path, null, directory)

    override suspend fun post(path: String, body: JsonElement, directory: String?): JsonElement =
        request("POST", path, body, directory)

    override suspend fun events(directory: String?): Flow<JsonObject> {
        val key = directory.orEmpty()
        val existing = eventProcesses[key]
        if (existing != null) {
            return existing.standardOutputLines.toOpenCodeEvents()
        }

        val created = runtime.openProcess(
            RemoteCommand(
                program = "bash",
                arguments = listOf("-lc", SSE_SCRIPT),
                environment = mapOf(
                    PASSWORD_ENV to password,
                    URL_ENV to url("/event", directory),
                ),
            ),
        )
        val winner = eventProcesses.putIfAbsent(key, created)
        val process = winner ?: created
        if (winner == null) {
            scope.launch {
                created.standardErrorLines.collect { /* Drain curl diagnostics. */ }
            }
            scope.launch {
                created.exitCode.first { it != null }
                eventProcesses.remove(key, created)
            }
        } else {
            created.close()
        }
        return process.standardOutputLines.toOpenCodeEvents()
    }

    override suspend fun close() {
        for (process in eventProcesses.values.toSet()) {
            process.close()
        }
        eventProcesses.clear()
        serverProcess.close()
        scope.cancel()
    }

    private suspend fun health(): JsonElement =
        request("GET", "/global/health", null, null, 1.seconds)

    private suspend fun request(
        method: String,
        path: String,
        body: JsonElement?,
        directory: String?,
        requestTimeout: Duration = 55.seconds,
    ): JsonElement {
        val encodedBody = body?.let {
            json.encodeToString(JsonElement.serializer(), it)
        }
        val result = runtime.execute(
            RemoteCommand(
                program = "bash",
                arguments = listOf("-lc", REQUEST_SCRIPT),
                environment = buildMap {
                    put(PASSWORD_ENV, password)
                    put(METHOD_ENV, method)
                    put(URL_ENV, url(path, directory))
                    put(REQUEST_TIMEOUT_ENV, requestTimeout.inWholeSeconds.coerceAtLeast(1).toString())
                    put(HAS_BODY_ENV, if (encodedBody == null) "0" else "1")
                    encodedBody?.let { put(BODY_ENV, it) }
                },
            ),
            timeout = requestTimeout + 5.seconds,
        )
        check(result.successful) {
            val details = result.standardError.trim().ifEmpty { result.standardOutput.trim() }
            "OpenCode $method $path failed: $details"
        }
        val output = result.standardOutput.trim()
        return if (output.isEmpty()) JsonNull else json.parseToJsonElement(output)
    }

    private fun url(path: String, directory: String?): String {
        require(path.startsWith("/")) { "OpenCode path must be absolute" }
        val query = directory?.let {
            "?directory=" + URLEncoder.encode(it, StandardCharsets.UTF_8)
        }.orEmpty()
        return "http://127.0.0.1:$port$path$query"
    }

    private fun Flow<String>.toOpenCodeEvents(): Flow<JsonObject> = mapNotNull { line ->
        val payload = line.removePrefix("data:").trim()
        if (!line.startsWith("data:") || payload.isEmpty()) {
            null
        } else {
            runCatching { json.parseToJsonElement(payload).objectOrNull() }.getOrNull()
        }
    }

    companion object {
        suspend fun start(
            runtime: RemoteAgentRuntime,
            executable: String,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): OpenCodeServerClient {
            val portResult = runtime.execute(
                RemoteCommand(
                    program = "python3",
                    arguments = listOf("-c", ALLOCATE_PORT_SCRIPT),
                ),
            )
            check(portResult.successful) {
                "Unable to allocate a loopback port for OpenCode: " + portResult.standardError.trim()
            }
            val port = checkNotNull(portResult.standardOutput.trim().toIntOrNull()) {
                "OpenCode port allocator returned invalid output"
            }
            val passwordBytes = ByteArray(32).also(SecureRandom()::nextBytes)
            val password = Base64.getUrlEncoder().withoutPadding().encodeToString(passwordBytes)
            val process = runtime.openProcess(
                RemoteCommand(
                    program = executable,
                    arguments = listOf(
                        "serve",
                        "--hostname",
                        "127.0.0.1",
                        "--port",
                        port.toString(),
                    ),
                    environment = mapOf(
                        "OPENCODE_SERVER_USERNAME" to "opencode",
                        "OPENCODE_SERVER_PASSWORD" to password,
                    ),
                ),
            )
            val client = OpenCodeServerClient(runtime, process, port, password, dispatcher)
            repeat(40) {
                val healthy = runCatching {
                    client.health().objectOrNull()?.string("healthy") == "true"
                }.getOrDefault(false)
                if (healthy) {
                    return client
                }
                delay(250.milliseconds)
            }
            client.close()
            error("OpenCode headless server did not become ready on loopback")
        }

        private const val PASSWORD_ENV = "AGENT_RELAY_OPENCODE_PASSWORD"
        private const val METHOD_ENV = "AGENT_RELAY_OPENCODE_METHOD"
        private const val URL_ENV = "AGENT_RELAY_OPENCODE_URL"
        private const val REQUEST_TIMEOUT_ENV = "AGENT_RELAY_OPENCODE_REQUEST_TIMEOUT"
        private const val HAS_BODY_ENV = "AGENT_RELAY_OPENCODE_HAS_BODY"
        private const val BODY_ENV = "AGENT_RELAY_OPENCODE_BODY"

        private const val ALLOCATE_PORT_SCRIPT =
            "import socket; s=socket.socket(); s.bind(('127.0.0.1', 0)); " +
                "print(s.getsockname()[1]); s.close()"

        private const val REQUEST_SCRIPT =
            "set -o pipefail; " +
                "if [ \"\$AGENT_RELAY_OPENCODE_HAS_BODY\" = 1 ]; then " +
                "printf '%s' \"\$AGENT_RELAY_OPENCODE_BODY\" | " +
                "curl --config <(printf 'user = \"opencode:%s\"\\n' \"\$AGENT_RELAY_OPENCODE_PASSWORD\") " +
                "--fail-with-body --silent --show-error --connect-timeout 5 " +
                "--max-time \"\$AGENT_RELAY_OPENCODE_REQUEST_TIMEOUT\" " +
                "--request \"\$AGENT_RELAY_OPENCODE_METHOD\" --header 'Content-Type: application/json' " +
                "--data-binary @- \"\$AGENT_RELAY_OPENCODE_URL\"; " +
                "else curl --config <(printf 'user = \"opencode:%s\"\\n' " +
                "\"\$AGENT_RELAY_OPENCODE_PASSWORD\") --fail-with-body --silent --show-error " +
                "--connect-timeout 5 --max-time \"\$AGENT_RELAY_OPENCODE_REQUEST_TIMEOUT\" " +
                "--request \"\$AGENT_RELAY_OPENCODE_METHOD\" \"\$AGENT_RELAY_OPENCODE_URL\"; fi"

        private const val SSE_SCRIPT =
            "curl --config <(printf 'user = \"opencode:%s\"\\n' " +
                "\"\$AGENT_RELAY_OPENCODE_PASSWORD\") --fail-with-body --silent --show-error " +
                "--no-buffer --header 'Accept: text/event-stream' \"\$AGENT_RELAY_OPENCODE_URL\""
    }
}
