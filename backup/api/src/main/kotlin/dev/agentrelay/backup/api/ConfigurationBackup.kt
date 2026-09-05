package dev.agentrelay.backup.api

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val CURRENT_FORMAT_VERSION = 1
private const val CURRENT_READER_VERSION = 1
private const val MIN_READER_VERSION = 1
private const val CURRENT_SCHEMA_VERSION = 1
private const val MAX_CATEGORIES = 6
private const val MAX_RECORDS_PER_CATEGORY = 128
private const val MAX_VALUE_CHARS = 4096
private const val MAX_ARCHIVE_BYTES = 1024 * 1024
private const val MIN_PASSPHRASE_CHARS = 12
private const val SALT_BYTES = 16
private const val NONCE_BYTES = 12
private const val KEY_BITS = 256
private const val TAG_BITS = 128
private const val DEFAULT_KDF_ITERATIONS = 120_000
private const val MIN_KDF_ITERATIONS = 100_000
private const val MAX_KDF_ITERATIONS = 500_000
private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private val KEY_PATTERN = Regex("[a-z][a-z0-9_.-]{0,63}")
private val VERSION_PATTERN = Regex("[0-9]+(?:\\.[0-9]+){0,2}")
private val DIGEST_PATTERN = Regex("[0-9a-f]{64}")
private val SENSITIVE_PATTERN = Regex(
    "(?i)(password|token|secret|private[ _-]?key|prompt|transcript|" +
        "daemon[ _-]?credential|hostname|account[ _-]?id|/home/|ssh[-_])",
)

private fun containsSensitiveMaterial(value: String): Boolean = SENSITIVE_PATTERN.containsMatchIn(value)
private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

/** Categories which may be deliberately selected for a portable configuration backup. */
@Serializable
enum class BackupCategory {
    CONNECTION_PROFILE_METADATA,
    NON_SECRET_PREFERENCES,
    WORKFLOW_SETTINGS,
    NOTIFICATION_SETTINGS,
    DRAFTS,
    REDACTED_SESSION_METADATA,
}

@Serializable
data class PortableRecord(val key: String, val value: String) {
    init {
        require(key.matches(KEY_PATTERN)) { "Portable record key is invalid" }
        require(value.length <= MAX_VALUE_CHARS) { "Portable record value is too large" }
        require(!containsSensitiveMaterial(key) && !containsSensitiveMaterial(value)) {
            "Sensitive material is not portable"
        }
    }
}

@Serializable
data class PortableCategory(
    val category: BackupCategory,
    val schemaVersion: Int,
    val records: List<PortableRecord>,
) {
    init {
        require(schemaVersion in 1..CURRENT_SCHEMA_VERSION) { "Unsupported category schema" }
        require(records.size <= MAX_RECORDS_PER_CATEGORY) { "Too many records" }
        require(records.map { it.key }.distinct().size == records.size) { "Duplicate record key" }
    }
}

@Serializable
data class ConfigurationSnapshot(val categories: List<PortableCategory>) {
    init {
        require(categories.size <= MAX_CATEGORIES) { "Too many backup categories" }
        require(categories.map { it.category }.distinct().size == categories.size) {
            "Duplicate backup category"
        }
    }

    fun canonical(): ConfigurationSnapshot = copy(
        categories = categories.sortedBy { it.category.name }.map { category ->
            category.copy(records = category.records.sortedBy { it.key })
        },
    )
}

@Serializable
data class BackupMetadata(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val sourceAppVersion: String,
    val minimumReaderVersion: Int,
    val maximumReaderVersion: Int,
    val createdAtMillis: Long,
    val sourceDeviceIdentityDigest: String,
    val toolVersion: String,
) {
    init {
        require(formatVersion == CURRENT_FORMAT_VERSION) { "Unsupported archive format" }
        require(sourceAppVersion.matches(VERSION_PATTERN)) { "Source app version is invalid" }
        require(minimumReaderVersion in 1..maximumReaderVersion) { "Reader window is invalid" }
        require(createdAtMillis >= 0) { "Creation time is invalid" }
        require(sourceDeviceIdentityDigest.matches(DIGEST_PATTERN)) { "Device digest is invalid" }
        require(toolVersion.matches(VERSION_PATTERN)) { "Tool version is invalid" }
    }
}

@Serializable
data class EncryptedConfigurationArchive(
    val metadata: BackupMetadata,
    val categories: List<BackupCategory>,
    val kdf: KdfParameters,
    val nonce: String,
    val ciphertext: String,
    val ciphertextSha256: String,
)

@Serializable
data class KdfParameters(val algorithm: String, val iterations: Int, val salt: String) {
    init {
        require(algorithm == KDF_ALGORITHM) { "Unsupported key derivation algorithm" }
        require(iterations in MIN_KDF_ITERATIONS..MAX_KDF_ITERATIONS) { "KDF work factor is invalid" }
        require(decode(salt).size == SALT_BYTES) { "KDF salt is invalid" }
    }
}

data class BackupDependencies(
    val nowMillis: () -> Long = { System.currentTimeMillis() },
    val randomBytes: (Int) -> ByteArray = { size -> ByteArray(size).also(SecureRandom()::nextBytes) },
)

class ConfigurationBackupCodec(
    private val dependencies: BackupDependencies = BackupDependencies(),
    private val readerVersion: Int = CURRENT_READER_VERSION,
) {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    fun create(
        snapshot: ConfigurationSnapshot,
        passphrase: CharArray,
        sourceAppVersion: String,
        sourceDeviceIdentityDigest: String,
        toolVersion: String,
    ): ByteArray {
        require(passphrase.size >= MIN_PASSPHRASE_CHARS) { "Passphrase is too short" }
        val canonical = snapshot.canonical()
        val metadata = BackupMetadata(
            sourceAppVersion = sourceAppVersion,
            minimumReaderVersion = MIN_READER_VERSION,
            maximumReaderVersion = CURRENT_READER_VERSION,
            createdAtMillis = dependencies.nowMillis().also { require(it >= 0) },
            sourceDeviceIdentityDigest = sourceDeviceIdentityDigest,
            toolVersion = toolVersion,
        )
        val salt = dependencies.randomBytes(SALT_BYTES)
        val nonce = dependencies.randomBytes(NONCE_BYTES)
        val kdf = KdfParameters(KDF_ALGORITHM, DEFAULT_KDF_ITERATIONS, encode(salt))
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, kdf), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(metadataBytes(metadata, canonical.categories.map { it.category }))
        val ciphertext = cipher.doFinal(json.encodeToString(ConfigurationSnapshot.serializer(), canonical).encodeToByteArray())
        val archive = EncryptedConfigurationArchive(
            metadata,
            canonical.categories.map { it.category },
            kdf,
            encode(nonce),
            encode(ciphertext),
            digest(ciphertext),
        )
        return json.encodeToString(EncryptedConfigurationArchive.serializer(), archive).encodeToByteArray()
    }

    fun preview(archiveBytes: ByteArray, passphrase: CharArray): ConfigurationSnapshot {
        require(archiveBytes.size <= MAX_ARCHIVE_BYTES) { "Archive is too large" }
        val archive = runCatching {
            json.decodeFromString(EncryptedConfigurationArchive.serializer(), archiveBytes.decodeToString())
        }.getOrElse { throw IllegalArgumentException("Archive is malformed", it) }
        require(archive.metadata.minimumReaderVersion <= readerVersion && readerVersion <= archive.metadata.maximumReaderVersion) {
            "Archive is outside the reader compatibility window"
        }
        val ciphertext = decode(archive.ciphertext)
        require(digest(ciphertext) == archive.ciphertextSha256) { "Archive ciphertext checksum failed" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, archive.kdf), GCMParameterSpec(TAG_BITS, decode(archive.nonce)))
            cipher.updateAAD(metadataBytes(archive.metadata, archive.categories))
            return json.decodeFromString(ConfigurationSnapshot.serializer(), cipher.doFinal(ciphertext).decodeToString()).canonical()
        } catch (failure: Exception) {
            throw IllegalArgumentException("Archive authentication failed", failure)
        }
    }

    private fun deriveKey(passphrase: CharArray, kdf: KdfParameters): SecretKeySpec =
        SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(
            PBEKeySpec(passphrase, decode(kdf.salt), kdf.iterations, KEY_BITS),
        ).encoded.let { SecretKeySpec(it, "AES") }

    private fun metadataBytes(metadata: BackupMetadata, categories: List<BackupCategory>): ByteArray =
        listOf(
            metadata.formatVersion,
            metadata.sourceAppVersion,
            metadata.minimumReaderVersion,
            metadata.maximumReaderVersion,
            metadata.createdAtMillis,
            metadata.sourceDeviceIdentityDigest,
            metadata.toolVersion,
            categories.sorted().joinToString(",") { it.name },
        ).joinToString("|").encodeToByteArray()

    companion object {
        private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
        private fun digest(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString("") { "%02x".format(it) }
    }
}
