package dev.agentrelay.provider.clinecli

import dev.agentrelay.provider.api.AGENT_PROVIDER_API_VERSION
import dev.agentrelay.provider.api.AgentCapability
import dev.agentrelay.provider.api.AgentProviderConnection
import dev.agentrelay.provider.api.AgentProviderDescriptor
import dev.agentrelay.provider.api.AgentProviderFactory
import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

class ClineAgentProviderFactory : AgentProviderFactory {
    override val descriptor = AgentProviderDescriptor(
        id = CLINE_PROVIDER_ID,
        displayName = "Cline",
        providerVersion = PROVIDER_VERSION,
        apiVersion = AGENT_PROVIDER_API_VERSION,
        capabilities = setOf(
            AgentCapability.SESSION_DISCOVERY,
            AgentCapability.SESSION_START,
            AgentCapability.SESSION_RESUME,
            AgentCapability.SESSION_HISTORY,
            AgentCapability.LIVE_STREAMING,
            AgentCapability.TURN_INTERRUPT,
            AgentCapability.APPROVALS,
            AgentCapability.FILE_CHANGES,
        ),
    )

    override suspend fun probe(runtime: RemoteAgentRuntime): ProviderReadiness {
        val executable = resolveExecutable(runtime)
            ?: return ProviderReadiness.Missing("Install Cline CLI with npm install -g cline")
        val versionResult = runtime.execute(RemoteCommand(executable, listOf("--version")))
        val version = (versionResult.standardOutput + versionResult.standardError)
            .lineSequence()
            .firstOrNull(String::isNotBlank)
            ?.trim()
        if (!versionResult.successful) {
            return ProviderReadiness.Failed(
                version ?: "Cline version check failed",
                recoverable = true,
            )
        }
        val helpResult = runtime.execute(RemoteCommand(executable, listOf("--help")))
        val help = helpResult.standardOutput + helpResult.standardError
        if (!helpResult.successful ||
            !help.contains("--acp") ||
            !help.contains("--auto-approve") ||
            !help.contains("history")
        ) {
            return ProviderReadiness.Incompatible(
                version,
                "This Cline build lacks ACP, approvals, or JSON history",
            )
        }
        val history = runtime.execute(
            RemoteCommand(executable, listOf("history", "--limit", "1", "--json")),
            timeout = 30.seconds,
        )
        if (!history.successful) {
            return ProviderReadiness.Failed(
                "Cline history is unavailable: " + history.standardError.trim(),
                recoverable = true,
            )
        }
        val rows = runCatching {
            Json.parseToJsonElement(history.standardOutput) as? JsonArray
        }.getOrNull() ?: return ProviderReadiness.Incompatible(
            version,
            "Cline history did not return a JSON array",
        )
        val provider = rows.firstOrNull()?.clineObject()?.string("provider")
        return ProviderReadiness.Ready(
            version = version ?: "unknown",
            authenticatedAs = null,
            details = "ACP v1 and durable JSON history available" +
                provider?.let { "; latest stored provider is $it" }.orEmpty(),
        )
    }

    override suspend fun connect(runtime: RemoteAgentRuntime): AgentProviderConnection {
        val readiness = probe(runtime)
        check(readiness is ProviderReadiness.Ready) { "Cline is not ready: $readiness" }
        val executable = checkNotNull(resolveExecutable(runtime)) {
            "Cline disappeared after the readiness check"
        }
        return ClineAgentConnection.create(descriptor, runtime, executable)
    }

    private suspend fun resolveExecutable(runtime: RemoteAgentRuntime): String? {
        val result = runtime.execute(RemoteCommand("sh", listOf("-lc", FIND_CLINE_SCRIPT)))
        return result.standardOutput.lineSequence()
            .firstOrNull { it.startsWith("/") && it.isNotBlank() }
    }

    private companion object {
        const val PROVIDER_VERSION = "0.1.0"
        const val FIND_CLINE_SCRIPT =
            "command -v cline 2>/dev/null || " +
                "{ test -x ~/.local/bin/cline && printf '%s\n' ~/.local/bin/cline; }"
    }
}
