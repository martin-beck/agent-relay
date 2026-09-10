/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.storage.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface DocumentCipher {
    fun encrypt(
        associatedData: ByteArray,
        plaintext: ByteArray,
    ): ByteArray

    fun decrypt(
        associatedData: ByteArray,
        envelope: ByteArray,
    ): ByteArray
}

interface SecureDocumentStore {
    suspend fun read(documentId: String): ByteArray?

    suspend fun write(
        documentId: String,
        plaintext: ByteArray,
    )

    suspend fun delete(documentId: String)
}

data class SecureDocumentNamespace(
    val directoryName: String,
    val associatedDataPrefix: String,
    val keyAlias: String,
) {
    init {
        require(directoryName.matches(Regex("[a-z0-9][a-z0-9._-]{1,63}"))) {
            "Secure document directory must be a bounded relative name"
        }
        require(associatedDataPrefix.isNotBlank() && associatedDataPrefix.length <= 128) {
            "Secure document associated-data prefix must be bounded"
        }
        require(keyAlias.isNotBlank() && keyAlias.length <= 128) {
            "Secure document key alias must be bounded"
        }
    }
}

class EncryptedFileDocumentStore internal constructor(
    private val root: File,
    private val cipher: DocumentCipher,
    private val associatedDataPrefix: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SecureDocumentStore {
    private val monitor = Any()

    constructor(
        context: Context,
        namespace: SecureDocumentNamespace,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        root = File(context.noBackupFilesDir, namespace.directoryName),
        cipher = AndroidKeystoreDocumentCipher(namespace.keyAlias),
        associatedDataPrefix = namespace.associatedDataPrefix,
        dispatcher = dispatcher,
    )

    override suspend fun read(documentId: String): ByteArray? = withContext(dispatcher) {
        synchronized(monitor) {
            val file = fileFor(documentId)
            if (!file.exists()) {
                return@synchronized null
            }
            val length = file.length()
            if (length <= 0L || length > MAX_DOCUMENT_BYTES) {
                throw SecureStoreCorruptException()
            }
            val envelope = file.readBytes()
            try {
                cipher.decrypt(associatedData(documentId), envelope)
            } catch (failure: AEADBadTagException) {
                throw SecureStoreCorruptException(failure)
            } catch (failure: SecureStoreException) {
                throw failure
            } catch (failure: Throwable) {
                throw SecureStoreUnavailableException(failure)
            }
        }
    }

    override suspend fun write(
        documentId: String,
        plaintext: ByteArray,
    ) = withContext(dispatcher) {
        require(plaintext.size <= MAX_DOCUMENT_BYTES) { "Secure document exceeds the size limit" }
        synchronized(monitor) {
            ensureRoot()
            val target = fileFor(documentId)
            val envelope = try {
                cipher.encrypt(associatedData(documentId), plaintext)
            } catch (failure: Throwable) {
                throw SecureStoreUnavailableException(failure)
            }
            val temporary = File.createTempFile(target.name, ".pending", root)
            try {
                restrictToOwner(temporary)
                FileOutputStream(temporary).use {
                    it.write(envelope)
                    it.fd.sync()
                }
                try {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(
                        temporary.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
                restrictToOwner(target)
            } catch (failure: Throwable) {
                temporary.delete()
                throw SecureStoreUnavailableException(failure)
            } finally {
                envelope.fill(0)
            }
        }
    }

    override suspend fun delete(documentId: String) = withContext(dispatcher) {
        synchronized(monitor) {
            val file = fileFor(documentId)
            if (file.exists() && !file.delete()) {
                throw SecureStoreUnavailableException()
            }
        }
    }

    private fun ensureRoot() {
        if (!root.exists() && !root.mkdirs()) {
            throw SecureStoreUnavailableException()
        }
        if (!root.isDirectory) {
            throw SecureStoreUnavailableException()
        }
        restrictToOwner(root)
    }

    private fun fileFor(documentId: String): File {
        require(documentId.isNotBlank() && documentId.length <= 512) {
            "Secure document id must be bounded"
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(documentId.encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(root, "$digest.bin")
    }

    private fun associatedData(documentId: String): ByteArray =
        "$associatedDataPrefix:$documentId".encodeToByteArray()

    private fun restrictToOwner(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
        if (file.isDirectory) {
            file.setExecutable(true, true)
        }
    }

    companion object {
        private const val MAX_DOCUMENT_BYTES = 16L * 1024L * 1024L
    }
}

private class AndroidKeystoreDocumentCipher(
    private val keyAlias: String,
) : DocumentCipher {

    override fun encrypt(
        associatedData: ByteArray,
        plaintext: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associatedData)
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(MAGIC.size + 2 + cipher.iv.size + ciphertext.size)
            .put(MAGIC)
            .put(FORMAT_VERSION)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(ciphertext)
            .array()
    }

    override fun decrypt(
        associatedData: ByteArray,
        envelope: ByteArray,
    ): ByteArray {
        if (envelope.size < MAGIC.size + 2 + MINIMUM_GCM_TAG_BYTES) {
            throw SecureStoreCorruptException()
        }
        val buffer = ByteBuffer.wrap(envelope)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        val version = buffer.get()
        val ivLength = buffer.get().toInt() and 0xff
        if (!magic.contentEquals(MAGIC) || version != FORMAT_VERSION || ivLength !in 12..32) {
            throw SecureStoreCorruptException()
        }
        if (buffer.remaining() < ivLength + MINIMUM_GCM_TAG_BYTES) {
            throw SecureStoreCorruptException()
        }
        val iv = ByteArray(ivLength).also(buffer::get)
        val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(associatedData)
        return cipher.doFinal(ciphertext)
    }

    private fun key(): SecretKey = synchronized(KEY_MONITOR) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey) ?: generateKey()
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private val KEY_MONITOR = Any()
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val MINIMUM_GCM_TAG_BYTES = 16
        private val MAGIC = byteArrayOf(0x41, 0x52, 0x53, 0x53)
        private const val FORMAT_VERSION: Byte = 1
    }
}

sealed class SecureStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

class SecureStoreUnavailableException(cause: Throwable? = null) :
    SecureStoreException("The secure local store is unavailable", cause)

class SecureStoreCorruptException(cause: Throwable? = null) :
    SecureStoreException("The secure local store could not be authenticated", cause)
