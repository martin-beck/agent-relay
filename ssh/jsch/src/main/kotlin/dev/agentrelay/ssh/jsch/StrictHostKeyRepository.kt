/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.ssh.jsch

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.UserInfo
import dev.agentrelay.ssh.api.SshEndpoint
import dev.agentrelay.ssh.api.SshHostKey
import dev.agentrelay.ssh.api.SshHostKeyChallenge
import dev.agentrelay.ssh.api.SshHostKeyDisposition
import java.security.MessageDigest
import java.util.Base64

internal class StrictHostKeyRepository(
    private val endpoint: SshEndpoint,
    trustedKeys: List<SshHostKey>,
    private val nowEpochMillis: () -> Long,
    private val onCheck: (trusted: Boolean) -> Unit = {},
) : HostKeyRepository {
    private val trusted = trustedKeys.toList()

    @Volatile
    private var observation: SshHostKeyChallenge? = null

    init {
        require(trusted.all { it.endpoint == endpoint }) {
            "Trusted host keys must belong to the connection endpoint"
        }
    }

    override fun check(host: String, key: ByteArray): Int {
        val candidate = candidate(key)
        val exact = trusted.any {
            it.algorithm == candidate.algorithm &&
                it.publicKeyBase64 == candidate.publicKeyBase64
        }
        onCheck(exact)
        if (exact) {
            observation = null
            return HostKeyRepository.OK
        }

        val disposition = if (trusted.isEmpty()) {
            SshHostKeyDisposition.UNKNOWN
        } else {
            SshHostKeyDisposition.CHANGED
        }
        observation = SshHostKeyChallenge(
            candidate = candidate,
            disposition = disposition,
            trustedFingerprints = trusted.map { it.sha256Fingerprint }.distinct().sorted(),
        )
        return if (disposition == SshHostKeyDisposition.UNKNOWN) {
            HostKeyRepository.NOT_INCLUDED
        } else {
            HostKeyRepository.CHANGED
        }
    }

    fun observedChallenge(): SshHostKeyChallenge? = observation

    override fun add(hostkey: HostKey, ui: UserInfo?) {
        throw UnsupportedOperationException("Host-key trust requires an explicit application decision")
    }

    override fun remove(host: String, type: String) {
        throw UnsupportedOperationException("Host-key removal requires an explicit application decision")
    }

    override fun remove(
        host: String,
        type: String,
        key: ByteArray,
    ) {
        throw UnsupportedOperationException("Host-key removal requires an explicit application decision")
    }

    override fun getKnownHostsRepositoryID(): String = "agent-relay-strict-host-keys"

    override fun getHostKey(): Array<HostKey> = trusted.map { it.toJschHostKey() }.toTypedArray()

    override fun getHostKey(host: String?, type: String?): Array<HostKey> = trusted
        .filter { type == null || it.algorithm == type }
        .map { it.toJschHostKey() }
        .toTypedArray()

    private fun candidate(key: ByteArray): SshHostKey {
        val parsed = try {
            HostKey(endpoint.displayName, key)
        } catch (failure: JSchException) {
            throw IllegalArgumentException("The server supplied an invalid SSH host key", failure)
        }
        val fingerprint = Base64.getEncoder()
            .withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
        return SshHostKey(
            endpoint = endpoint,
            algorithm = parsed.type,
            publicKeyBase64 = Base64.getEncoder().encodeToString(key),
            sha256Fingerprint = "SHA256:$fingerprint",
            trustedAtEpochMillis = nowEpochMillis(),
        )
    }

    private fun SshHostKey.toJschHostKey(): HostKey = try {
        HostKey(endpoint.displayName, Base64.getDecoder().decode(publicKeyBase64))
    } catch (failure: IllegalArgumentException) {
        throw IllegalStateException("A trusted SSH host key is corrupt", failure)
    } catch (failure: JSchException) {
        throw IllegalStateException("A trusted SSH host key is invalid", failure)
    }
}
