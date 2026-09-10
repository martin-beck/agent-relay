/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.provider.api

class AgentProviderRegistry(factories: Iterable<AgentProviderFactory>) {
    private val providers: Map<AgentProviderId, AgentProviderFactory>

    init {
        val all = factories.toList()
        val incompatible = all.filter {
            it.descriptor.apiVersion != AGENT_PROVIDER_API_VERSION
        }
        require(incompatible.isEmpty()) {
            "Provider API mismatch: " +
                incompatible.joinToString { it.descriptor.id.toString() + "=" + it.descriptor.apiVersion }
        }

        val duplicateIds = all.groupBy { it.descriptor.id }.filterValues { it.size > 1 }.keys
        require(duplicateIds.isEmpty()) {
            "Duplicate provider ids: " + duplicateIds.joinToString()
        }

        providers = all.associateBy { it.descriptor.id }
    }

    fun descriptors(): List<AgentProviderDescriptor> = providers.values.map {
        it.descriptor
    }.sortedBy { it.displayName }

    fun factory(id: AgentProviderId): AgentProviderFactory = providers[id]
        ?: throw NoSuchElementException("No provider registered for " + id)
}
