package dev.agentrelay.provider.aider

import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.provider.api.AgentSession
import dev.agentrelay.provider.api.AgentSessionId
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import dev.agentrelay.provider.api.StartSessionOptions
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

internal class AiderProcessClient private constructor(
    private val rpc: AiderProcessRpc,
    override val sessionId: AgentSessionId,
    override val currentModel: String?,
    override val stateDirectory: String,
    override val chatHistoryPath: String,
    override val filesJson: String,
) : AiderClient {
    override suspend fun prompt(text: String): AiderPromptResult {
        require(text.isNotBlank()) { "Input must not be blank" }
        val response = rpc.request("prompt", text)
        val files = response.arrayValue("files").orEmpty().mapNotNull { element ->
            val row = element.aiderObject() ?: return@mapNotNull null
            val path = row.string("path") ?: return@mapNotNull null
            AiderFileResult(
                remotePath = path,
                kind = when (row.string("kind")) {
                    "added" -> AgentFileChangeKind.ADDED
                    "deleted" -> AgentFileChangeKind.DELETED
                    "renamed" -> AgentFileChangeKind.RENAMED
                    "modified" -> AgentFileChangeKind.MODIFIED
                    else -> AgentFileChangeKind.UNKNOWN
                },
            )
        }
        return AiderPromptResult(response.string("text").orEmpty(), files)
    }

    override suspend fun close() {
        rpc.close()
    }

    companion object {
        suspend fun startNew(
            runtime: RemoteAgentRuntime,
            interpreter: String,
            stateRoot: String,
            sessionId: AgentSessionId,
            options: StartSessionOptions,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): AiderProcessClient =
            open(
                runtime = runtime,
                interpreter = interpreter,
                stateRoot = stateRoot,
                sessionId = sessionId,
                workingDirectory = options.workingDirectory,
                requestedModel = options.model,
                config = options.providerOptions["config"],
                filesJson = validatedFiles(options.providerOptions["files"]),
                resume = false,
                dispatcher = dispatcher,
            )

        suspend fun resume(
            runtime: RemoteAgentRuntime,
            interpreter: String,
            stateRoot: String,
            session: AgentSession,
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ): AiderProcessClient =
            open(
                runtime = runtime,
                interpreter = interpreter,
                stateRoot = stateRoot,
                sessionId = session.id,
                workingDirectory = session.workingDirectory,
                requestedModel = session.model,
                config = session.metadata["aider.config"],
                filesJson = validatedFiles(session.metadata["aider.files"]),
                resume = true,
                dispatcher = dispatcher,
            )

        private suspend fun open(
            runtime: RemoteAgentRuntime,
            interpreter: String,
            stateRoot: String,
            sessionId: AgentSessionId,
            workingDirectory: String?,
            requestedModel: String?,
            config: String?,
            filesJson: String,
            resume: Boolean,
            dispatcher: CoroutineDispatcher,
        ): AiderProcessClient {
            val stateDirectory = stateRoot.trimEnd('/') + "/" + sessionId.value
            val chatHistoryPath = "$stateDirectory/chat-history.md"
            val process = runtime.openProcess(
                RemoteCommand(
                    program = interpreter,
                    arguments = listOf(
                        "-u",
                        "-c",
                        AIDER_HELPER_SCRIPT,
                        sessionId.value,
                        stateRoot,
                        workingDirectory.orEmpty(),
                        requestedModel.orEmpty(),
                        config.orEmpty(),
                        filesJson,
                        resume.toString(),
                    ),
                    workingDirectory = workingDirectory,
                ),
            )
            val rpc = AiderProcessPeer(process, dispatcher)
            return try {
                val ready = rpc.awaitReady()
                AiderProcessClient(
                    rpc = rpc,
                    sessionId = sessionId,
                    currentModel = ready.model ?: requestedModel,
                    stateDirectory = stateDirectory,
                    chatHistoryPath = chatHistoryPath,
                    filesJson = filesJson,
                )
            } catch (error: Throwable) {
                rpc.close()
                throw error
            }
        }

        private fun validatedFiles(value: String?): String {
            if (value.isNullOrBlank()) return "[]"
            val array = runCatching { JSON.parseToJsonElement(value) as? JsonArray }.getOrNull()
                ?: throw IllegalArgumentException("Aider files must be a JSON string array")
            require(array.all { it is JsonPrimitive && it.isString && it.content.isNotBlank() }) {
                "Aider files must be a JSON string array"
            }
            return JSON.encodeToString(JsonElement.serializer(), array)
        }

        private val JSON = Json
    }
}
