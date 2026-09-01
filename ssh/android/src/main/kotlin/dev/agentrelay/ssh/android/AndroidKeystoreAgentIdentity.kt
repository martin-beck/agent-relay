package dev.agentrelay.ssh.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.jcraft.jsch.Identity
import com.jcraft.jsch.IdentityRepository
import dev.agentrelay.ssh.jsch.JschAgentIdentityProvider
import java.math.BigInteger
import dev.agentrelay.storage.android.SecureStoreCorruptException
import dev.agentrelay.storage.android.SecureStoreUnavailableException
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Vector

data class AndroidAgentPublicKey(
    val keyId: String,
    val algorithm: String,
    val sha256Fingerprint: String,
    val openSshPublicKey: String,
)

class AndroidKeystoreAgentKeyManager(
    private val keyStoreProvider: String = ANDROID_KEYSTORE,
) {
    fun create(
        keyId: String,
        requireUserAuthentication: Boolean = false,
    ): AndroidAgentPublicKey {
        validateKeyId(keyId)
        val alias = aliasFor(keyId)
        val keyStore = keyStore()
        if (!keyStore.containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                keyStoreProvider,
            )
            generator.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE_JCA_NAME))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(requireUserAuthentication)
                    .build(),
            )
            generator.generateKeyPair()
        }
        return publicKey(keyId)
            ?: throw SecureStoreUnavailableException()
    }

    fun publicKey(keyId: String): AndroidAgentPublicKey? {
        validateKeyId(keyId)
        val certificate = keyStore().getCertificate(aliasFor(keyId)) ?: return null
        val publicKey = certificate.publicKey as? ECPublicKey
            ?: throw SecureStoreCorruptException()
        val blob = EcdsaSshEncoding.publicKeyBlob(publicKey)
        val fingerprint = Base64.getEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(blob),
        )
        return AndroidAgentPublicKey(
            keyId = keyId,
            algorithm = EcdsaSshEncoding.ALGORITHM,
            sha256Fingerprint = "SHA256:$fingerprint",
            openSshPublicKey =
            EcdsaSshEncoding.ALGORITHM + " " + Base64.getEncoder().encodeToString(blob),
        )
    }

    fun delete(keyId: String): Boolean {
        validateKeyId(keyId)
        val keyStore = keyStore()
        val alias = aliasFor(keyId)
        if (!keyStore.containsAlias(alias)) {
            return false
        }
        keyStore.deleteEntry(alias)
        return true
    }

    internal fun identity(keyId: String): AndroidKeystoreEcdsaIdentity? {
        validateKeyId(keyId)
        val keyStore = keyStore()
        val alias = aliasFor(keyId)
        val privateKey = keyStore.getKey(alias, null) as? PrivateKey ?: return null
        val publicKey = keyStore.getCertificate(alias)?.publicKey as? ECPublicKey
            ?: throw SecureStoreCorruptException()
        return AndroidKeystoreEcdsaIdentity(
            name = "Android Keystore key",
            privateKey = privateKey,
            publicKey = publicKey,
        )
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(keyStoreProvider).apply { load(null) }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CURVE_JCA_NAME = "secp256r1"
        private const val ALIAS_PREFIX = "agent-relay.ssh.agent.v1."

        internal fun aliasFor(keyId: String): String {
            validateKeyId(keyId)
            val digest = MessageDigest.getInstance("SHA-256").digest(keyId.encodeToByteArray())
            return ALIAS_PREFIX + digest.joinToString("") { "%02x".format(it) }
        }

        internal fun validateKeyId(keyId: String) {
            require(keyId.isNotBlank() && keyId.length <= 256 && keyId.none(Char::isISOControl)) {
                "Agent key id must be a bounded printable identifier"
            }
        }
    }
}

class AndroidKeystoreAgentIdentityProvider(
    private val keyManager: AndroidKeystoreAgentKeyManager = AndroidKeystoreAgentKeyManager(),
) : JschAgentIdentityProvider {
    override fun identitiesFor(keyId: String): IdentityRepository? =
        keyManager.identity(keyId)?.let(::SingleIdentityRepository)
}

internal class SingleIdentityRepository(private var identity: Identity?) : IdentityRepository {
    override fun getName(): String = "Android Keystore"

    override fun getStatus(): Int = if (identity == null) {
        IdentityRepository.UNAVAILABLE
    } else {
        IdentityRepository.RUNNING
    }

    override fun getIdentities(): Vector<Identity> = Vector<Identity>().apply {
        identity?.let(::add)
    }

    override fun add(identity: ByteArray): Boolean = false

    override fun remove(blob: ByteArray): Boolean {
        val current = identity ?: return false
        if (!current.publicKeyBlob.contentEquals(blob)) {
            return false
        }
        identity = null
        return true
    }

    override fun removeAll() {
        identity = null
    }
}

internal class AndroidKeystoreEcdsaIdentity(
    private val name: String,
    private val privateKey: PrivateKey,
    private val publicKey: ECPublicKey,
) : Identity {
    private val publicKeyBlob = EcdsaSshEncoding.publicKeyBlob(publicKey)

    override fun setPassphrase(passphrase: ByteArray?): Boolean = true

    override fun getPublicKeyBlob(): ByteArray = publicKeyBlob.copyOf()

    override fun getSignature(data: ByteArray): ByteArray? = sign(data)

    override fun getSignature(
        data: ByteArray,
        algorithm: String,
    ): ByteArray? = if (algorithm == EcdsaSshEncoding.ALGORITHM) sign(data) else null

    @Suppress("OVERRIDE_DEPRECATION")
    override fun decrypt(): Boolean = true

    override fun getAlgName(): String = EcdsaSshEncoding.ALGORITHM

    override fun getName(): String = name

    override fun isEncrypted(): Boolean = false

    override fun clear() = Unit

    private fun sign(data: ByteArray): ByteArray? = try {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(data)
        EcdsaSshEncoding.signatureBlob(signer.sign())
    } catch (_: java.security.GeneralSecurityException) {
        null
    }
}

internal object EcdsaSshEncoding {
    const val ALGORITHM = "ecdsa-sha2-nistp256"
    private const val CURVE_SSH_NAME = "nistp256"
    private const val COORDINATE_BYTES = 32

    fun publicKeyBlob(publicKey: ECPublicKey): ByteArray {
        require(publicKey.params.curve.field.fieldSize == 256) {
            "Only NIST P-256 Android Keystore keys are supported"
        }
        val point = byteArrayOf(0x04) +
            fixedUnsigned(publicKey.w.affineX, COORDINATE_BYTES) +
            fixedUnsigned(publicKey.w.affineY, COORDINATE_BYTES)
        return sshString(ALGORITHM.encodeToByteArray()) +
            sshString(CURVE_SSH_NAME.encodeToByteArray()) +
            sshString(point)
    }

    fun signatureBlob(derSignature: ByteArray): ByteArray {
        val reader = DerReader(derSignature)
        val sequence = reader.readElement(0x30)
        reader.requireExhausted()
        val integers = DerReader(sequence)
        val r = integers.readElement(0x02).toPositiveMpInt()
        val s = integers.readElement(0x02).toPositiveMpInt()
        integers.requireExhausted()
        val inner = sshString(r) + sshString(s)
        return sshString(ALGORITHM.encodeToByteArray()) + sshString(inner)
    }

    private fun fixedUnsigned(
        value: BigInteger,
        size: Int,
    ): ByteArray {
        require(value.signum() >= 0) { "EC coordinates must be positive" }
        val encoded = value.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        require(encoded.size <= size) { "EC coordinate is too large" }
        return ByteArray(size - encoded.size) + encoded
    }

    private fun ByteArray.toPositiveMpInt(): ByteArray {
        require(isNotEmpty()) { "ECDSA integer must not be empty" }
        val stripped = dropWhile { it == 0.toByte() }.toByteArray()
        val normalized = if (stripped.isEmpty()) byteArrayOf(0) else stripped
        return if ((normalized[0].toInt() and 0x80) != 0) byteArrayOf(0) + normalized else normalized
    }

    private fun sshString(value: ByteArray): ByteArray =
        ByteBuffer.allocate(Int.SIZE_BYTES + value.size)
            .putInt(value.size)
            .put(value)
            .array()

    private class DerReader(private val bytes: ByteArray) {
        private var offset = 0

        fun readElement(expectedTag: Int): ByteArray {
            require(readByte() == expectedTag) { "Unexpected DER tag" }
            val length = readLength()
            require(length >= 0 && offset + length <= bytes.size) { "Invalid DER length" }
            return bytes.copyOfRange(offset, offset + length).also { offset += length }
        }

        fun requireExhausted() {
            require(offset == bytes.size) { "Trailing DER data" }
        }

        private fun readLength(): Int {
            val first = readByte()
            if ((first and 0x80) == 0) {
                return first
            }
            val count = first and 0x7f
            require(count in 1..2 && offset + count <= bytes.size) { "Unsupported DER length" }
            var result = 0
            repeat(count) {
                result = (result shl 8) or readByte()
            }
            return result
        }

        private fun readByte(): Int {
            require(offset < bytes.size) { "Unexpected end of DER data" }
            return bytes[offset++].toInt() and 0xff
        }
    }
}
