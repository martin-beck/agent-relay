package dev.agentrelay.ssh.android

import android.content.Context
import dev.agentrelay.ssh.api.SensitiveBytes
import dev.agentrelay.ssh.api.SshCredentialId
import dev.agentrelay.ssh.api.SshCredentialPurpose
import dev.agentrelay.ssh.api.SshCredentialStore
import dev.agentrelay.ssh.api.SshEndpoint
import dev.agentrelay.ssh.api.SshHostKey
import dev.agentrelay.ssh.api.SshHostKeyStore
import dev.agentrelay.ssh.api.SshProfile
import dev.agentrelay.ssh.api.SshProfileId
import dev.agentrelay.ssh.api.SshProfileStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Arrays

class AndroidSshProfileStore internal constructor(
    private val documents: SecureDocumentStore,
    private val json: Json = secureStoreJson(),
) : SshProfileStore {
    constructor(context: Context) : this(EncryptedFileDocumentStore(context))

    private val mutex = Mutex()
    private val serializer = ListSerializer(SshProfile.serializer())

    override suspend fun profiles(): List<SshProfile> = mutex.withLock {
        readProfiles()
            .sortedBy { it.label }
    }

    override suspend fun profile(id: SshProfileId): SshProfile? = mutex.withLock {
        readProfiles().firstOrNull { it.id == id }
    }

    override suspend fun save(profile: SshProfile) = mutex.withLock {
        val current = readProfiles().toMutableList()
        val index = current.indexOfFirst { it.id == profile.id }
        if (index >= 0) {
            val previous = current[index]
            require(profile.createdAtEpochMillis == previous.createdAtEpochMillis) {
                "SSH profile creation time cannot change"
            }
            require(profile.updatedAtEpochMillis >= previous.updatedAtEpochMillis) {
                "SSH profile updates must be monotonic"
            }
            current[index] = profile
        } else {
            current += profile
        }
        writeList(PROFILES_DOCUMENT, serializer, current)
    }

    override suspend fun delete(id: SshProfileId) = mutex.withLock {
        val current = readProfiles()
        val remaining = current.filterNot { it.id == id }
        if (remaining.size != current.size) {
            writeList(PROFILES_DOCUMENT, serializer, remaining)
        }
    }

    private suspend fun readProfiles(): List<SshProfile> =
        readList(PROFILES_DOCUMENT, serializer).validateUniqueBy(SshProfile::id, "SSH profile")

    private suspend fun <T> readList(
        documentId: String,
        serializer: KSerializer<List<T>>,
    ): List<T> {
        val plaintext = documents.read(documentId) ?: return emptyList()
        return try {
            json.decodeFromString(serializer, plaintext.decodeToString())
        } catch (failure: SecureStoreException) {
            throw failure
        } catch (failure: Throwable) {
            throw SecureStoreCorruptException(failure)
        } finally {
            Arrays.fill(plaintext, 0)
        }
    }

    private suspend fun <T> writeList(
        documentId: String,
        serializer: KSerializer<List<T>>,
        values: List<T>,
    ) {
        val plaintext = json.encodeToString(serializer, values).encodeToByteArray()
        try {
            documents.write(documentId, plaintext)
        } finally {
            Arrays.fill(plaintext, 0)
        }
    }

    companion object {
        private const val PROFILES_DOCUMENT = "ssh-profiles-v1"
    }
}

class AndroidSshHostKeyStore internal constructor(
    private val documents: SecureDocumentStore,
    private val json: Json = secureStoreJson(),
) : SshHostKeyStore {
    constructor(context: Context) : this(EncryptedFileDocumentStore(context))

    private val mutex = Mutex()
    private val serializer = ListSerializer(SshHostKey.serializer())

    override suspend fun trustedKeys(endpoint: SshEndpoint): List<SshHostKey> = mutex.withLock {
        read().filter { it.endpoint == endpoint }.sortedBy { it.algorithm }
    }

    override suspend fun trustFirstUse(candidate: SshHostKey): Boolean = mutex.withLock {
        val current = read()
        if (current.any { it.endpoint == candidate.endpoint }) {
            false
        } else {
            write(current + candidate)
            true
        }
    }

    override suspend fun replace(
        candidate: SshHostKey,
        expectedFingerprints: Set<String>,
    ): Boolean = mutex.withLock {
        val current = read()
        val endpointKeys = current.filter { it.endpoint == candidate.endpoint }
        if (endpointKeys.map { it.sha256Fingerprint }.toSet() != expectedFingerprints) {
            false
        } else {
            write(current.filterNot { it.endpoint == candidate.endpoint } + candidate)
            true
        }
    }

    override suspend fun delete(endpoint: SshEndpoint) = mutex.withLock {
        val current = read()
        val remaining = current.filterNot { it.endpoint == endpoint }
        if (remaining.size != current.size) {
            write(remaining)
        }
    }

    private suspend fun read(): List<SshHostKey> {
        val plaintext = documents.read(HOST_KEYS_DOCUMENT) ?: return emptyList()
        return try {
            json.decodeFromString(serializer, plaintext.decodeToString())
                .validateUniqueBy(
                    key = { it.endpoint to it.sha256Fingerprint },
                    type = "SSH host key",
                )
        } catch (failure: SecureStoreException) {
            throw failure
        } catch (failure: Throwable) {
            throw SecureStoreCorruptException(failure)
        } finally {
            Arrays.fill(plaintext, 0)
        }
    }

    private suspend fun write(keys: List<SshHostKey>) {
        val plaintext = json.encodeToString(serializer, keys).encodeToByteArray()
        try {
            documents.write(HOST_KEYS_DOCUMENT, plaintext)
        } finally {
            Arrays.fill(plaintext, 0)
        }
    }

    companion object {
        private const val HOST_KEYS_DOCUMENT = "ssh-host-keys-v1"
    }
}

class AndroidKeystoreSshCredentialStore internal constructor(
    private val documents: SecureDocumentStore,
) : SshCredentialStore {
    constructor(context: Context) : this(EncryptedFileDocumentStore(context))

    private val mutex = Mutex()

    override suspend fun put(
        id: SshCredentialId,
        purpose: SshCredentialPurpose,
        secret: SensitiveBytes,
    ) = mutex.withLock {
        require(secret.size in 1..MAX_CREDENTIAL_BYTES) {
            "SSH credential must be non-empty and bounded"
        }
        val value = secret.copy()
        val plaintext = ByteArray(HEADER_BYTES + value.size)
        try {
            plaintext[0] = FORMAT_VERSION
            plaintext[1] = purpose.code
            value.copyInto(plaintext, destinationOffset = HEADER_BYTES)
            documents.write(documentId(id), plaintext)
        } finally {
            Arrays.fill(value, 0)
            Arrays.fill(plaintext, 0)
        }
    }

    override suspend fun get(
        id: SshCredentialId,
        purpose: SshCredentialPurpose,
    ): SensitiveBytes? = mutex.withLock {
        val plaintext = documents.read(documentId(id)) ?: return@withLock null
        try {
            if (
                plaintext.size <= HEADER_BYTES ||
                plaintext[0] != FORMAT_VERSION ||
                plaintext[1] != purpose.code
            ) {
                throw SecureStoreCorruptException()
            }
            val extracted = plaintext.copyOfRange(HEADER_BYTES, plaintext.size)
            try {
                SensitiveBytes.copyOf(extracted)
            } finally {
                Arrays.fill(extracted, 0)
            }
        } finally {
            Arrays.fill(plaintext, 0)
        }
    }

    override suspend fun delete(id: SshCredentialId) = mutex.withLock {
        documents.delete(documentId(id))
    }

    private fun documentId(id: SshCredentialId): String = "ssh-credential-v1:${id.value}"

    private val SshCredentialPurpose.code: Byte
        get() = when (this) {
            SshCredentialPurpose.PASSWORD -> 1
            SshCredentialPurpose.PRIVATE_KEY -> 2
            SshCredentialPurpose.PRIVATE_KEY_PASSPHRASE -> 3
        }

    companion object {
        private const val FORMAT_VERSION: Byte = 1
        private const val HEADER_BYTES = 2
        private const val MAX_CREDENTIAL_BYTES = 4 * 1024 * 1024
    }
}

private fun secureStoreJson() = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    explicitNulls = true
    classDiscriminator = "authentication_type"
}

private fun <T, K> List<T>.validateUniqueBy(
    key: (T) -> K,
    type: String,
): List<T> {
    if (groupBy(key).any { it.value.size > 1 }) {
        throw SecureStoreCorruptException(IllegalStateException("Duplicate $type records"))
    }
    return this
}
