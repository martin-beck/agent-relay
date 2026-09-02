package dev.agentrelay.provider.opendesk

import dev.agentrelay.provider.api.AGENT_PROVIDER_API_VERSION
import dev.agentrelay.provider.api.AgentCapability
import dev.agentrelay.provider.api.AgentProviderConnection
import dev.agentrelay.provider.api.AgentProviderDescriptor
import dev.agentrelay.provider.api.AgentProviderFactory
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.opencode.OpenCodeCompatibleAgentProviderFactory
import dev.agentrelay.provider.opencode.OpenCodeCompatibleProviderConfiguration
import dev.agentrelay.provider.opencode.OpenCodeProtocolDialect

internal val OPENDESK_PROVIDER_ID = AgentProviderId("openharmony.opendesk")

class OpenDeskAgentProviderFactory(
    configDirectory: String? = null,
) : AgentProviderFactory {
    private val delegate = OpenCodeCompatibleAgentProviderFactory(
        OpenCodeCompatibleProviderConfiguration(
            descriptor = AgentProviderDescriptor(
                id = OPENDESK_PROVIDER_ID,
                displayName = "OpenDesk",
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
                ),
            ),
            executableName = "OpenDesk",
            installHint = "Install OpenDesk CLI with npm install -g @bitclub.ai/opendesk-cli",
            executableLookupScript = FIND_OPENDESK_SCRIPT,
            serveArguments = buildList {
                addAll(listOf("serve", "--mode", "opencode"))
                configDirectory?.let { directory ->
                    require(directory.isNotBlank()) { "OpenDesk config directory must not be blank" }
                    add("--config-directory")
                    add(directory)
                }
            },
            serveHelpMarker = "opencode",
            readinessDetails =
            "Authenticated loopback server API available; model access is checked when a session runs",
            protocolDialect = OpenCodeProtocolDialect.OPENDESK,
            metadataNamespace = "opendesk",
            prependExecutableDirectoryToPath = true,
        ),
    )

    override val descriptor: AgentProviderDescriptor = delegate.descriptor

    override suspend fun probe(runtime: RemoteAgentRuntime): ProviderReadiness = delegate.probe(runtime)

    override suspend fun connect(runtime: RemoteAgentRuntime): AgentProviderConnection =
        delegate.connect(runtime)

    private companion object {
        const val PROVIDER_VERSION = "0.1.0"

        const val FIND_OPENDESK_SCRIPT =
            "command -v opendesk 2>/dev/null || " +
                "{ test -x ~/.local/bin/opendesk && printf '%s\\n' ~/.local/bin/opendesk; } || " +
                "{ find ~/.nvm/versions/node -mindepth 3 -maxdepth 3 " +
                "-path '*/bin/opendesk' -type l -print 2>/dev/null | sort -V | tail -n 1; }"
    }
}
