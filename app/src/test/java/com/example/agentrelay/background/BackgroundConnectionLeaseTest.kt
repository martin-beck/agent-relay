package com.example.agentrelay.background

import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.connection.api.ConnectionProfileSummary
import dev.agentrelay.session.runtime.SessionConnectionKey
import dev.agentrelay.storage.android.SecureDocumentStore
import dev.agentrelay.storage.android.SecureStoreCorruptException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundConnectionLeaseTest {
    @Test
    fun connectionIntentIsEncryptedStoreReadyOnlyWhileExplicitlyEnabled() = runTest {
        val documents = InMemorySecureDocuments()
        val first = BackgroundConnectionLease(documents)
        val ssh = connection("ssh", "primary")
        val local = connection("local", "device")

        first.recordConnect(ssh)
        first.recordConnect(local)
        assertNull(documents.singleDocument())

        first.enable()
        val persisted = requireNotNull(documents.singleDocument())
        assertTrue(persisted.isNotEmpty())

        val restored = BackgroundConnectionLease(documents)
        assertEquals(setOf(local, ssh), restored.restore())

        restored.recordDisconnect(ssh)
        assertEquals(
            setOf(local),
            BackgroundConnectionLease(documents).restore(),
        )

        restored.disable()
        assertNull(documents.singleDocument())
        val missingFailure = runCatching {
            BackgroundConnectionLease(documents).restore()
        }.exceptionOrNull()
        assertEquals(IllegalStateException::class.java, missingFailure?.javaClass)
    }

    @Test
    fun malformedLeaseFailsClosedWithoutReturningPartialConnections() = runTest {
        val documents = InMemorySecureDocuments().apply {
            write("background-connection-lease-v1", "not-json".encodeToByteArray())
        }

        val corruptFailure = runCatching {
            BackgroundConnectionLease(documents).restore()
        }.exceptionOrNull()
        assertEquals(SecureStoreCorruptException::class.java, corruptFailure?.javaClass)

        documents.write(
            "background-connection-lease-v1",
            (
                """{"formatVersion":1,"connections":[""" +
                    """{"providerId":"ssh","profileId":"same"},""" +
                    """{"providerId":"ssh","profileId":"same"}]}"""
                ).encodeToByteArray(),
        )
        val duplicateFailure = runCatching {
            BackgroundConnectionLease(documents).restore()
        }.exceptionOrNull()
        assertEquals(SecureStoreCorruptException::class.java, duplicateFailure?.javaClass)
    }

    @Test
    fun unsupportedInvalidOrOversizedLeaseFailsClosed() = runTest {
        val excessiveConnections = (0..64).joinToString(
            prefix = """{"formatVersion":1,"connections":[""",
            postfix = "]}",
        ) { index ->
            """{"providerId":"ssh","profileId":"profile-$index"}"""
        }
        val payloads = listOf(
            """{"formatVersion":2,"connections":[]}""",
            (
                """{"formatVersion":1,"connections":[""" +
                    """{"providerId":"SSH","profileId":"primary"}]}"""
                ),
            excessiveConnections,
        )

        payloads.forEachIndexed { index, payload ->
            val documents = InMemorySecureDocuments().apply {
                write(
                    "background-connection-lease-v1",
                    payload.encodeToByteArray(),
                )
            }
            val failure = runCatching {
                BackgroundConnectionLease(documents).restore()
            }.exceptionOrNull()
            assertEquals(
                "case $index",
                SecureStoreCorruptException::class.java,
                failure?.javaClass,
            )
        }
    }

    @Test
    fun recoverySelectsOnlyConfiguredProviderScopedConnectionsInStableOrder() {
        val local = connection("local", "shared")
        val ssh = connection("ssh", "shared")
        val removed = connection("ssh", "removed")
        val profiles = listOf(
            profile("ssh", "shared"),
            profile("local", "shared"),
        )

        assertEquals(
            listOf(local, ssh),
            configuredBackgroundRecoveryConnections(setOf(removed, ssh, local), profiles),
        )
    }

    private fun profile(providerId: String, profileId: String) = ConnectionProfileSummary(
        id = ConnectionProfileId(profileId),
        providerId = ConnectionProviderId(providerId),
        label = "Configured connection",
        target = "Redacted target",
        authenticationLabel = null,
    )

    private fun connection(providerId: String, profileId: String) = SessionConnectionKey(
        providerId = ConnectionProviderId(providerId),
        profileId = ConnectionProfileId(profileId),
    )
}

private class InMemorySecureDocuments : SecureDocumentStore {
    private val documents = mutableMapOf<String, ByteArray>()

    override suspend fun read(documentId: String): ByteArray? = documents[documentId]?.copyOf()

    override suspend fun write(documentId: String, plaintext: ByteArray) {
        documents[documentId] = plaintext.copyOf()
    }

    override suspend fun delete(documentId: String) {
        documents.remove(documentId)
    }

    fun singleDocument(): ByteArray? = documents.values.singleOrNull()?.copyOf()
}
