/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.storage.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreDocumentStoreTest {
    @Test
    fun androidKeystoreRoundTripDoesNotPersistPlaintext() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val suffix = UUID.randomUUID().toString()
        val namespace = SecureDocumentNamespace(
            directoryName = "instrumented-secure-store",
            associatedDataPrefix = "agent-relay:instrumented-store:v1",
            keyAlias = "agent-relay.instrumented.secure-store.$suffix",
        )
        val documentId = "instrumented-$suffix"
        val store = EncryptedFileDocumentStore(context, namespace)
        val plaintext = "device-only-secret".encodeToByteArray()

        try {
            store.write(documentId, plaintext)

            assertContentEquals(plaintext, store.read(documentId))
            val persisted = context.noBackupFilesDir
                .resolve(namespace.directoryName)
                .listFiles()
                .orEmpty()
                .flatMap { it.readBytes().asIterable() }
                .toByteArray()
            assertFalse(persisted.decodeToString().contains("device-only-secret"))
        } finally {
            store.delete(documentId)
        }
    }

    @Test
    fun namespacesRejectDirectoryTraversal() {
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            SecureDocumentNamespace("../outside", "agent-relay:test:v1", "agent-relay.test")
        }
    }
}
