package dev.agentrelay.provider.aider

import dev.agentrelay.provider.api.AGENT_PROVIDER_API_VERSION
import dev.agentrelay.provider.api.AgentCapability
import dev.agentrelay.provider.api.AgentProviderConnection
import dev.agentrelay.provider.api.AgentProviderDescriptor
import dev.agentrelay.provider.api.AgentProviderFactory
import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import kotlin.time.Duration.Companion.seconds

class AiderAgentProviderFactory : AgentProviderFactory {
    override val descriptor = AgentProviderDescriptor(
        id = AIDER_PROVIDER_ID,
        displayName = "Aider",
        providerVersion = PROVIDER_VERSION,
        apiVersion = AGENT_PROVIDER_API_VERSION,
        capabilities = setOf(
            AgentCapability.SESSION_DISCOVERY,
            AgentCapability.SESSION_START,
            AgentCapability.SESSION_RESUME,
            AgentCapability.SESSION_HISTORY,
            AgentCapability.TURN_INTERRUPT,
            AgentCapability.FILE_CHANGES,
        ),
    )

    override suspend fun probe(runtime: RemoteAgentRuntime): ProviderReadiness {
        val executable = resolveExecutable(runtime)
            ?: return ProviderReadiness.Missing(
                "Install Aider with uv tool install --python 3.12 aider-chat",
            )
        val versionResult = runtime.execute(RemoteCommand(executable, listOf("--version")))
        val version = (versionResult.standardOutput + versionResult.standardError)
            .lineSequence()
            .firstOrNull(String::isNotBlank)
            ?.trim()
        if (!versionResult.successful) {
            return ProviderReadiness.Failed(
                version ?: "Aider version check failed",
                recoverable = true,
            )
        }
        val helpResult = runtime.execute(RemoteCommand(executable, listOf("--help")))
        val help = helpResult.standardOutput + helpResult.standardError
        if (!helpResult.successful ||
            !help.contains("--restore-chat-history") ||
            !help.contains("--no-auto-commits") ||
            !help.contains("--no-suggest-shell-commands")
        ) {
            return ProviderReadiness.Incompatible(
                version,
                "This Aider build lacks safe resumable one-shot controls",
            )
        }
        val interpreter = resolveInterpreter(runtime, executable)
            ?: return ProviderReadiness.Incompatible(
                version,
                "Aider's Python interpreter could not be resolved",
            )
        val compatibility = runtime.execute(
            RemoteCommand(interpreter, listOf("-c", COMPATIBILITY_SCRIPT)),
            timeout = 30.seconds,
        )
        if (!compatibility.successful || compatibility.standardOutput.trim() != "compatible") {
            return ProviderReadiness.Incompatible(
                version,
                "Aider's installed Python API is not compatible with the safe relay helper",
            )
        }
        return ProviderReadiness.Ready(
            version = version ?: "unknown",
            authenticatedAs = null,
            details = "Safe resumable helper available; model authentication is checked " +
                "on the first prompt; unstructured confirmations are declined",
        )
    }

    override suspend fun connect(runtime: RemoteAgentRuntime): AgentProviderConnection {
        val readiness = probe(runtime)
        check(readiness is ProviderReadiness.Ready) { "Aider is not ready: $readiness" }
        val executable = checkNotNull(resolveExecutable(runtime)) {
            "Aider disappeared after the readiness check"
        }
        val interpreter = checkNotNull(resolveInterpreter(runtime, executable)) {
            "Aider's interpreter disappeared after the readiness check"
        }
        val home = runtime.execute(
            RemoteCommand("python3", listOf("-c", HOME_SCRIPT)),
            timeout = 15.seconds,
        )
        check(home.successful && home.standardOutput.trim().startsWith("/")) {
            "Unable to resolve the remote home directory for Aider state"
        }
        val stateRoot = home.standardOutput.trim().trimEnd('/') +
            "/.local/state/agent-relay/aider"
        return AiderAgentConnection.create(
            descriptor,
            runtime,
            interpreter,
            stateRoot,
        )
    }

    private suspend fun resolveExecutable(runtime: RemoteAgentRuntime): String? {
        val result = runtime.execute(RemoteCommand("sh", listOf("-lc", FIND_AIDER_SCRIPT)))
        return result.standardOutput.lineSequence()
            .firstOrNull { it.startsWith("/") && it.isNotBlank() }
    }

    private suspend fun resolveInterpreter(
        runtime: RemoteAgentRuntime,
        executable: String,
    ): String? {
        val result = runtime.execute(
            RemoteCommand("python3", listOf("-c", RESOLVE_INTERPRETER_SCRIPT, executable)),
        )
        return result.standardOutput.lineSequence()
            .firstOrNull { it.startsWith("/") && it.isNotBlank() }
    }

    private companion object {
        const val PROVIDER_VERSION = "0.1.0"
        const val FIND_AIDER_SCRIPT =
            "command -v aider 2>/dev/null || " +
                "{ test -x ~/.local/bin/aider && printf '%s\n' ~/.local/bin/aider; }"
        val RESOLVE_INTERPRETER_SCRIPT =
            """
            import os
            import pathlib
            import sys

            executable = pathlib.Path(sys.argv[1]).resolve()
            with executable.open(encoding="utf-8", errors="replace") as stream:
                first = stream.readline().strip()
            candidate = first[2:].split()[0] if first.startswith("#!") else ""
            if candidate.startswith("/") and os.access(candidate, os.X_OK):
                print(candidate)
            """.trimIndent()
        val COMPATIBILITY_SCRIPT =
            """
            import inspect
            from aider.coders.base_coder import Coder
            from aider.main import main

            main_parameters = inspect.signature(main).parameters
            run_parameters = inspect.signature(Coder.run).parameters
            if "return_coder" in main_parameters and "with_message" in run_parameters:
                print("compatible")
            """.trimIndent()
        const val HOME_SCRIPT =
            "import pathlib; print(pathlib.Path.home().resolve())"
    }
}
