/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.continuecli

import dev.agentrelay.provider.api.AGENT_PROVIDER_API_VERSION
import dev.agentrelay.provider.api.AgentCapability
import dev.agentrelay.provider.api.AgentProviderConnection
import dev.agentrelay.provider.api.AgentProviderDescriptor
import dev.agentrelay.provider.api.AgentProviderFactory
import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand

class ContinueAgentProviderFactory : AgentProviderFactory {
    override val descriptor = AgentProviderDescriptor(
        id = CONTINUE_PROVIDER_ID,
        displayName = "Continue CLI",
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
                installHint = "Install Continue CLI with npm install -g @continuedev/cli",
            )
        val versionResult = runtime.execute(RemoteCommand(executable, listOf("--version")))
        val version = (versionResult.standardOutput + versionResult.standardError)
            .lineSequence()
            .firstOrNull(String::isNotBlank)
            ?.trim()
        if (!versionResult.successful) {
            return ProviderReadiness.Failed(
                reason = version ?: "Continue CLI version check failed",
                recoverable = true,
            )
        }
        val serverHelp = runtime.execute(RemoteCommand(executable, listOf("serve", "--help")))
        val help = serverHelp.standardOutput + serverHelp.standardError
        if (!serverHelp.successful ||
            !help.contains("server", ignoreCase = true)
        ) {
            return ProviderReadiness.Incompatible(
                version = version,
                reason = "This Continue CLI build does not expose the server API",
            )
        }
        return ProviderReadiness.Ready(
            version = version ?: "unknown",
            details = "Loopback-safe server API available; model credentials are checked when connecting",
        )
    }

    override suspend fun connect(runtime: RemoteAgentRuntime): AgentProviderConnection {
        val readiness = probe(runtime)
        check(readiness is ProviderReadiness.Ready) { "Continue CLI is not ready: $readiness" }
        val executable = checkNotNull(resolveExecutable(runtime)) {
            "Continue CLI disappeared after the readiness check"
        }
        return ContinueAgentConnection.create(descriptor, runtime, executable)
    }

    private suspend fun resolveExecutable(runtime: RemoteAgentRuntime): String? {
        val location = runtime.execute(RemoteCommand("sh", listOf("-lc", FIND_CONTINUE_SCRIPT)))
        return location.standardOutput.lineSequence()
            .firstOrNull { it.startsWith("/") && it.isNotBlank() }
    }

    private companion object {
        const val PROVIDER_VERSION = "0.1.0"
        const val FIND_CONTINUE_SCRIPT =
            "command -v cn 2>/dev/null || " +
                "{ test -x ~/.local/bin/cn && printf '%s\\n' ~/.local/bin/cn; } || " +
                "{ test -x ~/.nvm/versions/node/v24.16.0/bin/cn && " +
                "printf '%s\\n' ~/.nvm/versions/node/v24.16.0/bin/cn; }"
    }
}
