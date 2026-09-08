/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.background

import android.content.Context
import dev.agentrelay.connection.api.ConnectionProfileSummary
import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.session.runtime.SessionConnectionKey
import dev.agentrelay.storage.android.EncryptedFileDocumentStore
import dev.agentrelay.storage.android.SecureDocumentNamespace
import dev.agentrelay.storage.android.SecureDocumentStore
import dev.agentrelay.storage.android.SecureStoreCorruptException
import java.util.Arrays
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class BackgroundConnectionLease internal constructor(
    private val documents: SecureDocumentStore,
    private val json: Json = backgroundConnectionLeaseJson(),
) {
    constructor(context: Context) : this(
        EncryptedFileDocumentStore(
            context = context.applicationContext,
            namespace = BACKGROUND_CONNECTION_LEASE_NAMESPACE,
        ),
    )

    private val mutex = Mutex()
    private val desiredConnections = linkedSetOf<SessionConnectionKey>()
    private var enabled = false

    suspend fun recordConnect(key: SessionConnectionKey) = mutex.withLock {
        desiredConnections += key
        persistIfEnabled()
    }

    suspend fun recordDisconnect(key: SessionConnectionKey) = mutex.withLock {
        desiredConnections -= key
        persistIfEnabled()
    }

    suspend fun enable() = mutex.withLock {
        enabled = true
        persist()
    }

    suspend fun restore(): Set<SessionConnectionKey> = mutex.withLock {
        val restored = checkNotNull(load()) {
            "Background connection recovery was not explicitly enabled"
        }
        desiredConnections.clear()
        desiredConnections += restored
        enabled = true
        restored
    }

    suspend fun disable() = mutex.withLock {
        enabled = false
        documents.delete(BACKGROUND_CONNECTION_LEASE_DOCUMENT)
    }

    private suspend fun persistIfEnabled() {
        if (enabled) {
            persist()
        }
    }

    private suspend fun persist() {
        require(desiredConnections.size <= MAX_CONNECTIONS) {
            "Too many background connections"
        }
        val document = BackgroundConnectionLeaseDocument(
            formatVersion = BACKGROUND_CONNECTION_LEASE_FORMAT_VERSION,
            connections = desiredConnections
                .sortedWith(
                    compareBy<SessionConnectionKey> { it.providerId.value }
                        .thenBy { it.profileId.value },
                )
                .map { key ->
                    BackgroundConnectionLeaseEntry(
                        providerId = key.providerId.value,
                        profileId = key.profileId.value,
                    )
                },
        )
        val plaintext = json.encodeToString(document).encodeToByteArray()
        try {
            documents.write(BACKGROUND_CONNECTION_LEASE_DOCUMENT, plaintext)
        } finally {
            Arrays.fill(plaintext, 0)
        }
    }

    private suspend fun load(): Set<SessionConnectionKey>? {
        val plaintext = documents.read(BACKGROUND_CONNECTION_LEASE_DOCUMENT) ?: return null
        return try {
            val document = json.decodeFromString<BackgroundConnectionLeaseDocument>(
                plaintext.decodeToString(),
            )
            check(document.formatVersion == BACKGROUND_CONNECTION_LEASE_FORMAT_VERSION) {
                "Unsupported background connection lease version"
            }
            check(document.connections.size <= MAX_CONNECTIONS) {
                "Too many background connections"
            }
            val restored = document.connections.map { entry ->
                SessionConnectionKey(
                    providerId = ConnectionProviderId(entry.providerId),
                    profileId = ConnectionProfileId(entry.profileId),
                )
            }
            check(restored.distinct().size == restored.size) {
                "Duplicate background connections"
            }
            restored.toCollection(linkedSetOf())
        } catch (failure: SecureStoreCorruptException) {
            throw failure
        } catch (failure: Throwable) {
            throw SecureStoreCorruptException(failure)
        } finally {
            Arrays.fill(plaintext, 0)
        }
    }

    private companion object {
        const val BACKGROUND_CONNECTION_LEASE_DOCUMENT = "background-connection-lease-v1"
        const val BACKGROUND_CONNECTION_LEASE_FORMAT_VERSION = 1
        const val MAX_CONNECTIONS = 64
    }
}

internal fun configuredBackgroundRecoveryConnections(
    desiredConnections: Set<SessionConnectionKey>,
    profiles: List<ConnectionProfileSummary>,
): List<SessionConnectionKey> {
    val availableConnections = profiles.mapTo(mutableSetOf()) { profile ->
        SessionConnectionKey(
            providerId = profile.providerId,
            profileId = profile.id,
        )
    }
    return desiredConnections
        .filter(availableConnections::contains)
        .sortedWith(
            compareBy<SessionConnectionKey> { it.providerId.value }
                .thenBy { it.profileId.value },
        )
}
private val BACKGROUND_CONNECTION_LEASE_NAMESPACE = SecureDocumentNamespace(
    directoryName = "background-connection-secure-store",
    associatedDataPrefix = "agent-relay:background-connection:v1",
    keyAlias = "agent-relay.background-connection.secure-store.v1",
)

private fun backgroundConnectionLeaseJson() = Json {
    encodeDefaults = true
    ignoreUnknownKeys = false
    explicitNulls = true
}

@Serializable
private data class BackgroundConnectionLeaseDocument(
    val formatVersion: Int,
    val connections: List<BackgroundConnectionLeaseEntry>,
)

@Serializable
private data class BackgroundConnectionLeaseEntry(
    val providerId: String,
    val profileId: String,
)
