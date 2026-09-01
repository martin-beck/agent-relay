package dev.agentrelay.provider.opencode

import dev.agentrelay.provider.api.AGENT_PROVIDER_API_VERSION
import dev.agentrelay.provider.api.AgentCapability
import dev.agentrelay.provider.api.AgentProviderConnection
import dev.agentrelay.provider.api.AgentProviderDescriptor
import dev.agentrelay.provider.api.AgentProviderFactory
import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand

class OpenCodeAgentProviderFactory : AgentProviderFactory {
    override val descriptor = AgentProviderDescriptor(
        id = OPENCODE_PROVIDER_ID,
        displayName = "OpenCode",
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
            ?: return ProviderReadiness.Missing(
                installHint = "Install OpenCode from https://opencode.ai/docs/",
            )
        val versionResult = runtime.execute(RemoteCommand(executable, listOf("--version")))
        val version = (versionResult.standardOutput + versionResult.standardError)
            .lineSequence()
            .firstOrNull(String::isNotBlank)
            ?.trim()
        if (!versionResult.successful) {
            return ProviderReadiness.Failed(
                reason = version ?: "OpenCode version check failed",
                recoverable = true,
            )
        }

        val serveHelp = runtime.execute(RemoteCommand(executable, listOf("serve", "--help")))
        if (!serveHelp.successful ||
            !(serveHelp.standardOutput + serveHelp.standardError).contains("server", ignoreCase = true)
        ) {
            return ProviderReadiness.Incompatible(
                version = version,
                reason = "This OpenCode build does not expose the headless server",
            )
        }
        return ProviderReadiness.Ready(
            version = version ?: "unknown",
            details = "Headless server API available; model credentials are checked when connecting",
        )
    }

    override suspend fun connect(runtime: RemoteAgentRuntime): AgentProviderConnection {
        val readiness = probe(runtime)
        check(readiness is ProviderReadiness.Ready) {
            "OpenCode is not ready: $readiness"
        }
        val executable = checkNotNull(resolveExecutable(runtime)) {
            "OpenCode disappeared after the readiness check"
        }
        val client = OpenCodeServerClient.start(runtime, executable)
        return try {
            OpenCodeAgentConnection.create(descriptor, client)
        } catch (error: Throwable) {
            client.close()
            throw error
        }
    }

    private suspend fun resolveExecutable(runtime: RemoteAgentRuntime): String? {
        val location = runtime.execute(RemoteCommand("sh", listOf("-lc", FIND_OPENCODE_SCRIPT)))
        return location.standardOutput.lineSequence()
            .firstOrNull { it.startsWith("/") && it.isNotBlank() }
    }

    private companion object {
        const val PROVIDER_VERSION = "0.1.0"

        const val FIND_OPENCODE_SCRIPT =
            "command -v opencode 2>/dev/null || " +
                "{ test -x ~/.opencode/bin/opencode && printf '%s\\n' ~/.opencode/bin/opencode; } || " +
                "{ test -x ~/.local/bin/opencode && printf '%s\\n' ~/.local/bin/opencode; }"
    }
}
