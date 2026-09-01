package dev.agentrelay.ssh.api

import dev.agentrelay.connection.api.ConnectionProfileEditor
import dev.agentrelay.connection.api.ConnectionProfileDeleteException
import dev.agentrelay.connection.api.ConnectionProfileField
import dev.agentrelay.connection.api.ConnectionProfileFieldCondition
import dev.agentrelay.connection.api.ConnectionProfileFieldId
import dev.agentrelay.connection.api.ConnectionProfileFieldInput
import dev.agentrelay.connection.api.ConnectionProfileFieldOption
import dev.agentrelay.connection.api.ConnectionProfileFieldType
import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProfileManager
import dev.agentrelay.connection.api.ConnectionProfileOperation
import dev.agentrelay.connection.api.ConnectionProfileOperationException
import dev.agentrelay.connection.api.ConnectionProfileOperationId
import dev.agentrelay.connection.api.ConnectionProfileOperationResult
import dev.agentrelay.connection.api.ConnectionProfileSaveResult
import dev.agentrelay.connection.api.ConnectionProfileUpdate
import dev.agentrelay.connection.api.ConnectionProfileValidationException
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SshConnectionProfileManager(
    private val profiles: SshProfileStore,
    private val credentials: SshCredentialStore,
    private val hostKeys: SshHostKeyStore,
    private val agentKeys: SshAgentKeyManager,
    private val managedKeys: SshManagedKeyService? = null,
    private val clock: SshClock = SshClock(System::currentTimeMillis),
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) : ConnectionProfileManager {
    private val mutex = Mutex()

    override suspend fun editor(profileId: ConnectionProfileId?): ConnectionProfileEditor =
        mutex.withLock {
            val profile = profileId?.let {
                profiles.profile(SshProfileId(it.value))
                    ?: throw NoSuchElementException("SSH profile is unavailable")
            }
            val configuredProfiles = profiles.profiles()
            val authentication = profile?.authentication
            val authenticationValue = when (authentication) {
                is SshAuthentication.Password, null -> AUTH_PASSWORD
                is SshAuthentication.ImportedKey -> AUTH_IMPORTED_KEY
                is SshAuthentication.AgentBacked -> AUTH_AGENT_BACKED
            }
            val storedPassphrase =
                (authentication as? SshAuthentication.ImportedKey)?.passphraseCredentialId != null
            val managedKeyId = profile?.appManagedKeyId
                ?: (authentication as? SshAuthentication.AgentBacked)?.keyId
            val publicKey = managedKeyId?.let(agentKeys::publicKey)
            ConnectionProfileEditor(
                providerId = SshConnectionProvider.ID,
                providerName = "Secure Shell",
                profileId = profileId,
                title = if (profile == null) "Add Secure Shell profile" else "Edit Secure Shell profile",
                fields = listOf(
                    textField(
                        LABEL,
                        "Profile name",
                        profile?.label.orEmpty(),
                        128,
                        true,
                        "A recognizable name shown only in Agent Relay.",
                    ),
                    textField(HOST, "Host", profile?.endpoint?.host.orEmpty(), 253, true),
                    textField(
                        PORT,
                        "Port",
                        profile?.endpoint?.port?.toString() ?: "22",
                        5,
                        true,
                        type = ConnectionProfileFieldType.PORT,
                    ),
                    textField(USERNAME, "Username", profile?.username.orEmpty(), 128, true),
                    jumpHostField(profile, configuredProfiles),
                    authenticationField(authenticationValue),
                    secretField(
                        PASSWORD,
                        "Password",
                        MAX_PASSWORD_CHARS,
                        stored = authentication is SshAuthentication.Password,
                        condition = AUTH_PASSWORD,
                        replacement = "Enter a replacement password.",
                    ),
                    secretField(
                        PRIVATE_KEY,
                        "Private key",
                        MAX_PRIVATE_KEY_CHARS,
                        stored = authentication is SshAuthentication.ImportedKey,
                        condition = AUTH_IMPORTED_KEY,
                        replacement = "Paste a replacement private key.",
                        type = ConnectionProfileFieldType.MULTILINE_SECRET,
                    ),
                    ConnectionProfileField(
                        id = PASSPHRASE_MODE,
                        label = "Private-key passphrase",
                        type = ConnectionProfileFieldType.SINGLE_CHOICE,
                        value = if (storedPassphrase) PASSPHRASE_KEEP else PASSPHRASE_NONE,
                        required = true,
                        options = buildList {
                            if (storedPassphrase) {
                                add(option(PASSPHRASE_KEEP, "Keep stored passphrase"))
                            }
                            add(option(PASSPHRASE_NONE, "No passphrase"))
                            add(option(PASSPHRASE_REPLACE, "Set a new passphrase"))
                        },
                        visibleWhen = listOf(condition(AUTHENTICATION, AUTH_IMPORTED_KEY)),
                    ),
                    ConnectionProfileField(
                        id = PASSPHRASE,
                        label = "New private-key passphrase",
                        type = ConnectionProfileFieldType.PASSWORD,
                        supportingText = "Encrypted separately from the private key.",
                        required = true,
                        maxLength = MAX_PASSWORD_CHARS,
                        visibleWhen = listOf(
                            condition(AUTHENTICATION, AUTH_IMPORTED_KEY),
                            condition(PASSPHRASE_MODE, PASSPHRASE_REPLACE),
                        ),
                    ),
                    ConnectionProfileField(
                        id = PUBLIC_KEY,
                        label = "App-managed public key",
                        type = ConnectionProfileFieldType.READ_ONLY,
                        value = publicKey?.openSshPublicKey ?: "Created after saving this profile.",
                        supportingText =
                        "The private key stays in Android Keystore. Install this public key on the remote account.",
                        maxLength = MAX_PUBLIC_KEY_CHARS,
                    ),
                ),
                canDelete = profile != null,
                operations = if (profile == null) emptyList() else profileOperations(),
            )
        }

    private fun authenticationField(value: String) = ConnectionProfileField(
        id = AUTHENTICATION,
        label = "Authentication",
        type = ConnectionProfileFieldType.SINGLE_CHOICE,
        value = value,
        required = true,
        options = listOf(
            option(
                AUTH_PASSWORD,
                "Password",
                "Encrypted in Android Keystore-backed app storage.",
            ),
            option(
                AUTH_IMPORTED_KEY,
                "Imported private key",
                "Paste an OpenSSH or PEM private key.",
            ),
            option(
                AUTH_AGENT_BACKED,
                "Android Keystore key",
                "The private key never leaves Android Keystore.",
            ),
        ),
    )

    override suspend fun save(update: ConnectionProfileUpdate): ConnectionProfileSaveResult =
        mutex.withLock {
            require(update.providerId == SshConnectionProvider.ID) {
                "Connection profile update belongs to a different provider"
            }
            val previous = update.profileId?.let {
                profiles.profile(SshProfileId(it.value))
                    ?: throw NoSuchElementException("SSH profile is unavailable")
            }
            val input = ValidatedInput(update, previous)
            val profileId = previous?.id ?: allocateProfileId()
            val existingManagedKeyId = previous?.appManagedKeyId
                ?: (previous?.authentication as? SshAuthentication.AgentBacked)?.keyId
            val jumpHostProfileId = validatedJumpHost(input.jumpHostValue, profileId)
            val createdCredentials = mutableListOf<SshCredentialId>()
            val createdAgentKeys = mutableListOf<String>()
            val saved = try {
                val managedKeyId = ensureManagedKey(
                    profileId = profileId,
                    existingManagedKeyId = existingManagedKeyId,
                    created = createdAgentKeys,
                )
                val authentication = when (input.authentication) {
                    AUTH_PASSWORD -> passwordAuthentication(
                        profileId,
                        previous?.authentication,
                        input,
                        createdCredentials,
                    )
                    AUTH_IMPORTED_KEY -> importedKeyAuthentication(
                        profileId,
                        previous?.authentication,
                        input,
                        createdCredentials,
                    )
                    AUTH_AGENT_BACKED -> agentAuthentication(managedKeyId)
                    else -> error("Validated SSH authentication is unsupported")
                }
                val now = clock.epochMillis()
                SshProfile(
                    id = profileId,
                    label = input.label,
                    endpoint = input.endpoint,
                    username = input.username,
                    authentication = authentication,
                    jumpHostProfileId = jumpHostProfileId,
                    appManagedKeyId = managedKeyId,
                    createdAtEpochMillis = previous?.createdAtEpochMillis ?: now,
                    updatedAtEpochMillis = maxOf(now, previous?.updatedAtEpochMillis ?: now),
                ).also { profiles.save(it) }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    cleanupCreated(createdCredentials, createdAgentKeys)
                }
                throw cancelled
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    cleanupCreated(createdCredentials, createdAgentKeys)
                }
                throw failure
            }
            previous?.authentication?.let {
                withContext(NonCancellable) {
                    cleanupSuperseded(
                        previous = it,
                        current = saved.authentication,
                        retainedAgentKeyId = saved.appManagedKeyId,
                    )
                }
            }
            ConnectionProfileSaveResult(
                profile = saved.toConnectionSummary(),
                notice = if (saved.authentication is SshAuthentication.AgentBacked) {
                    "Profile saved. Add the displayed public key to the remote account before connecting."
                } else {
                    "Profile saved securely."
                },
            )
        }

    override suspend fun delete(profileId: ConnectionProfileId) = mutex.withLock {
        val sshProfileId = SshProfileId(profileId.value)
        val profile = profiles.profile(sshProfileId) ?: return@withLock
        if (profiles.profiles().any { it.jumpHostProfileId == sshProfileId }) {
            throw ConnectionProfileDeleteException(
                "Remove this profile as a jump host before deleting it.",
            )
        }
        profiles.delete(sshProfileId)
        withContext(NonCancellable) {
            cleanupAuthentication(
                authentication = profile.authentication,
                additionalAgentKeyId = profile.appManagedKeyId,
            )
            if (profiles.profiles().none { it.endpoint == profile.endpoint }) {
                runCleanup { hostKeys.delete(profile.endpoint) }
            }
        }
    }

    override suspend fun performOperation(
        profileId: ConnectionProfileId,
        operationId: ConnectionProfileOperationId,
    ): ConnectionProfileOperationResult = mutex.withLock {
        val service = managedKeys
            ?: throw ConnectionProfileOperationException(
                "SSH key operations are unavailable on this device.",
            )
        val sshProfileId = SshProfileId(profileId.value)
        if (profiles.profile(sshProfileId) == null) {
            throw ConnectionProfileOperationException("The SSH profile no longer exists.")
        }
        try {
            val result = when (operationId) {
                INSTALL_PUBLIC_KEY -> service.installPublicKey(sshProfileId)
                VERIFY_KEY_LOGIN -> service.verifyPasswordlessLogin(sshProfileId)
                else -> throw IllegalArgumentException("Unknown SSH profile operation")
            }
            ConnectionProfileOperationResult(result.notice)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (approval: SshHostKeyApprovalRequiredException) {
            val identity = when (approval.challenge.disposition) {
                SshHostKeyDisposition.UNKNOWN -> "unverified"
                SshHostKeyDisposition.CHANGED -> "changed"
            }
            throw ConnectionProfileOperationException(
                "The SSH host identity is $identity. Connect from the session hub, review it, and retry.",
                approval,
            )
        } catch (failure: SshConnectionException) {
            throw ConnectionProfileOperationException(failure.failure.actionableMessage, failure)
        }
    }

    private fun profileOperations(): List<ConnectionProfileOperation> = listOf(
        ConnectionProfileOperation(
            id = INSTALL_PUBLIC_KEY,
            label = "Install public key",
            supportingText =
            "Use the currently saved authentication to add only the app-managed public key.",
            confirmationTitle = "Install public key on this remote account?",
            confirmationMessage =
            "Agent Relay will connect with the saved credential and add this profile's public key " +
                "to ~/.ssh/authorized_keys. The private key never leaves Android Keystore.",
        ),
        ConnectionProfileOperation(
            id = VERIFY_KEY_LOGIN,
            label = "Test key-only login",
            supportingText =
            "Connect using only the app-managed key and confirm passwordless login works.",
        ),
    )

    private suspend fun allocateProfileId(): SshProfileId {
        repeat(MAX_ID_ATTEMPTS) {
            val candidate = SshProfileId("ssh-${generatedIdToken()}")
            if (profiles.profile(candidate) == null) return candidate
        }
        error("A unique SSH profile id could not be allocated")
    }

    private suspend fun validatedJumpHost(
        value: String,
        profileId: SshProfileId,
    ): SshProfileId? {
        if (value.isBlank() || value == NO_JUMP_HOST) return null
        val candidate = try {
            SshProfileId(value)
        } catch (_: IllegalArgumentException) {
            invalid(JUMP_HOST, "Choose a configured SSH jump host.")
        }
        val configured = profiles.profiles().associateBy(SshProfile::id)
        if (candidate !in configured) {
            invalid(JUMP_HOST, "The selected SSH jump host is no longer available.")
        }
        val visited = linkedSetOf(profileId)
        var current = candidate
        repeat(MAX_JUMP_HOSTS) {
            if (!visited.add(current)) {
                invalid(JUMP_HOST, "The selected SSH jump-host route contains a cycle.")
            }
            val currentProfile = configured[current]
                ?: invalid(JUMP_HOST, "The selected SSH jump-host route is incomplete.")
            val next = currentProfile.jumpHostProfileId ?: return candidate
            current = next
        }
        invalid(JUMP_HOST, "The selected SSH jump-host route is too deep.")
    }

    private fun jumpHostField(
        profile: SshProfile?,
        configured: List<SshProfile>,
    ) = ConnectionProfileField(
        id = JUMP_HOST,
        label = "Jump host",
        type = ConnectionProfileFieldType.SINGLE_CHOICE,
        value = profile?.jumpHostProfileId?.value ?: NO_JUMP_HOST,
        supportingText = "Connect through another configured SSH profile.",
        required = true,
        options = buildList {
            add(option(NO_JUMP_HOST, "Direct connection"))
            configured
                .filterNot { it.id == profile?.id }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
                .forEach { add(option(it.id.value, it.label, it.endpoint.displayName)) }
        },
    )

    private suspend fun passwordAuthentication(
        profileId: SshProfileId,
        previous: SshAuthentication?,
        input: ValidatedInput,
        created: MutableList<SshCredentialId>,
    ): SshAuthentication.Password {
        val supplied = input.secret(PASSWORD)
        return if (supplied != null && !supplied.isEmpty) {
            SshAuthentication.Password(
                storeCredential(
                    profileId,
                    "password",
                    SshCredentialPurpose.PASSWORD,
                    supplied,
                    created,
                ),
            )
        } else {
            previous as? SshAuthentication.Password ?: invalid(PASSWORD, "Enter a password.")
        }
    }

    private suspend fun importedKeyAuthentication(
        profileId: SshProfileId,
        previous: SshAuthentication?,
        input: ValidatedInput,
        created: MutableList<SshCredentialId>,
    ): SshAuthentication.ImportedKey {
        val suppliedKey = input.secret(PRIVATE_KEY)
        val keyId = if (suppliedKey != null && !suppliedKey.isEmpty) {
            storeCredential(
                profileId,
                "private-key",
                SshCredentialPurpose.PRIVATE_KEY,
                suppliedKey,
                created,
            )
        } else {
            (previous as? SshAuthentication.ImportedKey)?.privateKeyCredentialId
                ?: invalid(PRIVATE_KEY, "Paste a private key.")
        }
        val passphraseId = when (input.passphraseMode) {
            PASSPHRASE_KEEP -> (previous as? SshAuthentication.ImportedKey)
                ?.passphraseCredentialId
                ?: invalid(PASSPHRASE_MODE, "There is no stored passphrase to keep.")
            PASSPHRASE_NONE -> null
            PASSPHRASE_REPLACE -> {
                val passphrase = input.secret(PASSPHRASE)
                if (passphrase == null || passphrase.isEmpty) {
                    invalid(PASSPHRASE, "Enter the new private-key passphrase.")
                }
                storeCredential(
                    profileId,
                    "passphrase",
                    SshCredentialPurpose.PRIVATE_KEY_PASSPHRASE,
                    passphrase,
                    created,
                )
            }
            else -> error("Validated SSH passphrase mode is unsupported")
        }
        return SshAuthentication.ImportedKey(keyId, passphraseId)
    }

    private fun ensureManagedKey(
        profileId: SshProfileId,
        existingManagedKeyId: String?,
        created: MutableList<String>,
    ): String {
        val keyId = existingManagedKeyId ?: "${profileId.value}.device-key.v1"
        if (agentKeys.publicKey(keyId) == null) {
            agentKeys.create(keyId)
            created += keyId
        }
        return keyId
    }

    private fun agentAuthentication(managedKeyId: String): SshAuthentication.AgentBacked =
        SshAuthentication.AgentBacked(managedKeyId)

    private suspend fun storeCredential(
        profileId: SshProfileId,
        kind: String,
        purpose: SshCredentialPurpose,
        secret: ConnectionProfileFieldInput.Secret,
        created: MutableList<SshCredentialId>,
    ): SshCredentialId {
        val id = allocateCredentialId(profileId, kind, created)
        secret.toSensitiveBytes().use { credentials.put(id, purpose, it) }
        created += id
        return id
    }

    private suspend fun allocateCredentialId(
        profileId: SshProfileId,
        kind: String,
        created: List<SshCredentialId>,
    ): SshCredentialId {
        val referenced = profiles.profiles()
            .flatMap { it.authentication.credentialIds() }
            .toSet() + created
        repeat(MAX_ID_ATTEMPTS) {
            val candidate = SshCredentialId(
                "${profileId.value.take(72)}.$kind-${generatedIdToken()}",
            )
            if (candidate !in referenced) return candidate
        }
        error("A unique SSH credential id could not be allocated")
    }

    private suspend fun cleanupCreated(
        credentialIds: List<SshCredentialId>,
        agentKeyIds: List<String>,
    ) {
        credentialIds.forEach { id -> runCleanup { credentials.delete(id) } }
        agentKeyIds.forEach { id -> runCleanup { agentKeys.delete(id) } }
    }

    private suspend fun cleanupSuperseded(
        previous: SshAuthentication,
        current: SshAuthentication,
        retainedAgentKeyId: String?,
    ) {
        val currentCredentials = current.credentialIds()
        previous.credentialIds().filterNot(currentCredentials::contains)
            .forEach { id -> runCleanup { credentials.delete(id) } }
        val currentAgentKeys = current.agentKeyIds() + setOfNotNull(retainedAgentKeyId)
        previous.agentKeyIds().filterNot(currentAgentKeys::contains)
            .forEach { id -> runCleanup { agentKeys.delete(id) } }
    }

    private suspend fun cleanupAuthentication(
        authentication: SshAuthentication,
        additionalAgentKeyId: String?,
    ) {
        authentication.credentialIds().forEach { id -> runCleanup { credentials.delete(id) } }
        (authentication.agentKeyIds() + setOfNotNull(additionalAgentKeyId))
            .forEach { id -> runCleanup { agentKeys.delete(id) } }
    }

    private fun SshAuthentication.credentialIds(): Set<SshCredentialId> = when (this) {
        is SshAuthentication.Password -> setOf(credentialId)
        is SshAuthentication.ImportedKey ->
            setOfNotNull(privateKeyCredentialId, passphraseCredentialId)
        is SshAuthentication.AgentBacked -> emptySet()
    }

    private fun SshAuthentication.agentKeyIds(): Set<String> =
        (this as? SshAuthentication.AgentBacked)?.let { setOf(it.keyId) }.orEmpty()

    private suspend fun runCleanup(block: suspend () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
            // Encrypted orphan cleanup is best effort and never invalidates the saved profile.
        }
    }

    private fun generatedIdToken(): String = idGenerator().also {
        require(it.matches(ID_TOKEN_PATTERN)) {
            "Generated SSH profile token must be a bounded identifier"
        }
    }

    private inner class ValidatedInput(
        update: ConnectionProfileUpdate,
        previous: SshProfile?,
    ) {
        private val fields = update.fields
        private val errors = mutableMapOf<ConnectionProfileFieldId, String>()
        val label = text(LABEL).trim().also {
            if (it.isEmpty() || it.length > 128 || it.any(Char::isISOControl)) {
                errors[LABEL] = "Enter a profile name of 1 to 128 printable characters."
            }
        }
        private val host = text(HOST).trim()
        private val port = text(PORT).toIntOrNull().also {
            if (it !in 1..65535) errors[PORT] = "Enter a port from 1 to 65535."
        }
        val username = text(USERNAME).also {
            if (it.isBlank() || it.length > 128 || it.any(Char::isISOControl)) {
                errors[USERNAME] = "Enter a username of 1 to 128 characters."
            }
        }
        val authentication = text(AUTHENTICATION).also {
            if (it !in setOf(AUTH_PASSWORD, AUTH_IMPORTED_KEY, AUTH_AGENT_BACKED)) {
                errors[AUTHENTICATION] = "Choose a supported authentication method."
            }
        }
        val passphraseMode = text(PASSPHRASE_MODE).ifBlank {
            if ((previous?.authentication as? SshAuthentication.ImportedKey)
                    ?.passphraseCredentialId != null
            ) {
                PASSPHRASE_KEEP
            } else {
                PASSPHRASE_NONE
            }
        }.also {
            if (it !in setOf(PASSPHRASE_KEEP, PASSPHRASE_NONE, PASSPHRASE_REPLACE)) {
                errors[PASSPHRASE_MODE] =
                    "Choose how to handle the private-key passphrase."
            }
        }
        val jumpHostValue = text(JUMP_HOST).ifBlank { NO_JUMP_HOST }
        val endpoint: SshEndpoint

        init {
            require((fields.keys - EDITABLE_FIELDS).isEmpty()) {
                "Connection profile update contains unknown fields"
            }
            if (host.isEmpty() || host.length > 253) {
                errors[HOST] = "Enter a host name or address of 1 to 253 characters."
            }
            endpoint = try {
                SshEndpoint(host, port ?: 0)
            } catch (_: IllegalArgumentException) {
                errors.putIfAbsent(HOST, "Enter a valid host name or address.")
                SshEndpoint("invalid.example", 22)
            }
            validateSecret(PASSWORD, MAX_PASSWORD_CHARS)
            validateSecret(PRIVATE_KEY, MAX_PRIVATE_KEY_CHARS)
            validateSecret(PASSPHRASE, MAX_PASSWORD_CHARS)
            if (errors.isNotEmpty()) {
                throw ConnectionProfileValidationException(errors)
            }
        }

        fun secret(id: ConnectionProfileFieldId): ConnectionProfileFieldInput.Secret? =
            fields[id] as? ConnectionProfileFieldInput.Secret

        private fun validateSecret(
            id: ConnectionProfileFieldId,
            maxLength: Int,
        ) {
            val value = fields[id] ?: return
            if (value !is ConnectionProfileFieldInput.Secret) {
                errors[id] = "Enter this value in the secure field."
            } else if (value.length > maxLength) {
                errors[id] = "The secure value is too long."
            }
        }

        private fun text(id: ConnectionProfileFieldId): String =
            (fields[id] as? ConnectionProfileFieldInput.Text)?.value.orEmpty()
    }

    private fun ConnectionProfileFieldInput.Secret.toSensitiveBytes(): SensitiveBytes =
        useChars { chars ->
            val encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(chars))
            val bytes = ByteArray(encoded.remaining())
            encoded.get(bytes)
            try {
                SensitiveBytes.copyOf(bytes)
            } finally {
                Arrays.fill(bytes, 0)
                if (encoded.hasArray()) Arrays.fill(encoded.array(), 0)
            }
        }

    private fun SshProfile.toConnectionSummary() =
        dev.agentrelay.connection.api.ConnectionProfileSummary(
            id = ConnectionProfileId(id.value),
            providerId = SshConnectionProvider.ID,
            label = label,
            target = endpoint.displayName,
            authenticationLabel = when (authentication) {
                is SshAuthentication.Password -> "Password"
                is SshAuthentication.ImportedKey -> "Imported key"
                is SshAuthentication.AgentBacked -> "Agent-backed key"
            },
        )

    private fun textField(
        id: ConnectionProfileFieldId,
        label: String,
        value: String,
        maxLength: Int,
        required: Boolean,
        supportingText: String? = null,
        type: ConnectionProfileFieldType = ConnectionProfileFieldType.TEXT,
    ) = ConnectionProfileField(
        id = id,
        label = label,
        type = type,
        value = value,
        supportingText = supportingText,
        required = required,
        maxLength = maxLength,
    )

    private fun secretField(
        id: ConnectionProfileFieldId,
        label: String,
        maxLength: Int,
        stored: Boolean,
        condition: String,
        replacement: String,
        type: ConnectionProfileFieldType = ConnectionProfileFieldType.PASSWORD,
    ) = ConnectionProfileField(
        id = id,
        label = label,
        type = type,
        supportingText = if (stored) {
            "Stored securely. Leave blank to keep it, or $replacement"
        } else {
            replacement
        },
        required = !stored,
        maxLength = maxLength,
        visibleWhen = listOf(condition(AUTHENTICATION, condition)),
        hasStoredSecret = stored,
    )

    private fun option(
        value: String,
        label: String,
        supportingText: String? = null,
    ) = ConnectionProfileFieldOption(value, label, supportingText)

    private fun condition(field: ConnectionProfileFieldId, value: String) =
        ConnectionProfileFieldCondition(field, value)

    private fun invalid(field: ConnectionProfileFieldId, message: String): Nothing =
        throw ConnectionProfileValidationException(mapOf(field to message))

    companion object {
        internal val LABEL = ConnectionProfileFieldId("profile-label")
        internal val HOST = ConnectionProfileFieldId("host-name")
        internal val PORT = ConnectionProfileFieldId("host-port")
        internal val USERNAME = ConnectionProfileFieldId("username")
        internal val JUMP_HOST = ConnectionProfileFieldId("jump-host")
        internal val AUTHENTICATION = ConnectionProfileFieldId("authentication")
        internal val PASSWORD = ConnectionProfileFieldId("password")
        internal val PRIVATE_KEY = ConnectionProfileFieldId("private-key")
        internal val PASSPHRASE_MODE = ConnectionProfileFieldId("passphrase-mode")
        internal val PASSPHRASE = ConnectionProfileFieldId("passphrase")
        internal val PUBLIC_KEY = ConnectionProfileFieldId("public-key")
        internal val INSTALL_PUBLIC_KEY =
            ConnectionProfileOperationId("install-public-key")
        internal val VERIFY_KEY_LOGIN =
            ConnectionProfileOperationId("verify-key-login")

        private val EDITABLE_FIELDS = setOf(
            LABEL,
            HOST,
            PORT,
            USERNAME,
            JUMP_HOST,
            AUTHENTICATION,
            PASSWORD,
            PRIVATE_KEY,
            PASSPHRASE_MODE,
            PASSPHRASE,
        )
        private const val AUTH_PASSWORD = "password"
        private const val AUTH_IMPORTED_KEY = "imported-key"
        private const val AUTH_AGENT_BACKED = "agent-backed"
        private const val NO_JUMP_HOST = "direct"
        private const val PASSPHRASE_KEEP = "keep"
        private const val PASSPHRASE_NONE = "none"
        private const val PASSPHRASE_REPLACE = "replace"
        private const val MAX_PASSWORD_CHARS = 16 * 1024
        private const val MAX_PRIVATE_KEY_CHARS = 4 * 1024 * 1024
        private const val MAX_PUBLIC_KEY_CHARS = 16 * 1024
        private const val MAX_ID_ATTEMPTS = 8
        private const val MAX_JUMP_HOSTS =
            SshConnectionRouteResolver.MAX_JUMP_HOSTS
        private val ID_TOKEN_PATTERN = Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,47}")
    }
}
