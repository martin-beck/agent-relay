package dev.agentrelay.provider.codex

import dev.agentrelay.provider.api.AGENT_PROVIDER_API_VERSION
import dev.agentrelay.provider.api.AgentCapability
import dev.agentrelay.provider.api.AgentProviderConnection
import dev.agentrelay.provider.api.AgentProviderDescriptor
import dev.agentrelay.provider.api.AgentProviderFactory
import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand

class CodexAgentProviderFactory : AgentProviderFactory {
    override val descriptor = AgentProviderDescriptor(
        id = CODEX_PROVIDER_ID,
        displayName = "Codex",
        providerVersion = PROVIDER_VERSION,
        apiVersion = AGENT_PROVIDER_API_VERSION,
        capabilities = setOf(
            AgentCapability.SESSION_DISCOVERY,
            AgentCapability.SESSION_START,
            AgentCapability.SESSION_RESUME,
            AgentCapability.SESSION_HISTORY,
            AgentCapability.LIVE_STREAMING,
            AgentCapability.ACTIVE_TURN_STEERING,
            AgentCapability.TURN_INTERRUPT,
            AgentCapability.APPROVALS,
            AgentCapability.FILE_CHANGES,
        ),
    )

    override suspend fun probe(runtime: RemoteAgentRuntime): ProviderReadiness {
        val location = runtime.execute(RemoteCommand("sh", listOf("-lc", FIND_CODEX_SCRIPT)))
        val executable = location.standardOutput.lineSequence()
            .firstOrNull { it.startsWith("/") && it.isNotBlank() }
            ?: return ProviderReadiness.Missing(
                installHint = "Install Codex from https://developers.openai.com/codex/cli",
            )

        val versionResult = runtime.execute(RemoteCommand(executable, listOf("--version")))
        val version = (versionResult.standardOutput + versionResult.standardError)
            .lineSequence()
            .firstOrNull(String::isNotBlank)
            ?.trim()
        if (!versionResult.successful) {
            return ProviderReadiness.Failed(
                reason = version ?: "Codex version check failed",
                recoverable = true,
            )
        }

        val appServer = runtime.execute(RemoteCommand(executable, listOf("app-server", "--help")))
        if (!appServer.successful) {
            return ProviderReadiness.Incompatible(
                version = version,
                reason = "This Codex build does not expose app-server",
            )
        }

        val login = runtime.execute(RemoteCommand(executable, listOf("login", "status")))
        val loginText = (login.standardOutput + login.standardError).trim()
        if (!login.successful || !loginText.contains("Logged in", ignoreCase = true)) {
            return ProviderReadiness.NeedsAuthentication(
                version = version,
                loginHint = "Run codex login on the remote host",
            )
        }

        return ProviderReadiness.Ready(
            version = version ?: "unknown",
            details = loginText.lineSequence().firstOrNull(),
        )
    }

    override suspend fun connect(runtime: RemoteAgentRuntime): AgentProviderConnection {
        val readiness = probe(runtime)
        check(readiness is ProviderReadiness.Ready) {
            "Codex is not ready: $readiness"
        }
        val process = runtime.openProcess(
            RemoteCommand("sh", listOf("-lc", START_APP_SERVER_SCRIPT)),
        )
        return CodexAgentConnection.create(descriptor, JsonRpcPeer(process))
    }

    private companion object {
        const val PROVIDER_VERSION = "0.1.0"

        const val FIND_CODEX_SCRIPT =
            "command -v codex 2>/dev/null || " +
                "{ test -x ~/.local/bin/codex && printf '%s\\n' ~/.local/bin/codex; } || " +
                "{ test -x ~/bin/codex && printf '%s\\n' ~/bin/codex; }"

        const val START_APP_SERVER_SCRIPT =
            "codex_bin=\$(" + FIND_CODEX_SCRIPT + ") && " +
                "test -n \"\$codex_bin\" && exec \"\$codex_bin\" app-server --stdio"
    }
}
