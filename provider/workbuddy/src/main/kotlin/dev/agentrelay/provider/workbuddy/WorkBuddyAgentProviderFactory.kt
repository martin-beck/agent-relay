/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.workbuddy

import dev.agentrelay.provider.api.AGENT_PROVIDER_API_VERSION
import dev.agentrelay.provider.api.AgentCapability
import dev.agentrelay.provider.api.AgentProviderConnection
import dev.agentrelay.provider.api.AgentProviderDescriptor
import dev.agentrelay.provider.api.AgentProviderFactory
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteAgentRuntime

/**
 * WorkBuddy Local Assistant's evidence-backed Open API v2 boundary.
 *
 * The remote environment holds the OAuth token and explicit consent marker. Agent Relay never
 * accepts either value as a provider option or command argument. Only the singleton Local
 * Assistant operations documented by the pinned WorkBuddy contract are advertised.
 */
class WorkBuddyAgentProviderFactory internal constructor(
    private val clientFactory: (RemoteAgentRuntime) -> WorkBuddyClient,
) : AgentProviderFactory {
    constructor() : this({ runtime -> RemoteWorkBuddyClient(runtime) })

    override val descriptor = DESCRIPTOR

    override suspend fun probe(runtime: RemoteAgentRuntime): ProviderReadiness =
        when (val status = clientFactory(runtime).onlineStatus()) {
            is WorkBuddyOnlineResult.Available -> ProviderReadiness.Ready(
                version = PROVIDER_VERSION,
                details = if (status.online) "Local Assistant is online" else "Local Assistant is offline",
            )
            WorkBuddyOnlineResult.AuthenticationRequired -> ProviderReadiness.NeedsAuthentication(
                version = PROVIDER_VERSION,
                loginHint = "Authorize the approved WorkBuddy Local Assistant scopes and enable explicit consent",
            )
            WorkBuddyOnlineResult.AuthorizationDenied -> ProviderReadiness.Failed(
                reason = "WorkBuddy Local Assistant permission is unavailable",
                recoverable = true,
            )
            WorkBuddyOnlineResult.Unavailable -> ProviderReadiness.Failed(
                reason = "WorkBuddy Local Assistant could not be reached",
                recoverable = true,
            )
        }

    override suspend fun connect(runtime: RemoteAgentRuntime): AgentProviderConnection =
        WorkBuddyAgentConnection(descriptor, clientFactory(runtime))

    internal companion object {
        const val PROVIDER_VERSION = "0.1.0"
        val PROVIDER_ID = AgentProviderId("workbuddy.localassistant")
        val DESCRIPTOR = AgentProviderDescriptor(
            id = PROVIDER_ID,
            displayName = "WorkBuddy Local Assistant",
            providerVersion = PROVIDER_VERSION,
            apiVersion = AGENT_PROVIDER_API_VERSION,
            capabilities = setOf(
                AgentCapability.SESSION_DISCOVERY,
                AgentCapability.SESSION_START,
                AgentCapability.SESSION_HISTORY,
            ),
        )
    }
}
