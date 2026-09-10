/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.ssh.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreDocumentCipherTest {
    @Test
    fun androidKeystoreRoundTripDoesNotPersistPlaintext() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val documentId = "instrumented-" + UUID.randomUUID()
        val keyId = "instrumented-" + UUID.randomUUID()
        val store = encryptedSshDocumentStore(context)
        val plaintext = "device-only-secret".encodeToByteArray()

        val keyManager = AndroidKeystoreAgentKeyManager()
        try {
            store.write(documentId, plaintext)
            assertContentEquals(plaintext, store.read(documentId))
            val key = keyManager.create(keyId)
            assertTrue(key.sha256Fingerprint.startsWith("SHA256:"))
            assertTrue(key.openSshPublicKey.startsWith("ecdsa-sha2-nistp256 "))
            assertContentEquals(
                key.openSshPublicKey.encodeToByteArray(),
                AndroidKeystoreAgentKeyManager().publicKey(keyId)?.openSshPublicKey?.encodeToByteArray(),
            )
            assertTrue(AndroidKeystoreAgentIdentityProvider(keyManager).identitiesFor(keyId) != null)
            val persisted = context.noBackupFilesDir
                .resolve("ssh-secure-store")
                .listFiles()
                .orEmpty()
                .flatMap { it.readBytes().asIterable() }
                .toByteArray()
            assertFalse(persisted.decodeToString().contains("device-only-secret"))
        } finally {
            store.delete(documentId)
            keyManager.delete(keyId)
        }
    }
}
