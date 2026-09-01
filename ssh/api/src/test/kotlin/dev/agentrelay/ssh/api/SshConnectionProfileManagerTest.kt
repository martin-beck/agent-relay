package dev.agentrelay.ssh.api

import dev.agentrelay.connection.api.ConnectionProfileFieldId
import dev.agentrelay.connection.api.ConnectionProfileFieldInput
import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProfileUpdate
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.connection.api.ConnectionProfileValidationException
import java.util.ArrayDeque
import java.util.Arrays
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SshConnectionProfileManagerTest {
    @Test
    fun passwordProfileRoundTripNeverReturnsSecretAndReplacesCredential() = runTest {
        val fixture = Fixture("profile", "password-one", "password-two")

        val created = profileUpdate(password = "first password").use {
            fixture.manager.save(it)
        }
        val profileId = SshProfileId(created.profile.id.value)
        val first = fixture.profiles.profile(profileId)!!
        val firstAuthentication = assertIs<SshAuthentication.Password>(first.authentication)
        assertContentEquals(
            "first password".encodeToByteArray(),
            fixture.credentials.values.getValue(firstAuthentication.credentialId).second,
        )

        val editor = fixture.manager.editor(created.profile.id)
        val passwordField = editor.fields.single { it.id == SshConnectionProfileManager.PASSWORD }
        assertEquals("", passwordField.value)
        assertTrue(passwordField.hasStoredSecret)
        assertFalse(editor.toString().contains("first password"))

        fixture.now = 200L
        val updated = profileUpdate(
            profileId = created.profile.id,
            label = "Renamed profile",
            password = "second password",
        ).use { fixture.manager.save(it) }
        val replacement = fixture.profiles.profile(profileId)!!
        val replacementAuthentication =
            assertIs<SshAuthentication.Password>(replacement.authentication)

        assertEquals("Renamed profile", updated.profile.label)
        assertEquals(100L, replacement.createdAtEpochMillis)
        assertEquals(200L, replacement.updatedAtEpochMillis)
        assertNotEquals(firstAuthentication.credentialId, replacementAuthentication.credentialId)
        assertEquals(setOf(firstAuthentication.credentialId), fixture.credentials.deleted)
        assertContentEquals(
            "second password".encodeToByteArray(),
            fixture.credentials.values.getValue(replacementAuthentication.credentialId).second,
        )
    }

    @Test
    fun importedKeyCanKeepThenRemovePassphraseWithoutExposingEitherSecret() = runTest {
        val fixture = Fixture("profile", "private-key", "passphrase")

        val created = profileUpdate(
            authentication = "imported-key",
            privateKey = "PRIVATE KEY MATERIAL",
            passphraseMode = "replace",
            passphrase = "key passphrase",
        ).use { fixture.manager.save(it) }
        val profileId = SshProfileId(created.profile.id.value)
        val original = assertIs<SshAuthentication.ImportedKey>(
            fixture.profiles.profile(profileId)!!.authentication,
        )

        val editor = fixture.manager.editor(created.profile.id)
        val privateKey = editor.fields.single {
            it.id == SshConnectionProfileManager.PRIVATE_KEY
        }
        val passphrase = editor.fields.single {
            it.id == SshConnectionProfileManager.PASSPHRASE
        }
        assertEquals("", privateKey.value)
        assertEquals("", passphrase.value)
        assertTrue(privateKey.hasStoredSecret)
        assertFalse(editor.toString().contains("PRIVATE KEY MATERIAL"))
        assertTrue(
            editor.fields.single {
                it.id == SshConnectionProfileManager.PASSPHRASE_MODE
            }.options.any { it.value == "keep" },
        )

        profileUpdate(
            profileId = created.profile.id,
            authentication = "imported-key",
            passphraseMode = "keep",
        ).use { fixture.manager.save(it) }
        val kept = assertIs<SshAuthentication.ImportedKey>(
            fixture.profiles.profile(profileId)!!.authentication,
        )
        assertEquals(original, kept)
        assertTrue(fixture.credentials.deleted.isEmpty())

        profileUpdate(
            profileId = created.profile.id,
            authentication = "imported-key",
            passphraseMode = "none",
        ).use { fixture.manager.save(it) }
        val withoutPassphrase = assertIs<SshAuthentication.ImportedKey>(
            fixture.profiles.profile(profileId)!!.authentication,
        )
        assertEquals(original.privateKeyCredentialId, withoutPassphrase.privateKeyCredentialId)
        assertNull(withoutPassphrase.passphraseCredentialId)
        assertEquals(setOf(original.passphraseCredentialId), fixture.credentials.deleted)
        assertTrue(original.privateKeyCredentialId in fixture.credentials.values)
    }

    @Test
    fun agentKeyCollisionIsRetriedAndDeleteRemovesKeyAndUnsharedHostTrust() = runTest {
        val fixture = Fixture("profile", "collision", "fresh")
        val collidingKeyId = "ssh-profile.agent-collision"
        fixture.agentKeys.keys[collidingKeyId] = agentPublicKey(collidingKeyId)

        val created = profileUpdate(authentication = "agent-backed").use {
            fixture.manager.save(it)
        }
        val profile = fixture.profiles.profile(SshProfileId(created.profile.id.value))!!
        val authentication = assertIs<SshAuthentication.AgentBacked>(profile.authentication)
        assertEquals("ssh-profile.agent-fresh", authentication.keyId)
        assertTrue(collidingKeyId in fixture.agentKeys.keys)

        val editor = fixture.manager.editor(created.profile.id)
        val publicKeyField = editor.fields.single {
            it.id == SshConnectionProfileManager.PUBLIC_KEY
        }
        assertTrue(publicKeyField.value.contains(authentication.keyId))
        assertFalse(publicKeyField.value.contains("PRIVATE"))

        fixture.manager.delete(created.profile.id)

        assertNull(fixture.profiles.profile(profile.id))
        assertEquals(setOf(authentication.keyId), fixture.agentKeys.deleted)
        assertTrue(collidingKeyId in fixture.agentKeys.keys)
        assertEquals(listOf(profile.endpoint), fixture.hostKeys.deleted)
    }

    @Test
    fun referencedCredentialCollisionIsRetriedWithoutOverwritingExistingSecret() = runTest {
        val fixture = Fixture("new", "collision", "fresh")
        val collidingId = SshCredentialId("ssh-new.password-collision")
        fixture.credentials.values[collidingId] =
            SshCredentialPurpose.PASSWORD to "existing secret".encodeToByteArray()
        fixture.profiles.values[SshProfileId("other")] = profile(
            id = "other",
            credentialId = collidingId,
        )

        val created = profileUpdate(password = "new secret").use {
            fixture.manager.save(it)
        }
        val authentication = assertIs<SshAuthentication.Password>(
            fixture.profiles.profile(SshProfileId(created.profile.id.value))!!.authentication,
        )

        assertEquals(SshCredentialId("ssh-new.password-fresh"), authentication.credentialId)
        assertContentEquals(
            "existing secret".encodeToByteArray(),
            fixture.credentials.values.getValue(collidingId).second,
        )
        assertContentEquals(
            "new secret".encodeToByteArray(),
            fixture.credentials.values.getValue(authentication.credentialId).second,
        )
    }

    @Test
    fun invalidSecureInputAndFailedSaveLeaveNoDurableCredential() = runTest {
        val fixture = Fixture("profile", "password")
        val fields = profileFields(password = null).toMutableMap().apply {
            put(SshConnectionProfileManager.PORT, text("70000"))
            put(SshConnectionProfileManager.PASSWORD, text("not a secure field"))
        }
        val invalid = assertFailsWith<ConnectionProfileValidationException> {
            ConnectionProfileUpdate(
                providerId = SshConnectionProvider.ID,
                profileId = null,
                fields = fields,
            ).use { fixture.manager.save(it) }
        }
        assertTrue(SshConnectionProfileManager.PORT in invalid.fieldErrors)
        assertTrue(SshConnectionProfileManager.PASSWORD in invalid.fieldErrors)
        assertTrue(fixture.profiles.values.isEmpty())
        assertTrue(fixture.credentials.values.isEmpty())

        fixture.profiles.saveFailure = IllegalStateException("simulated write failure")
        assertFailsWith<IllegalStateException> {
            profileUpdate(password = "temporary secret").use {
                fixture.manager.save(it)
            }
        }
        assertTrue(fixture.profiles.values.isEmpty())
        assertTrue(fixture.credentials.values.isEmpty())
        assertEquals(1, fixture.credentials.deleted.size)

        val secret = secret("never log this")
        assertFalse(secret.toString().contains("never log this"))
        secret.close()
        assertFailsWith<IllegalStateException> { secret.length }
    }

    @Test
    fun invalidProfilesGeneratedIdsAndProviderFieldsAreRejected() = runTest {
        val fixture = Fixture("unused")
        val missing = ConnectionProfileId("missing")

        assertFailsWith<NoSuchElementException> {
            fixture.manager.editor(missing)
        }
        assertFailsWith<NoSuchElementException> {
            profileUpdate(profileId = missing, password = "password").use {
                fixture.manager.save(it)
            }
        }
        ConnectionProfileUpdate(
            providerId = ConnectionProviderId("local.device"),
            profileId = null,
            fields = profileFields(password = "password"),
        ).use { foreignUpdate ->
            assertFailsWith<IllegalArgumentException> {
                fixture.manager.save(foreignUpdate)
            }
        }

        val unknownFields = profileFields(password = "password").toMutableMap().apply {
            put(ConnectionProfileFieldId("unexpected-field"), text("value"))
        }
        ConnectionProfileUpdate(
            providerId = SshConnectionProvider.ID,
            profileId = null,
            fields = unknownFields,
        ).use { unknownUpdate ->
            assertFailsWith<IllegalArgumentException> {
                fixture.manager.save(unknownUpdate)
            }
        }

        val malformedId = Fixture("invalid token")
        assertFailsWith<IllegalArgumentException> {
            profileUpdate(password = "password").use {
                malformedId.manager.save(it)
            }
        }

        val collidingIds = Fixture(*Array(64) { "collision" })
        collidingIds.profiles.values[SshProfileId("ssh-collision")] = profile(
            id = "ssh-collision",
            credentialId = SshCredentialId("existing-password"),
        )
        assertFailsWith<IllegalStateException> {
            profileUpdate(password = "password").use {
                collidingIds.manager.save(it)
            }
        }
    }

    @Test
    fun validationReportsEveryMalformedProviderFieldWithoutPersistingSecrets() = runTest {
        val fixture = Fixture("profile")
        val invalidFields = profileFields(password = null).toMutableMap().apply {
            put(SshConnectionProfileManager.LABEL, text("invalid\nlabel"))
            put(SshConnectionProfileManager.HOST, text("h".repeat(254)))
            put(SshConnectionProfileManager.USERNAME, text("\u0000"))
            put(SshConnectionProfileManager.AUTHENTICATION, text("unsupported"))
            put(SshConnectionProfileManager.PASSPHRASE_MODE, text("unsupported"))
            put(
                SshConnectionProfileManager.PASSWORD,
                secret("x".repeat(16_385)),
            )
        }

        val invalid = assertFailsWith<ConnectionProfileValidationException> {
            ConnectionProfileUpdate(
                providerId = SshConnectionProvider.ID,
                profileId = null,
                fields = invalidFields,
            ).use { fixture.manager.save(it) }
        }

        assertEquals(
            setOf(
                SshConnectionProfileManager.LABEL,
                SshConnectionProfileManager.HOST,
                SshConnectionProfileManager.USERNAME,
                SshConnectionProfileManager.AUTHENTICATION,
                SshConnectionProfileManager.PASSPHRASE_MODE,
                SshConnectionProfileManager.PASSWORD,
            ),
            invalid.fieldErrors.keys,
        )
        assertTrue(fixture.profiles.values.isEmpty())
        assertTrue(fixture.credentials.values.isEmpty())

        assertFailsWith<ConnectionProfileValidationException> {
            profileUpdate(password = null).use { fixture.manager.save(it) }
        }
    }

    private class Fixture(vararg ids: String) {
        val profiles = MutableProfileStore()
        val credentials = MutableCredentialStore()
        val hostKeys = RecordingHostKeyStore()
        val agentKeys = RecordingAgentKeyManager()
        var now = 100L
        private val generatedIds = ArrayDeque(ids.toList())
        val manager = SshConnectionProfileManager(
            profiles = profiles,
            credentials = credentials,
            hostKeys = hostKeys,
            agentKeys = agentKeys,
            clock = SshClock { now },
            idGenerator = { generatedIds.removeFirst() },
        )
    }
}

private class MutableProfileStore : SshProfileStore {
    val values = linkedMapOf<SshProfileId, SshProfile>()
    var saveFailure: Throwable? = null

    override suspend fun profiles(): List<SshProfile> = values.values.toList()

    override suspend fun profile(id: SshProfileId): SshProfile? = values[id]

    override suspend fun save(profile: SshProfile) {
        saveFailure?.let { throw it }
        values[profile.id] = profile
    }

    override suspend fun delete(id: SshProfileId) {
        values.remove(id)
    }
}

private class MutableCredentialStore : SshCredentialStore {
    val values = linkedMapOf<SshCredentialId, Pair<SshCredentialPurpose, ByteArray>>()
    val deleted = linkedSetOf<SshCredentialId>()

    override suspend fun put(
        id: SshCredentialId,
        purpose: SshCredentialPurpose,
        secret: SensitiveBytes,
    ) {
        values[id] = purpose to secret.copy()
    }

    override suspend fun get(
        id: SshCredentialId,
        purpose: SshCredentialPurpose,
    ): SensitiveBytes? = values[id]
        ?.takeIf { it.first == purpose }
        ?.second
        ?.let(SensitiveBytes::copyOf)

    override suspend fun delete(id: SshCredentialId) {
        values.remove(id)?.second?.let { Arrays.fill(it, 0) }
        deleted += id
    }
}

private class RecordingHostKeyStore : SshHostKeyStore {
    val deleted = mutableListOf<SshEndpoint>()

    override suspend fun trustedKeys(endpoint: SshEndpoint): List<SshHostKey> = emptyList()

    override suspend fun trustFirstUse(candidate: SshHostKey): Boolean = true

    override suspend fun replace(
        candidate: SshHostKey,
        expectedFingerprints: Set<String>,
    ): Boolean = true

    override suspend fun delete(endpoint: SshEndpoint) {
        deleted += endpoint
    }
}

private class RecordingAgentKeyManager : SshAgentKeyManager {
    val keys = linkedMapOf<String, SshAgentPublicKey>()
    val deleted = linkedSetOf<String>()

    override fun create(
        keyId: String,
        requireUserAuthentication: Boolean,
    ): SshAgentPublicKey {
        check(keyId !in keys)
        return agentPublicKey(keyId).also { keys[keyId] = it }
    }

    override fun publicKey(keyId: String): SshAgentPublicKey? = keys[keyId]

    override fun delete(keyId: String): Boolean {
        deleted += keyId
        return keys.remove(keyId) != null
    }
}

private fun profileUpdate(
    profileId: ConnectionProfileId? = null,
    label: String = "Development server",
    authentication: String = "password",
    password: String? = null,
    privateKey: String? = null,
    passphraseMode: String = "none",
    passphrase: String? = null,
): ConnectionProfileUpdate = ConnectionProfileUpdate(
    providerId = SshConnectionProvider.ID,
    profileId = profileId,
    fields = profileFields(
        label = label,
        authentication = authentication,
        password = password,
        privateKey = privateKey,
        passphraseMode = passphraseMode,
        passphrase = passphrase,
    ),
)

private fun profileFields(
    label: String = "Development server",
    authentication: String = "password",
    password: String? = null,
    privateKey: String? = null,
    passphraseMode: String = "none",
    passphrase: String? = null,
): Map<ConnectionProfileFieldId, ConnectionProfileFieldInput> = buildMap {
    put(SshConnectionProfileManager.LABEL, text(label))
    put(SshConnectionProfileManager.HOST, text("example.test"))
    put(SshConnectionProfileManager.PORT, text("22"))
    put(SshConnectionProfileManager.USERNAME, text("developer"))
    put(SshConnectionProfileManager.AUTHENTICATION, text(authentication))
    put(SshConnectionProfileManager.PASSPHRASE_MODE, text(passphraseMode))
    password?.let { put(SshConnectionProfileManager.PASSWORD, secret(it)) }
    privateKey?.let { put(SshConnectionProfileManager.PRIVATE_KEY, secret(it)) }
    passphrase?.let { put(SshConnectionProfileManager.PASSPHRASE, secret(it)) }
}

private fun text(value: String): ConnectionProfileFieldInput.Text =
    ConnectionProfileFieldInput.Text(value)

private fun secret(value: String): ConnectionProfileFieldInput.Secret {
    val characters = value.toCharArray()
    return try {
        ConnectionProfileFieldInput.Secret.copyOf(characters)
    } finally {
        Arrays.fill(characters, '\u0000')
    }
}

private fun profile(
    id: String,
    credentialId: SshCredentialId,
) = SshProfile(
    id = SshProfileId(id),
    label = "Existing",
    endpoint = SshEndpoint("other.example"),
    username = "developer",
    authentication = SshAuthentication.Password(credentialId),
    createdAtEpochMillis = 1L,
    updatedAtEpochMillis = 1L,
)

private fun agentPublicKey(keyId: String) = SshAgentPublicKey(
    keyId = keyId,
    algorithm = "ssh-ed25519",
    sha256Fingerprint = "SHA256:AAAAAAAAAAAAAAAAAAAAAA",
    openSshPublicKey = "ssh-ed25519 AAAA $keyId",
)
