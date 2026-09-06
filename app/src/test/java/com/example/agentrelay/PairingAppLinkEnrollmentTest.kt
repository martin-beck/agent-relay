package com.example.agentrelay

import dev.agentrelay.connection.api.PairingAppLink
import dev.agentrelay.connection.api.PairingEnrollmentProfile
import dev.agentrelay.connection.api.StableEndpointIdentity
import dev.agentrelay.storage.android.SecureDocumentStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingAppLinkEnrollmentTest {
    @Test
    fun enrollmentPersistsProfileBeforeConsumingEncryptedGrant() = runTest {
        val documents = InMemoryDocuments()
        val enrollment = AndroidPairingAppLinkEnrollment(documents)
        val identity = StableEndpointIdentity(DAEMON_IDENTITY)
        enrollment.write(
            PairingLinkGrantRecord(
                grantReference = "grant-12345678",
                daemonIdentity = identity.value,
                encodedPublicKey = "AAAA",
                credentialReference = "credential-12345678",
                routeReference = "route-12345678",
                expiresAtMillis = NOW + 60_000,
            ),
        )
        val link = PairingAppLink(
            grantReference = "grant-12345678",
            daemonIdentity = identity,
            nonce = "nonce-12345678",
            expiresAtMillis = NOW + 60_000,
            signature = "AAAA",
        )
        val profile = PairingEnrollmentProfile(
            daemonIdentity = identity,
            grantReference = link.grantReference,
            credentialReference = "credential-12345678",
            routeReference = "route-12345678",
            expiresAtMillis = link.expiresAtMillis,
        )

        assertTrue(enrollment.enroll(VerifiedPairingAppLink(link, profile)))
        assertTrue(documents.values.keys.any { it.startsWith("enrollment-") })
        assertTrue(documents.values["grant-grant-12345678"]!!.decodeToString().contains("\"consumed\":true"))
    }

    private class InMemoryDocuments : SecureDocumentStore {
        val values = linkedMapOf<String, ByteArray>()

        override suspend fun read(documentId: String): ByteArray? = values[documentId]

        override suspend fun write(documentId: String, plaintext: ByteArray) {
            values[documentId] = plaintext
        }

        override suspend fun delete(documentId: String) {
            values.remove(documentId)
        }
    }

    private companion object {
        const val NOW = 1_700_000_000_000L
        const val DAEMON_IDENTITY = "ari_v1_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
    }
}
