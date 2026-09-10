/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.ssh.api

interface SshProfileStore {
    suspend fun profiles(): List<SshProfile>

    suspend fun profile(id: SshProfileId): SshProfile?

    suspend fun save(profile: SshProfile)

    suspend fun delete(id: SshProfileId)
}

interface SshCredentialStore {
    suspend fun put(
        id: SshCredentialId,
        purpose: SshCredentialPurpose,
        secret: SensitiveBytes,
    )

    suspend fun get(
        id: SshCredentialId,
        purpose: SshCredentialPurpose,
    ): SensitiveBytes?

    suspend fun delete(id: SshCredentialId)
}

interface SshHostKeyStore {
    suspend fun trustedKeys(endpoint: SshEndpoint): List<SshHostKey>

    suspend fun trustFirstUse(candidate: SshHostKey): Boolean

    suspend fun replace(
        candidate: SshHostKey,
        expectedFingerprints: Set<String>,
    ): Boolean

    suspend fun delete(endpoint: SshEndpoint)
}

class SshAuthenticationResolver(private val credentialStore: SshCredentialStore) {
    suspend fun resolve(profile: SshProfile): ResolvedSshAuthentication = when (val auth = profile.authentication) {
        is SshAuthentication.Password -> ResolvedSshAuthentication.Password(
            requireCredential(auth.credentialId, SshCredentialPurpose.PASSWORD),
        )

        is SshAuthentication.ImportedKey -> {
            val privateKey = requireCredential(
                auth.privateKeyCredentialId,
                SshCredentialPurpose.PRIVATE_KEY,
            )
            try {
                ResolvedSshAuthentication.ImportedKey(
                    privateKey = privateKey,
                    passphrase = auth.passphraseCredentialId?.let {
                        requireCredential(it, SshCredentialPurpose.PRIVATE_KEY_PASSPHRASE)
                    },
                )
            } catch (failure: Throwable) {
                privateKey.close()
                throw failure
            }
        }

        is SshAuthentication.AgentBacked -> ResolvedSshAuthentication.AgentBacked(auth.keyId)
    }

    private suspend fun requireCredential(
        id: SshCredentialId,
        purpose: SshCredentialPurpose,
    ): SensitiveBytes = credentialStore.get(id, purpose)
        ?: throw MissingSshCredentialException(id, purpose)
}

class MissingSshCredentialException(
    val credentialId: SshCredentialId,
    val purpose: SshCredentialPurpose,
) : IllegalStateException("Required SSH credential is unavailable")

class InMemorySshHostKeyStore : SshHostKeyStore {
    private val records = mutableMapOf<SshEndpoint, List<SshHostKey>>()

    override suspend fun trustedKeys(endpoint: SshEndpoint): List<SshHostKey> =
        synchronized(records) { records[endpoint].orEmpty() }

    override suspend fun trustFirstUse(candidate: SshHostKey): Boolean = synchronized(records) {
        if (records[candidate.endpoint].orEmpty().isNotEmpty()) {
            false
        } else {
            records[candidate.endpoint] = listOf(candidate)
            true
        }
    }

    override suspend fun replace(candidate: SshHostKey, expectedFingerprints: Set<String>): Boolean =
        synchronized(records) {
            val existing = records[candidate.endpoint].orEmpty()
            if (existing.map { it.sha256Fingerprint }.toSet() != expectedFingerprints) {
                false
            } else {
                records[candidate.endpoint] = listOf(candidate)
                true
            }
        }

    override suspend fun delete(endpoint: SshEndpoint) {
        synchronized(records) { records.remove(endpoint) }
    }
}
