/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.ssh.api

import kotlinx.coroutines.CancellationException

class ResolvedSshHost(
    val profile: SshProfile,
    val authentication: ResolvedSshAuthentication,
    trustedHostKeys: List<SshHostKey>,
) : AutoCloseable {
    val trustedHostKeys: List<SshHostKey> = trustedHostKeys.toList()

    override fun close() = authentication.close()

    override fun toString(): String =
        "ResolvedSshHost(profileId=${profile.id}, authentication=[REDACTED], trustedHostKeys=${trustedHostKeys.size})"
}

class SshConnectionRoute(
    val destination: ResolvedSshHost,
    jumpHosts: List<ResolvedSshHost> = emptyList(),
) : AutoCloseable {
    val jumpHosts: List<ResolvedSshHost> = jumpHosts.toList()

    init {
        val routeIds = this.jumpHosts.map { it.profile.id } + destination.profile.id
        require(routeIds.distinct().size == routeIds.size) {
            "An SSH connection route must not repeat a profile"
        }
    }

    val hostsInConnectionOrder: List<ResolvedSshHost>
        get() = jumpHosts + destination

    override fun close() {
        hostsInConnectionOrder.asReversed().forEach(ResolvedSshHost::close)
    }

    override fun toString(): String =
        "SshConnectionRoute(destination=${destination.profile.id}, jumpHosts=${jumpHosts.map { it.profile.id }})"
}

class SshConnectionRouteResolver(
    private val profiles: SshProfileStore,
    credentialStore: SshCredentialStore,
    private val hostKeys: SshHostKeyStore,
) {
    private val authenticationResolver = SshAuthenticationResolver(credentialStore)

    suspend fun resolve(destination: SshProfile): SshConnectionRoute {
        val configured = profiles.profiles().associateBy(SshProfile::id)
        val closestFirst = mutableListOf<SshProfile>()
        val visited = linkedSetOf(destination.id)
        var jumpHostId = destination.jumpHostProfileId
        while (jumpHostId != null) {
            val jumpHost = requireJumpHost(
                id = jumpHostId,
                visited = visited,
                currentDepth = closestFirst.size,
                configured = configured,
            )
            closestFirst += jumpHost
            jumpHostId = jumpHost.jumpHostProfileId
        }

        val resolved = mutableListOf<ResolvedSshHost>()
        try {
            closestFirst.asReversed().forEach { resolved += resolveHost(it) }
            resolved += resolveHost(destination)
            val route = SshConnectionRoute(
                destination = resolved.last(),
                jumpHosts = resolved.dropLast(1),
            )
            resolved.clear()
            return route
        } catch (cancelled: CancellationException) {
            resolved.asReversed().forEach(ResolvedSshHost::close)
            throw cancelled
        } catch (failure: Throwable) {
            resolved.asReversed().forEach(ResolvedSshHost::close)
            throw failure
        }
    }

    private fun requireJumpHost(
        id: SshProfileId,
        visited: MutableSet<SshProfileId>,
        currentDepth: Int,
        configured: Map<SshProfileId, SshProfile>,
    ): SshProfile {
        if (!visited.add(id)) {
            throw configurationFailure(
                code = "SSH_JUMP_HOST_CYCLE",
                message = "The SSH jump-host route contains a cycle. Edit the affected profiles.",
            )
        }
        if (currentDepth >= MAX_JUMP_HOSTS) {
            throw configurationFailure(
                code = "SSH_JUMP_HOST_ROUTE_TOO_DEEP",
                message = "The SSH jump-host route is too deep. Use at most $MAX_JUMP_HOSTS jump hosts.",
            )
        }
        return configured[id]
            ?: throw configurationFailure(
                code = "SSH_JUMP_HOST_MISSING",
                message = "A configured SSH jump host is unavailable. Edit the destination profile.",
            )
    }

    private suspend fun resolveHost(profile: SshProfile): ResolvedSshHost {
        val authentication = authenticationResolver.resolve(profile)
        return try {
            ResolvedSshHost(
                profile = profile,
                authentication = authentication,
                trustedHostKeys = hostKeys.trustedKeys(profile.endpoint),
            )
        } catch (failure: Throwable) {
            authentication.close()
            throw failure
        }
    }

    private fun configurationFailure(
        code: String,
        message: String,
    ) = SshConnectionException(
        SshFailure(
            category = SshFailureCategory.CONFIGURATION,
            code = code,
            actionableMessage = message,
            recoverable = false,
        ),
    )

    companion object {
        const val MAX_JUMP_HOSTS = 8
    }
}
