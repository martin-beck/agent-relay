/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.openjiuwen

import dev.agentrelay.provider.api.AGENT_PROVIDER_API_VERSION
import dev.agentrelay.provider.api.AgentProviderConnection
import dev.agentrelay.provider.api.AgentProviderDescriptor
import dev.agentrelay.provider.api.AgentProviderFactory
import dev.agentrelay.provider.api.AgentProviderId
import dev.agentrelay.provider.api.ProviderReadiness
import dev.agentrelay.provider.api.RemoteAgentRuntime
import dev.agentrelay.provider.api.RemoteCommand
import kotlin.time.Duration.Companion.seconds

/**
 * Discovery boundary for the OpenJiuwen Python SDK.
 *
 * The SDK is a library, not a relay protocol. Until a separately pinned and
 * reviewed bridge exists, this factory deliberately advertises no capabilities
 * and never invokes user code or an external tool.
 */
class OpenJiuwenAgentProviderFactory : AgentProviderFactory {
    override val descriptor = AgentProviderDescriptor(
        id = OPENJIUWEN_PROVIDER_ID,
        displayName = "openJiuwen Core",
        providerVersion = PROVIDER_VERSION,
        apiVersion = AGENT_PROVIDER_API_VERSION,
        capabilities = emptySet(),
    )

    override suspend fun probe(runtime: RemoteAgentRuntime): ProviderReadiness {
        val result = runtime.execute(
            RemoteCommand("python3", listOf("-c", SDK_PROBE_SCRIPT)),
            timeout = 10.seconds,
        )
        if (!result.successful) {
            return ProviderReadiness.Missing(
                installHint = "Install the pinned openJiuwen Core SDK from the approved source revision",
            )
        }
        val version = result.standardOutput.trim().takeIf { it.matches(VERSION_PATTERN) }
        if (version == null) {
            return ProviderReadiness.Missing(
                installHint = "Install the pinned openJiuwen Core SDK from the approved source revision",
            )
        }
        return ProviderReadiness.Incompatible(
            version = version,
            reason = "The SDK has no reviewed Agent Relay bridge; provider support remains disabled",
        )
    }

    override suspend fun connect(runtime: RemoteAgentRuntime): AgentProviderConnection =
        error("OpenJiuwen has no reviewed Agent Relay bridge")

    private companion object {
        val OPENJIUWEN_PROVIDER_ID = AgentProviderId("openjiuwen.core")
        const val PROVIDER_VERSION = "0.1.0"
        const val SDK_PROBE_SCRIPT =
            "import importlib.metadata; " +
                "print(importlib.metadata.version('openjiuwen'))"
        val VERSION_PATTERN = Regex("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?(?:[.-][A-Za-z0-9.-]+)?")
    }
}
