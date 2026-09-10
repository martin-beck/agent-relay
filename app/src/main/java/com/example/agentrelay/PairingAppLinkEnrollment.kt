/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay

import android.content.Context
import androidx.core.net.toUri
import dev.agentrelay.connection.api.PairingAppLinkCodec
import dev.agentrelay.connection.api.PairingAppLink
import dev.agentrelay.connection.api.PairingEnrollmentCoordinator
import dev.agentrelay.connection.api.PairingEnrollmentProfile
import dev.agentrelay.storage.android.EncryptedFileDocumentStore
import dev.agentrelay.storage.android.SecureDocumentNamespace
import dev.agentrelay.storage.android.SecureDocumentStore
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import android.content.Intent

internal data class VerifiedPairingAppLink(
    val link: PairingAppLink,
    val profile: PairingEnrollmentProfile,
)

@Serializable
private data class PersistedEnrollmentProfile(
    val daemonIdentity: String,
    val grantReference: String,
    val credentialReference: String,
    val routeReference: String,
    val expiresAtMillis: Long,
)

@Serializable
internal data class PairingLinkGrantRecord(
    val grantReference: String,
    val daemonIdentity: String,
    val encodedPublicKey: String,
    val credentialReference: String,
    val routeReference: String,
    val expiresAtMillis: Long,
    val consumed: Boolean = false,
)

/** Resolves one-time grants from the encrypted store and never exposes grant payloads to logs/UI. */
internal class AndroidPairingAppLinkEnrollment(
    private val documents: SecureDocumentStore,
    private val json: Json = Json { ignoreUnknownKeys = false },
) {
    private val mutex = Mutex()

    constructor(context: Context) : this(
        EncryptedFileDocumentStore(
            context = context.applicationContext,
            namespace = SecureDocumentNamespace(
                directoryName = "pairing-grants",
                associatedDataPrefix = "agent-relay/pairing-grant/v1",
                keyAlias = "agent-relay-pairing-grants-v1",
            ),
        ),
    )

    suspend fun resolveAndVerifyLink(raw: String, nowMillis: Long): VerifiedPairingAppLink? = mutex.withLock {
        val parsed = runCatching { PairingAppLinkCodec.parse(raw, nowMillis) }.getOrNull() ?: return@withLock null
        val record = read(parsed.grantReference) ?: return@withLock null
        if (record.consumed || record.grantReference != parsed.grantReference ||
            record.daemonIdentity != parsed.daemonIdentity.value || record.expiresAtMillis != parsed.expiresAtMillis
        ) {
            return@withLock null
        }
        val key = runCatching { Base64.getUrlDecoder().decode(record.encodedPublicKey) }.getOrNull()
            ?: return@withLock null
        val verifier = PairingAppLinkCodec.ed25519Verifier(key)
        val intentResult = parsePairingAppLinkIntent(
            Intent(Intent.ACTION_VIEW).setData(raw.toUri()),
            nowMillis,
            verifier,
        )
        val verifiedLink = (intentResult as? PairingAppLinkIntentResult.Accepted)?.link
            ?: return@withLock null
        VerifiedPairingAppLink(
            link = verifiedLink,
            profile = PairingEnrollmentProfile(
                daemonIdentity = parsed.daemonIdentity,
                grantReference = parsed.grantReference,
                credentialReference = record.credentialReference,
                routeReference = record.routeReference,
                expiresAtMillis = parsed.expiresAtMillis,
            ),
        )
    }

    suspend fun resolveAndVerify(raw: String, nowMillis: Long): PairingEnrollmentProfile? =
        resolveAndVerifyLink(raw, nowMillis)?.profile

    suspend fun enroll(verified: VerifiedPairingAppLink): Boolean = mutex.withLock {
        val persisted = PersistedEnrollmentProfile(
            daemonIdentity = verified.profile.daemonIdentity.value,
            grantReference = verified.profile.grantReference,
            credentialReference = verified.profile.credentialReference,
            routeReference = verified.profile.routeReference,
            expiresAtMillis = verified.profile.expiresAtMillis,
        )
        documents.write(
            enrollmentDocumentId(verified.profile.grantReference),
            json.encodeToString(PersistedEnrollmentProfile.serializer(), persisted).toByteArray(),
        )
        val coordinator = PairingEnrollmentCoordinator(
            store = object : dev.agentrelay.connection.api.PairingEnrollmentStore {
                override fun begin(profile: PairingEnrollmentProfile) =
                    object : dev.agentrelay.connection.api.PairingEnrollmentTransaction {
                        private var committed = false

                        override fun commit() {
                            committed = true
                        }

                        override fun rollback() {
                            if (!committed) {
                                runBlocking { documents.delete(enrollmentDocumentId(profile.grantReference)) }
                            }
                        }
                    }
            },
            profileFactory = { verified.profile },
            probe = { profile ->
                runBlocking {
                    documents.read(enrollmentDocumentId(profile.grantReference))
                        ?.decodeToString()
                        ?.let { encoded ->
                            runCatching {
                                json.decodeFromString(
                                    PersistedEnrollmentProfile.serializer(),
                                    encoded,
                                )
                            }.getOrNull()
                        }
                        ?.let { stored ->
                            stored.grantReference == profile.grantReference &&
                                stored.daemonIdentity == profile.daemonIdentity.value &&
                                stored.credentialReference == profile.credentialReference &&
                                stored.routeReference == profile.routeReference &&
                                stored.expiresAtMillis == profile.expiresAtMillis
                        } == true
                }
            },
        )
        if (!coordinator.enroll(verified.link)) return@withLock false
        if (consumeLocked(verified.profile.grantReference)) return@withLock true
        documents.delete(enrollmentDocumentId(verified.profile.grantReference))
        false
    }

    suspend fun consume(grantReference: String): Boolean = mutex.withLock {
        consumeLocked(grantReference)
    }

    private suspend fun consumeLocked(grantReference: String): Boolean {
        val record = read(grantReference) ?: return false
        if (record.consumed) return false
        documents.write(
            documentId(grantReference),
            json.encodeToString(PairingLinkGrantRecord.serializer(), record.copy(consumed = true)).toByteArray(),
        )
        return true
    }

    suspend fun write(record: PairingLinkGrantRecord) {
        require(record.grantReference.isNotBlank())
        documents.write(documentId(record.grantReference), json.encodeToString(PairingLinkGrantRecord.serializer(), record).toByteArray())
    }

    private suspend fun read(reference: String): PairingLinkGrantRecord? =
        documents.read(documentId(reference))?.let { bytes ->
            runCatching { json.decodeFromString(PairingLinkGrantRecord.serializer(), bytes.decodeToString()) }.getOrNull()
        }

    private fun documentId(reference: String): String = "grant-$reference"

    private fun enrollmentDocumentId(reference: String): String = "enrollment-$reference"
}
