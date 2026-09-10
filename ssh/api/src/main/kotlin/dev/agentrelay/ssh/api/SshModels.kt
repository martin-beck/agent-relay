/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.ssh.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Arrays

@Serializable
@JvmInline
value class SshProfileId(val value: String) {
    init {
        require(value.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}"))) {
            "SSH profile id must be a stable identifier"
        }
    }

    override fun toString(): String = value
}

@Serializable
@JvmInline
value class SshCredentialId(val value: String) {
    init {
        require(value.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}"))) {
            "SSH credential id must be a stable identifier"
        }
    }

    override fun toString(): String = value
}

@Serializable
data class SshEndpoint(val host: String, val port: Int = 22) {
    init {
        require(host.isNotBlank() && host.length <= 253) { "SSH host must not be blank" }
        require(host.none { it.isWhitespace() || it.isISOControl() || it == '/' || it == '\\' }) {
            "SSH host contains an unsafe character"
        }
        require(port in 1..65535) { "SSH port is outside the valid range" }
    }

    val displayName: String
        get() = if (port == 22) host else "[$host]:$port"
}

@Serializable
sealed interface SshAuthentication {
    @Serializable
    @SerialName("password")
    data class Password(val credentialId: SshCredentialId) : SshAuthentication

    @Serializable
    @SerialName("imported_key")
    data class ImportedKey(
        val privateKeyCredentialId: SshCredentialId,
        val passphraseCredentialId: SshCredentialId? = null,
    ) : SshAuthentication

    @Serializable
    @SerialName("agent_backed")
    data class AgentBacked(val keyId: String) : SshAuthentication {
        init {
            require(keyId.isNotBlank() && keyId.length <= 256 && keyId.none(Char::isISOControl)) {
                "Agent key id must be a bounded printable identifier"
            }
        }
    }
}

@Serializable
data class SshProfile(
    val id: SshProfileId,
    val label: String,
    val endpoint: SshEndpoint,
    val username: String,
    val authentication: SshAuthentication,
    val jumpHostProfileId: SshProfileId? = null,
    val appManagedKeyId: String? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(label.isNotBlank() && label.length <= 128 && label.none(Char::isISOControl)) {
            "SSH profile label must be a bounded printable value"
        }
        require(username.isNotBlank() && username.length <= 128) { "SSH username must not be blank" }
        require(username.none { it.isISOControl() || it == '\u0000' }) {
            "SSH username contains an unsafe character"
        }
        require(createdAtEpochMillis >= 0L && updatedAtEpochMillis >= createdAtEpochMillis) {
            "SSH profile timestamps are inconsistent"
        }
        require(jumpHostProfileId != id) {
            "An SSH profile cannot use itself as a jump host"
        }
        require(
            appManagedKeyId == null ||
                appManagedKeyId.isNotBlank() &&
                appManagedKeyId.length <= 256 &&
                appManagedKeyId.none(Char::isISOControl),
        ) { "App-managed SSH key id must be a bounded printable identifier" }
    }
}

data class SshAgentPublicKey(
    val keyId: String,
    val algorithm: String,
    val sha256Fingerprint: String,
    val openSshPublicKey: String,
)

interface SshAgentKeyManager {
    fun create(
        keyId: String,
        requireUserAuthentication: Boolean = false,
    ): SshAgentPublicKey

    fun publicKey(keyId: String): SshAgentPublicKey?

    fun delete(keyId: String): Boolean
}

enum class SshCredentialPurpose {
    PASSWORD,
    PRIVATE_KEY,
    PRIVATE_KEY_PASSPHRASE,
}

class SensitiveBytes private constructor(private var bytes: ByteArray?) : AutoCloseable {
    val size: Int
        get() = requireOpen().size

    fun copy(): ByteArray = requireOpen().copyOf()

    fun <T> useBytes(block: (ByteArray) -> T): T {
        val temporary = copy()
        return try {
            block(temporary)
        } finally {
            Arrays.fill(temporary, 0)
        }
    }

    override fun close() {
        bytes?.let { Arrays.fill(it, 0) }
        bytes = null
    }

    override fun toString(): String = "SensitiveBytes([REDACTED])"

    private fun requireOpen(): ByteArray = checkNotNull(bytes) { "Sensitive bytes have been closed" }

    companion object {
        fun copyOf(bytes: ByteArray): SensitiveBytes = SensitiveBytes(bytes.copyOf())
    }
}

sealed interface ResolvedSshAuthentication : AutoCloseable {
    data class Password(val password: SensitiveBytes) : ResolvedSshAuthentication {
        override fun close() = password.close()
    }

    data class ImportedKey(
        val privateKey: SensitiveBytes,
        val passphrase: SensitiveBytes?,
    ) : ResolvedSshAuthentication {
        override fun close() {
            privateKey.close()
            passphrase?.close()
        }
    }

    data class AgentBacked(val keyId: String) : ResolvedSshAuthentication {
        override fun close() = Unit
    }
}

@Serializable
data class SshHostKey(
    val endpoint: SshEndpoint,
    val algorithm: String,
    val publicKeyBase64: String,
    val sha256Fingerprint: String,
    val trustedAtEpochMillis: Long,
) {
    init {
        require(algorithm.matches(Regex("[A-Za-z0-9@._+-]{1,128}"))) { "Invalid host-key algorithm" }
        require(publicKeyBase64.matches(Regex("[A-Za-z0-9+/]+={0,2}"))) { "Invalid host-key encoding" }
        require(sha256Fingerprint.matches(Regex("SHA256:[A-Za-z0-9+/]{20,}"))) {
            "Invalid SHA-256 host-key fingerprint"
        }
        require(trustedAtEpochMillis >= 0L) { "Invalid host-key trust timestamp" }
    }
}

enum class SshHostKeyDisposition {
    UNKNOWN,
    CHANGED,
}

data class SshHostKeyChallenge(
    val candidate: SshHostKey,
    val disposition: SshHostKeyDisposition,
    val trustedFingerprints: List<String>,
) {
    init {
        require(
            disposition == SshHostKeyDisposition.UNKNOWN || trustedFingerprints.isNotEmpty(),
        ) { "A changed host key must identify the previously trusted fingerprint" }
    }
}
