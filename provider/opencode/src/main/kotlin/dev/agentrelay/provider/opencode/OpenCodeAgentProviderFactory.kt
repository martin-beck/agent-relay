package dev.agentrelay.provider.opencode

import dev.agentrelay.provider.api.AGENT_PROVIDER_API_VERSION
import dev.agentrelay.provider.api.AgentCapability
import dev.agentrelay.provider.api.AgentProviderConnection
import dev.agentrelay.provider.api.AgentProviderDescriptor
import dev.agentrelay.provider.api.AgentProviderFactory
import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand

enum class OpenCodeProtocolDialect {
    OPENCODE,
    OPENDESK,
}

data class OpenCodeCompatibleProviderConfiguration(
    val descriptor: AgentProviderDescriptor,
    val executableName: String,
    val installHint: String,
    val executableLookupScript: String,
    val serveArguments: List<String>,
    val serveHelpArguments: List<String> = listOf("serve", "--help"),
    val serveHelpMarker: String = "server",
    val readinessDetails: String,
    val protocolDialect: OpenCodeProtocolDialect = OpenCodeProtocolDialect.OPENCODE,
    val metadataNamespace: String = executableName.lowercase(),
    val prependExecutableDirectoryToPath: Boolean = false,
)

class OpenCodeCompatibleAgentProviderFactory(
    private val configuration: OpenCodeCompatibleProviderConfiguration,
) : AgentProviderFactory {
    override val descriptor: AgentProviderDescriptor = configuration.descriptor

    override suspend fun probe(runtime: RemoteAgentRuntime): ProviderReadiness {
        val executable = resolveExecutable(runtime)
            ?: return ProviderReadiness.Missing(
                installHint = configuration.installHint,
            )
        val versionResult = runtime.execute(executable.command(listOf("--version")))
        val version = (versionResult.standardOutput + versionResult.standardError)
            .lineSequence()
            .firstOrNull(String::isNotBlank)
            ?.trim()
        if (!versionResult.successful) {
            return ProviderReadiness.Failed(
                reason = version ?: configuration.executableName + " version check failed",
                recoverable = true,
            )
        }

        val serveHelp = runtime.execute(executable.command(configuration.serveHelpArguments))
        if (!serveHelp.successful ||
            !(serveHelp.standardOutput + serveHelp.standardError).contains(
                configuration.serveHelpMarker,
                ignoreCase = true,
            )
        ) {
            return ProviderReadiness.Incompatible(
                version = version,
                reason = "This " + configuration.executableName +
                    " build does not expose the headless server",
            )
        }
        return ProviderReadiness.Ready(
            version = version ?: "unknown",
            details = configuration.readinessDetails,
        )
    }

    override suspend fun connect(runtime: RemoteAgentRuntime): AgentProviderConnection {
        val readiness = probe(runtime)
        check(readiness is ProviderReadiness.Ready) {
            configuration.executableName + " is not ready: $readiness"
        }
        val executable = checkNotNull(resolveExecutable(runtime)) {
            configuration.executableName + " disappeared after the readiness check"
        }
        val client = OpenCodeServerClient.start(runtime, executable, configuration)
        return try {
            OpenCodeAgentConnection.create(
                descriptor = descriptor,
                client = client,
                dialect = configuration.protocolDialect,
                providerName = descriptor.displayName,
                metadataNamespace = configuration.metadataNamespace,
            )
        } catch (error: Throwable) {
            client.close()
            throw error
        }
    }

    private suspend fun resolveExecutable(runtime: RemoteAgentRuntime): ResolvedOpenCodeExecutable? {
        val location = runtime.execute(
            RemoteCommand("sh", listOf("-lc", configuration.executableLookupScript)),
        )
        val path = location.standardOutput.lineSequence()
            .firstOrNull { it.startsWith("/") && it.isNotBlank() }
            ?: return null
        val environment = if (configuration.prependExecutableDirectoryToPath) {
            val directory = path.substringBeforeLast('/', missingDelimiterValue = "")
            if (directory.isBlank()) {
                emptyMap()
            } else {
                mapOf("PATH" to directory + ":" + STANDARD_REMOTE_PATH)
            }
        } else {
            emptyMap()
        }
        return ResolvedOpenCodeExecutable(path, environment)
    }

    private companion object {
        const val STANDARD_REMOTE_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    }
}

internal data class ResolvedOpenCodeExecutable(
    val path: String,
    val environment: Map<String, String> = emptyMap(),
) {
    fun command(arguments: List<String>): RemoteCommand =
        RemoteCommand(path, arguments, environment = environment)
}

class OpenCodeAgentProviderFactory : AgentProviderFactory by OpenCodeCompatibleAgentProviderFactory(
    OpenCodeCompatibleProviderConfiguration(
        descriptor = AgentProviderDescriptor(
            id = OPENCODE_PROVIDER_ID,
            displayName = "OpenCode",
            providerVersion = "0.1.0",
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
        ),
        executableName = "OpenCode",
        installHint = "Install OpenCode from https://opencode.ai/docs/",
        executableLookupScript =
        "command -v opencode 2>/dev/null || " +
            "{ test -x ~/.opencode/bin/opencode && printf '%s\\n' ~/.opencode/bin/opencode; } || " +
            "{ test -x ~/.local/bin/opencode && printf '%s\\n' ~/.local/bin/opencode; }",
        serveArguments = listOf("serve"),
        readinessDetails =
        "Headless server API available; model credentials are checked when connecting",
    ),
)
