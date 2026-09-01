package dev.agentrelay.provider.clinecli

import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentSession
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.StartSessionOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class ClineAcpClient private constructor(
    private val rpc: ClineAcpRpc,
    override val sessionId: String,
    override val currentModel: String?,
) : ClineClient {
    override val calls: Flow<ClineAcpCall> = rpc.calls

    override suspend fun prompt(text: String): JsonObject {
        require(text.isNotBlank()) { "Input must not be blank" }
        return rpc.request(
            "session/prompt",
            buildJsonObject {
                put("sessionId", sessionId)
                put(
                    "prompt",
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
        ).clineObject() ?: buildJsonObject {}
    }

    override suspend fun cancel() {
        rpc.notify(
            "session/cancel",
            buildJsonObject { put("sessionId", sessionId) },
        )
    }

    override suspend fun respondToPermission(
        call: ClineAcpCall,
        decision: AgentApprovalDecision,
        optionIds: Map<AgentApprovalDecision, String>,
    ) {
        val id = requireNotNull(call.id) { "Cline permission request is missing its RPC id" }
        val outcome = if (decision == AgentApprovalDecision.CANCEL) {
            buildJsonObject { put("outcome", "cancelled") }
        } else {
            val optionId = optionIds[decision]
                ?: throw IllegalArgumentException("Cline did not offer $decision")
            buildJsonObject {
                put("outcome", "selected")
                put("optionId", optionId)
            }
        }
        rpc.respond(id, buildJsonObject { put("outcome", outcome) })
    }

    override suspend fun rejectUnsupported(call: ClineAcpCall) {
        call.id?.let {
            rpc.respondError(it, -32601, "Unsupported Cline ACP client request: " + call.method)
        }
    }

    override suspend fun close() {
        rpc.close()
    }

    companion object {
        suspend fun startNew(
            runtime: RemoteAgentRuntime,
            executable: String,
            options: StartSessionOptions,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): ClineAcpClient {
            val rpc = open(runtime, executable, options, dispatcher)
            return try {
                initialize(rpc)
                val result = rpc.request(
                    "session/new",
                    buildSessionParams(options.workingDirectory),
                ).clineObject() ?: error("Cline returned an invalid new-session response")
                val sessionId = requireNotNull(result.string("sessionId")) {
                    "Cline did not return a session id"
                }
                val defaultModel = result.objectValue("models")?.string("currentModelId")
                val requestedModel = options.model?.takeIf(String::isNotBlank)
                if (requestedModel != null && requestedModel != defaultModel) {
                    rpc.request(
                        "session/set_model",
                        buildJsonObject {
                            put("sessionId", sessionId)
                            put("modelId", requestedModel)
                        },
                    )
                }
                val mode = options.providerOptions["mode"] ?: "act"
                require(mode == "act" || mode == "plan") { "Cline mode must be act or plan" }
                if (mode != "act") {
                    rpc.request(
                        "session/set_mode",
                        buildJsonObject {
                            put("sessionId", sessionId)
                            put("modeId", mode)
                        },
                    )
                }
                ClineAcpClient(rpc, sessionId, requestedModel ?: defaultModel)
            } catch (error: Throwable) {
                rpc.close()
                throw error
            }
        }

        suspend fun resume(
            runtime: RemoteAgentRuntime,
            executable: String,
            session: AgentSession,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): ClineAcpClient {
            val options = StartSessionOptions(
                workingDirectory = session.workingDirectory,
                model = session.model,
                providerOptions = buildMap {
                    session.metadata["cline.provider"]?.let { put("provider", it) }
                    session.metadata["cline.config"]?.let { put("config", it) }
                    session.metadata["cline.dataDir"]?.let { put("dataDir", it) }
                },
            )
            val rpc = open(runtime, executable, options, dispatcher)
            return try {
                initialize(rpc)
                val result = rpc.request(
                    "session/load",
                    buildSessionParams(session.workingDirectory, session.id),
                ).clineObject() ?: error("Cline returned an invalid load-session response")
                ClineAcpClient(
                    rpc,
                    session.id.value,
                    result.objectValue("models")?.string("currentModelId") ?: session.model,
                )
            } catch (error: Throwable) {
                rpc.close()
                throw error
            }
        }

        private suspend fun open(
            runtime: RemoteAgentRuntime,
            executable: String,
            options: StartSessionOptions,
            dispatcher: CoroutineDispatcher,
        ): ClineAcpRpc {
            require(options.providerOptions["autoApprove"]?.toBooleanStrictOrNull() != true) {
                "Cline ACP auto-approval is intentionally disabled"
            }
            val provider = options.providerOptions["provider"]
            val environment = buildMap {
                provider?.takeIf(String::isNotBlank)?.let { put("CLINE_PROVIDER", it) }
                options.model?.takeIf(String::isNotBlank)?.let { put("CLINE_MODEL", it) }
                options.providerOptions["apiKey"]?.takeIf(String::isNotBlank)?.let {
                    put("CLINE_API_KEY", it)
                }
                if ("CLINE_API_KEY" !in this && provider in KEYLESS_PROVIDERS) {
                    put("CLINE_API_KEY", "agent-relay-keyless-provider")
                }
            }
            val arguments = buildList {
                addAll(listOf("--acp", "--auto-approve", "false"))
                options.providerOptions["config"]?.takeIf(String::isNotBlank)?.let {
                    addAll(listOf("--config", it))
                }
                options.providerOptions["dataDir"]?.takeIf(String::isNotBlank)?.let {
                    addAll(listOf("--data-dir", it))
                }
            }
            val process = runtime.openProcess(
                RemoteCommand(
                    program = executable,
                    arguments = arguments,
                    environment = environment,
                    workingDirectory = options.workingDirectory,
                ),
            )
            return ClineAcpPeer(process, dispatcher)
        }

        private suspend fun initialize(rpc: ClineAcpRpc) {
            val result = rpc.request(
                "initialize",
                buildJsonObject {
                    put("protocolVersion", 1)
                    put("clientCapabilities", buildJsonObject {})
                    put(
                        "clientInfo",
                        buildJsonObject {
                            put("name", "agent-relay")
                            put("version", "0.1.0")
                        },
                    )
                },
            ).clineObject() ?: error("Cline returned an invalid initialize response")
            check(result.long("protocolVersion") == 1L) {
                "Cline returned an unsupported ACP protocol version"
            }
        }

        private fun buildSessionParams(
            workingDirectory: String?,
            sessionId: AgentSessionId? = null,
        ): JsonObject = buildJsonObject {
            sessionId?.let { put("sessionId", it.value) }
            put("cwd", workingDirectory ?: ".")
            put("mcpServers", JsonArray(emptyList()))
        }

        private val KEYLESS_PROVIDERS = setOf("ollama", "lmstudio")
    }
}
