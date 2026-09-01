package dev.agentrelay.provider.claude

import dev.agentrelay.provider.api.AGENT_PROVIDER_API_VERSION
import dev.agentrelay.provider.api.AgentCapability
import dev.agentrelay.provider.api.AgentProviderConnection
import dev.agentrelay.provider.api.AgentProviderDescriptor
import dev.agentrelay.provider.api.AgentProviderFactory
import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import kotlinx.serialization.json.Json

class ClaudeAgentProviderFactory : AgentProviderFactory {
    override val descriptor = AgentProviderDescriptor(
        id = CLAUDE_PROVIDER_ID,
        displayName = "Claude Code",
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
                installHint = "Install Claude Code from https://claude.com/product/claude-code",
            )
        val versionResult = runtime.execute(RemoteCommand(executable, listOf("--version")))
        val versionText = (versionResult.standardOutput + versionResult.standardError)
            .lineSequence()
            .firstOrNull(String::isNotBlank)
            ?.trim()
        val version = versionText?.substringBefore(' ')
        if (!versionResult.successful) {
            return ProviderReadiness.Failed(
                versionText ?: "Claude Code version check failed",
                recoverable = true,
            )
        }
        val helpResult = runtime.execute(RemoteCommand(executable, listOf("--help")))
        val help = helpResult.standardOutput + helpResult.standardError
        if (!helpResult.successful ||
            !help.contains("stream-json") ||
            !help.contains("--session-id") ||
            !help.contains("--resume")
        ) {
            return ProviderReadiness.Incompatible(
                version,
                "This Claude Code build lacks the bidirectional stream protocol",
            )
        }
        val authResult = runtime.execute(
            RemoteCommand(executable, listOf("auth", "status", "--json")),
        )
        val auth = runCatching {
            Json.parseToJsonElement(authResult.standardOutput).objectOrNull()
        }.getOrNull()
        if (!authResult.successful || auth?.boolean("loggedIn") != true) {
            return ProviderReadiness.NeedsAuthentication(
                version,
                "Run claude auth login on the remote host",
            )
        }
        return ProviderReadiness.Ready(
            version = version ?: "unknown",
            authenticatedAs = auth.string("authMethod"),
            details = "Bidirectional stream-json control protocol available",
        )
    }

    override suspend fun connect(runtime: RemoteAgentRuntime): AgentProviderConnection {
        val readiness = probe(runtime)
        check(readiness is ProviderReadiness.Ready) { "Claude Code is not ready: $readiness" }
        val executable = checkNotNull(resolveExecutable(runtime)) {
            "Claude Code disappeared after the readiness check"
        }
        return ClaudeAgentConnection.create(descriptor, runtime, executable)
    }

    private suspend fun resolveExecutable(runtime: RemoteAgentRuntime): String? {
        val result = runtime.execute(RemoteCommand("sh", listOf("-lc", FIND_CLAUDE_SCRIPT)))
        return result.standardOutput.lineSequence()
            .firstOrNull { it.startsWith("/") && it.isNotBlank() }
    }

    private companion object {
        const val PROVIDER_VERSION = "0.1.0"
        const val FIND_CLAUDE_SCRIPT =
            "command -v claude 2>/dev/null || " +
                "{ test -x ~/.local/bin/claude && printf '%s\\n' ~/.local/bin/claude; } || " +
                "{ test -x ~/.claude/local/claude && printf '%s\\n' ~/.claude/local/claude; }"
    }
}
