package dev.agentrelay.ssh.android

import dev.agentrelay.ssh.api.SensitiveBytes
import dev.agentrelay.ssh.api.SshAuthentication
import dev.agentrelay.ssh.api.SshCredentialId
import dev.agentrelay.ssh.api.SshCredentialPurpose
import dev.agentrelay.ssh.api.SshEndpoint
import dev.agentrelay.ssh.api.SshHostKey
import dev.agentrelay.ssh.api.SshProfile
import dev.agentrelay.ssh.api.SshProfileId
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidSshStoresTest {
    @Test
    fun profileStoreRoundTripsAllAuthenticationTypesAndPreservesCreationTime() = runTest {
        val documents = InMemoryDocuments()
        val store = AndroidSshProfileStore(documents)
        val profiles = listOf(
            profile("password", SshAuthentication.Password(SshCredentialId("password"))),
            profile(
                "imported",
                SshAuthentication.ImportedKey(
                    SshCredentialId("private-key"),
                    SshCredentialId("passphrase"),
                ),
            ),
            profile("agent", SshAuthentication.AgentBacked("android-keystore-key")),
        )

        profiles.reversed().forEach { store.save(it) }

        assertEquals(profiles.map { it.label }.sorted(), store.profiles().map { it.label })
        val updated = profiles.first().copy(label = "Updated", updatedAtEpochMillis = 2L)
        store.save(updated)
        assertEquals(updated, store.profile(updated.id))
        assertFailsWith<IllegalArgumentException> {
            store.save(updated.copy(createdAtEpochMillis = 2L))
        }

        store.delete(updated.id)
        assertNull(store.profile(updated.id))
    }

    @Test
    fun credentialStoreBindsCiphertextToIdAndPurpose() = runTest {
        val documents = InMemoryDocuments()
        val store = AndroidKeystoreSshCredentialStore(documents)
        val id = SshCredentialId("credential")
        val source = "correct horse battery staple".encodeToByteArray()
        val secret = SensitiveBytes.copyOf(source)

        store.put(id, SshCredentialPurpose.PASSWORD, secret)
        assertContentEquals(source, secret.copy())
        store.get(id, SshCredentialPurpose.PASSWORD).use {
            assertContentEquals(source, it?.copy())
        }
        assertFailsWith<SecureStoreCorruptException> {
            store.get(id, SshCredentialPurpose.PRIVATE_KEY)
        }

        store.delete(id)
        assertNull(store.get(id, SshCredentialPurpose.PASSWORD))
        secret.close()
    }

    @Test
    fun hostKeyTrustUsesCompareAndSetForRotation() = runTest {
        val store = AndroidSshHostKeyStore(InMemoryDocuments())
        val original = hostKey("SHA256:AAAAAAAAAAAAAAAAAAAAAA", "QUJDREVGR0hJSktMTU5PUA==")
        val replacement = hostKey("SHA256:BBBBBBBBBBBBBBBBBBBBBB", "R0hJSktMTU5PUFFSU1RVVg==")

        assertTrue(store.trustFirstUse(original))
        assertFalse(store.trustFirstUse(replacement))
        assertFalse(store.replace(replacement, setOf("SHA256:staleFingerprintValue")))
        assertEquals(listOf(original), store.trustedKeys(original.endpoint))
        assertTrue(store.replace(replacement, setOf(original.sha256Fingerprint)))
        assertEquals(listOf(replacement), store.trustedKeys(original.endpoint))

        store.delete(original.endpoint)
        assertTrue(store.trustedKeys(original.endpoint).isEmpty())
    }

    @Test
    fun malformedProfileDocumentFailsClosed() = runTest {
        val documents = InMemoryDocuments()
        documents.write("ssh-profiles-v1", "not-json".encodeToByteArray())

        assertFailsWith<SecureStoreCorruptException> {
            AndroidSshProfileStore(documents).profiles()
        }
    }

    private class InMemoryDocuments : SecureDocumentStore {
        private val values = ConcurrentHashMap<String, ByteArray>()

        override suspend fun read(documentId: String): ByteArray? = values[documentId]?.copyOf()

        override suspend fun write(
            documentId: String,
            plaintext: ByteArray,
        ) {
            values[documentId] = plaintext.copyOf()
        }

        override suspend fun delete(documentId: String) {
            values.remove(documentId)
        }
    }

    companion object {
        private fun profile(
            id: String,
            authentication: SshAuthentication,
        ) = SshProfile(
            id = SshProfileId(id),
            label = id.replaceFirstChar(Char::uppercase),
            endpoint = SshEndpoint("$id.example.test"),
            username = "developer",
            authentication = authentication,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )

        private fun hostKey(
            fingerprint: String,
            publicKey: String,
        ) = SshHostKey(
            endpoint = SshEndpoint("example.test"),
            algorithm = "ssh-ed25519",
            publicKeyBase64 = publicKey,
            sha256Fingerprint = fingerprint,
            trustedAtEpochMillis = 1L,
        )
    }
}
