package dev.agentrelay.storage.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EncryptedFileDocumentStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun documentsAreEncryptedAuthenticatedAndAtomicallyReplaced() = runTest {
        val root = temporaryFolder.newFolder("secure")
        val store = EncryptedFileDocumentStore(
            associatedDataPrefix = "agent-relay:test-store:v1",
            root = root,
            cipher = TestAesGcmCipher(),
            dispatcher = Dispatchers.Unconfined,
        )
        val first = "credential-one".encodeToByteArray()
        val second = "credential-two".encodeToByteArray()

        store.write("credential:test", first)
        assertContentEquals(first, store.read("credential:test"))
        assertFalse(root.singleFile().readBytes().containsSubsequence(first))

        store.write("credential:test", second)
        assertContentEquals(second, store.read("credential:test"))
        assertFalse(root.singleFile().readBytes().containsSubsequence(second))
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".pending") })

        val persisted = root.singleFile()
        val tampered = persisted.readBytes().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        persisted.writeBytes(tampered)
        assertFailsWith<SecureStoreCorruptException> {
            store.read("credential:test")
        }
    }

    @Test
    fun associatedDataSeparatesStoreNamespaces() = runTest {
        val root = temporaryFolder.newFolder("namespaced")
        val cipher = TestAesGcmCipher()
        val first = EncryptedFileDocumentStore(
            root = root,
            cipher = cipher,
            associatedDataPrefix = "agent-relay:first:v1",
            dispatcher = Dispatchers.Unconfined,
        )
        val second = EncryptedFileDocumentStore(
            root = root,
            cipher = cipher,
            associatedDataPrefix = "agent-relay:second:v1",
            dispatcher = Dispatchers.Unconfined,
        )

        first.write("same-document", "secret".encodeToByteArray())

        assertFailsWith<SecureStoreCorruptException> {
            second.read("same-document")
        }
    }

    @Test
    fun namespaceRejectsDirectoryTraversal() {
        assertFailsWith<IllegalArgumentException> {
            SecureDocumentNamespace(
                directoryName = "../outside",
                associatedDataPrefix = "agent-relay:test:v1",
                keyAlias = "agent-relay.test",
            )
        }
    }

    private fun File.singleFile(): File = listFiles().orEmpty().single { it.extension == "bin" }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean =
        indices.any { start ->
            start + needle.size <= size &&
                needle.indices.all { offset -> this[start + offset] == needle[offset] }
        }

    private class TestAesGcmCipher : DocumentCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        private val random = SecureRandom()

        override fun encrypt(
            associatedData: ByteArray,
            plaintext: ByteArray,
        ): ByteArray {
            val iv = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(associatedData)
            return iv + cipher.doFinal(plaintext)
        }

        override fun decrypt(
            associatedData: ByteArray,
            envelope: ByteArray,
        ): ByteArray {
            val iv = envelope.copyOfRange(0, 12)
            val ciphertext = envelope.copyOfRange(12, envelope.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(associatedData)
            return cipher.doFinal(ciphertext)
        }
    }
}
