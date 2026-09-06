package com.example.agentrelay

import android.content.Context
import dev.agentrelay.connection.api.PairingAppLinkCodec
import dev.agentrelay.connection.api.PairingEnrollmentProfile
import dev.agentrelay.storage.android.EncryptedFileDocumentStore
import dev.agentrelay.storage.android.SecureDocumentNamespace
import dev.agentrelay.storage.android.SecureDocumentStore
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    suspend fun resolveAndVerify(raw: String, nowMillis: Long): PairingEnrollmentProfile? = mutex.withLock {
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
        if (!verifier.verify(parsed.signingPayload(), parsed.signature)) return@withLock null
        PairingEnrollmentProfile(
            daemonIdentity = parsed.daemonIdentity,
            grantReference = parsed.grantReference,
            credentialReference = record.credentialReference,
            routeReference = record.routeReference,
            expiresAtMillis = parsed.expiresAtMillis,
        )
    }

    suspend fun consume(grantReference: String): Boolean = mutex.withLock {
        val record = read(grantReference) ?: return@withLock false
        if (record.consumed) return@withLock false
        documents.write(documentId(grantReference), json.encodeToString(PairingLinkGrantRecord.serializer(), record.copy(consumed = true)).toByteArray())
        true
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
}
