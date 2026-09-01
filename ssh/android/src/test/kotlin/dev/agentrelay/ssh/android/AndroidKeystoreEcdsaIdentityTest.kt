package dev.agentrelay.ssh.android

import org.junit.Test
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidKeystoreEcdsaIdentityTest {
    @Test
    fun identityProducesSshPublicKeyAndVerifiableSignature() {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val identity = AndroidKeystoreEcdsaIdentity(
            name = "test",
            privateKey = pair.private,
            publicKey = pair.public as ECPublicKey,
        )
        val data = "authenticate this SSH session".encodeToByteArray()

        val publicBlob = SshReader(identity.publicKeyBlob)
        assertEquals(EcdsaSshEncoding.ALGORITHM, publicBlob.string().decodeToString())
        assertEquals("nistp256", publicBlob.string().decodeToString())
        val point = publicBlob.string()
        assertEquals(65, point.size)
        assertEquals(0x04, point.first().toInt())
        publicBlob.requireExhausted()

        val signatureBlob = requireNotNull(identity.getSignature(data))
        val signature = SshReader(signatureBlob)
        assertEquals(EcdsaSshEncoding.ALGORITHM, signature.string().decodeToString())
        val inner = SshReader(signature.string())
        val r = inner.string()
        val s = inner.string()
        inner.requireExhausted()
        signature.requireExhausted()

        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(pair.public)
        verifier.update(data)
        assertTrue(verifier.verify(derSignature(r, s)))
        assertNull(identity.getSignature(data, "ssh-rsa"))
    }

    @Test
    fun repositoryExposesOnlyTheSelectedIdentityWithoutDeletingItsKey() {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val identity = AndroidKeystoreEcdsaIdentity(
            name = "test",
            privateKey = pair.private,
            publicKey = pair.public as ECPublicKey,
        )
        val repository = SingleIdentityRepository(identity)

        assertEquals(1, repository.identities.size)
        assertFalse(repository.add(byteArrayOf(1)))
        assertFalse(repository.remove(byteArrayOf(1)))
        assertTrue(repository.remove(identity.publicKeyBlob))
        assertTrue(repository.identities.isEmpty())
    }

    @Test
    fun malformedDerSignaturesAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            EcdsaSshEncoding.signatureBlob(byteArrayOf(0x30, 0x01, 0x00))
        }
    }

    private fun derSignature(
        r: ByteArray,
        s: ByteArray,
    ): ByteArray {
        val encodedR = derInteger(r)
        val encodedS = derInteger(s)
        val content = encodedR + encodedS
        require(content.size < 128)
        return byteArrayOf(0x30, content.size.toByte()) + content
    }

    private fun derInteger(value: ByteArray): ByteArray {
        val stripped = value.dropWhile { it == 0.toByte() }.toByteArray()
        val withoutRedundantZeros = if (stripped.isEmpty()) byteArrayOf(0) else stripped
        val positive = if ((withoutRedundantZeros[0].toInt() and 0x80) != 0) {
            byteArrayOf(0) + withoutRedundantZeros
        } else {
            withoutRedundantZeros
        }
        return byteArrayOf(0x02, positive.size.toByte()) + positive
    }

    private class SshReader(private val bytes: ByteArray) {
        private var offset = 0

        fun string(): ByteArray {
            require(offset + 4 <= bytes.size)
            val length = ByteBuffer.wrap(bytes, offset, 4).int
            offset += 4
            require(length >= 0 && offset + length <= bytes.size)
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }

        fun requireExhausted() {
            assertEquals(bytes.size, offset)
        }
    }
}
