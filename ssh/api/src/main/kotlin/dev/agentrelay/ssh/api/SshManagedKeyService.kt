package dev.agentrelay.ssh.api

import dev.agentrelay.provider.api.RemoteCommand
import java.util.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class SshManagedKeyOperationResult(
    val publicKey: SshAgentPublicKey,
    val notice: String,
)

class SshManagedKeyService(
    private val profiles: SshProfileStore,
    credentialStore: SshCredentialStore,
    hostKeys: SshHostKeyStore,
    private val agentKeys: SshAgentKeyManager,
    private val connector: SshConnector,
    private val clock: SshClock = SshClock(System::currentTimeMillis),
    private val operationTimeout: Duration = 20.seconds,
) {
    private val routeResolver = SshConnectionRouteResolver(profiles, credentialStore, hostKeys)
    private val keyMutex = Mutex()

    init {
        require(operationTimeout.isPositive()) { "SSH key operation timeout must be positive" }
    }

    suspend fun ensureKey(profileId: SshProfileId): SshAgentPublicKey = keyMutex.withLock {
        val profile = requireProfile(profileId)
        val keyId = profile.appManagedKeyId
            ?: (profile.authentication as? SshAuthentication.AgentBacked)?.keyId
            ?: "${profile.id.value}.device-key.v1"
        val existing = agentKeys.publicKey(keyId)
        val publicKey = existing ?: agentKeys.create(keyId)
        if (profile.appManagedKeyId != keyId) {
            try {
                profiles.save(
                    profile.copy(
                        appManagedKeyId = keyId,
                        updatedAtEpochMillis = maxOf(clock.epochMillis(), profile.updatedAtEpochMillis),
                    ),
                )
            } catch (failure: Throwable) {
                val authenticationUsesKey =
                    (profile.authentication as? SshAuthentication.AgentBacked)?.keyId == keyId
                if (existing == null && !authenticationUsesKey) {
                    runCatching { agentKeys.delete(keyId) }
                }
                throw failure
            }
        }
        publicKey
    }

    suspend fun installPublicKey(profileId: SshProfileId): SshManagedKeyOperationResult {
        val publicKey = ensureKey(profileId)
        val profile = requireProfile(profileId)
        val authorizedKey = normalizedAuthorizedKey(publicKey)
        val result = withConnection(profile) { connection ->
            connection.runtime.execute(
                command = RemoteCommand(
                    program = "sh",
                    arguments = listOf("-c", installScript(authorizedKey)),
                ),
                timeout = operationTimeout,
            )
        }
        if (!result.successful) {
            throw SshConnectionException(
                SshFailure(
                    category = SshFailureCategory.REMOTE_PROCESS_EXIT,
                    code = "SSH_PUBLIC_KEY_INSTALL_FAILED",
                    actionableMessage =
                    "The public key could not be installed. Check the remote account's SSH directory permissions.",
                    recoverable = true,
                ),
            )
        }
        return SshManagedKeyOperationResult(
            publicKey = publicKey,
            notice = "The app-managed public key is installed for this remote account.",
        )
    }

    suspend fun verifyPasswordlessLogin(profileId: SshProfileId): SshManagedKeyOperationResult {
        val publicKey = ensureKey(profileId)
        val profile = requireProfile(profileId).copy(
            authentication = SshAuthentication.AgentBacked(publicKey.keyId),
            appManagedKeyId = publicKey.keyId,
        )
        withConnection(profile) { connection ->
            connection.heartbeat()
        }
        return SshManagedKeyOperationResult(
            publicKey = publicKey,
            notice = "Passwordless app-managed key login succeeded.",
        )
    }

    private suspend fun <T> withConnection(
        destination: SshProfile,
        block: suspend (SshTransportConnection) -> T,
    ): T {
        val route = routeResolver.resolve(destination)
        try {
            val connection = connector.connect(route)
            try {
                if (!connection.isConnected) {
                    throw SshConnectionException(
                        SshFailure(
                            category = SshFailureCategory.NETWORK,
                            code = "SSH_KEY_OPERATION_DISCONNECTED",
                            actionableMessage =
                            "The SSH connection closed before the key operation could run.",
                            recoverable = true,
                        ),
                    )
                }
                return block(connection)
            } finally {
                connection.close()
            }
        } finally {
            route.close()
        }
    }

    private suspend fun requireProfile(profileId: SshProfileId): SshProfile =
        profiles.profile(profileId)
            ?: throw SshConnectionException(
                SshFailure(
                    category = SshFailureCategory.CONFIGURATION,
                    code = "SSH_PROFILE_MISSING",
                    actionableMessage = "The SSH profile no longer exists.",
                    recoverable = false,
                ),
            )

    private fun normalizedAuthorizedKey(publicKey: SshAgentPublicKey): String {
        val parts = publicKey.openSshPublicKey.trim().split(' ')
            .filter(String::isNotEmpty)
        require(parts.size >= 2 && parts[0] == publicKey.algorithm) {
            "The app-managed SSH public key is invalid"
        }
        require(parts[0].matches(KEY_ALGORITHM)) { "The SSH public-key algorithm is invalid" }
        require(parts[1].matches(KEY_BASE64)) { "The SSH public-key encoding is invalid" }
        require(parts[1].length <= MAX_PUBLIC_KEY_BASE64_CHARS) {
            "The SSH public-key blob is invalid"
        }
        val decoded = try {
            Base64.getDecoder().decode(parts[1])
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("The SSH public-key encoding is invalid", failure)
        }
        require(decoded.isNotEmpty() && decoded.size <= MAX_PUBLIC_KEY_BLOB_BYTES) {
            "The SSH public-key blob is invalid"
        }
        requireEmbeddedAlgorithm(decoded, parts[0])
        return "${parts[0]} ${parts[1]}"
    }

    private fun installScript(authorizedKey: String): String = """
        set -eu
        umask 077
        ssh_dir=${'$'}HOME/.ssh
        authorized_keys=${'$'}ssh_dir/authorized_keys
        if [ -L "${'$'}ssh_dir" ] || [ -L "${'$'}authorized_keys" ]; then
          exit 73
        fi
        mkdir -p "${'$'}ssh_dir"
        touch "${'$'}authorized_keys"
        chmod 700 "${'$'}ssh_dir"
        chmod 600 "${'$'}authorized_keys"
        key='$authorizedKey'
        if ! grep -qxF "${'$'}key" "${'$'}authorized_keys"; then
          printf '\n%s\n' "${'$'}key" >> "${'$'}authorized_keys"
        fi
    """.trimIndent()

    private fun requireEmbeddedAlgorithm(
        blob: ByteArray,
        expected: String,
    ) {
        require(blob.size >= SSH_STRING_LENGTH_BYTES) { "The SSH public-key blob is invalid" }
        val algorithmLength = (0 until SSH_STRING_LENGTH_BYTES).fold(0) { length, index ->
            (length shl Byte.SIZE_BITS) or (blob[index].toInt() and BYTE_MASK)
        }
        require(
            algorithmLength in 1..MAX_KEY_ALGORITHM_BYTES &&
                SSH_STRING_LENGTH_BYTES + algorithmLength <= blob.size,
        ) { "The SSH public-key blob is invalid" }
        val embedded = blob.decodeToString(
            startIndex = SSH_STRING_LENGTH_BYTES,
            endIndex = SSH_STRING_LENGTH_BYTES + algorithmLength,
            throwOnInvalidSequence = true,
        )
        require(embedded == expected) { "The SSH public-key algorithm does not match its blob" }
    }

    companion object {
        private val KEY_ALGORITHM = Regex("[A-Za-z0-9@._+-]{1,128}")
        private val KEY_BASE64 = Regex("[A-Za-z0-9+/]+={0,2}")
        private const val MAX_PUBLIC_KEY_BLOB_BYTES = 16 * 1024
        private const val MAX_PUBLIC_KEY_BASE64_CHARS = 21_848
        private const val MAX_KEY_ALGORITHM_BYTES = 128
        private const val SSH_STRING_LENGTH_BYTES = 4
        private const val BYTE_MASK = 0xff
    }
}
