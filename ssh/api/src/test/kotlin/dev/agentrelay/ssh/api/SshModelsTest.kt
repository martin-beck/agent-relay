package dev.agentrelay.ssh.api

import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SshModelsTest {
    @Test
    fun endpointRejectsAmbiguousOrUnsafeValues() {
        listOf("", "host name", "host/path", "host\\path", "host\nname").forEach { host ->
            assertFailsWith<IllegalArgumentException> { SshEndpoint(host) }
        }
        listOf(0, 65536).forEach { port ->
            assertFailsWith<IllegalArgumentException> { SshEndpoint("host", port) }
        }
        assertEquals("host", SshEndpoint("host").displayName)
        assertEquals("[2001:db8::1]:2200", SshEndpoint("2001:db8::1", 2200).displayName)
    }

    @Test
    fun secretsAreCopiedRedactedAndZeroizedOnClose() {
        val source = byteArrayOf(1, 2, 3)
        val secret = SensitiveBytes.copyOf(source)
        source.fill(9)

        assertEquals(listOf<Byte>(1, 2, 3), secret.copy().toList())
        assertEquals("SensitiveBytes([REDACTED])", secret.toString())

        secret.close()
        assertFailsWith<IllegalStateException> { secret.copy() }
    }

    @Test
    fun authenticationResolverRequiresExactCredentialPurpose() = runTest {
        val store = RecordingCredentialStore()
        val resolver = SshAuthenticationResolver(store)
        val profile = profile(
            SshAuthentication.ImportedKey(
                privateKeyCredentialId = SshCredentialId("private-key"),
                passphraseCredentialId = SshCredentialId("passphrase"),
            ),
        )

        resolver.resolve(profile).use {
            assertTrue(it is ResolvedSshAuthentication.ImportedKey)
        }

        assertEquals(
            listOf(
                SshCredentialId("private-key") to SshCredentialPurpose.PRIVATE_KEY,
                SshCredentialId("passphrase") to SshCredentialPurpose.PRIVATE_KEY_PASSPHRASE,
            ),
            store.requests,
        )
    }

    @Test
    fun hostKeyReplacementIsCompareAndSet() = runTest {
        val store = InMemorySshHostKeyStore()
        val original = hostKey("SHA256:AAAAAAAAAAAAAAAAAAAAAA")
        val replacement = hostKey("SHA256:BBBBBBBBBBBBBBBBBBBBBB")

        assertTrue(store.trustFirstUse(original))
        assertFalse(store.trustFirstUse(replacement))
        assertFalse(store.replace(replacement, setOf("SHA256:staleFingerprintValue")))
        assertEquals(listOf(original), store.trustedKeys(original.endpoint))
        assertTrue(store.replace(replacement, setOf(original.sha256Fingerprint)))
        assertEquals(listOf(replacement), store.trustedKeys(original.endpoint))
    }

    @Test
    fun missingCredentialsNeverExposeTheirIdentifierInTheMessage() = runTest {
        val store = RecordingCredentialStore(returnsSecret = false)
        val error = assertFailsWith<MissingSshCredentialException> {
            SshAuthenticationResolver(store).resolve(
                profile(SshAuthentication.Password(SshCredentialId("sensitive-reference"))),
            )
        }

        assertFalse(error.message.orEmpty().contains("sensitive-reference"))
        assertNull(error.cause)
    }

    private class RecordingCredentialStore(
        private val returnsSecret: Boolean = true,
    ) : SshCredentialStore {
        val requests = mutableListOf<Pair<SshCredentialId, SshCredentialPurpose>>()

        override suspend fun put(
            id: SshCredentialId,
            purpose: SshCredentialPurpose,
            secret: SensitiveBytes,
        ) = Unit

        override suspend fun get(
            id: SshCredentialId,
            purpose: SshCredentialPurpose,
        ): SensitiveBytes? {
            requests += id to purpose
            return if (returnsSecret) SensitiveBytes.copyOf(byteArrayOf(1, 2, 3)) else null
        }

        override suspend fun delete(id: SshCredentialId) = Unit
    }

    companion object {
        fun profile(authentication: SshAuthentication) = SshProfile(
            id = SshProfileId("profile-1"),
            label = "Development",
            endpoint = SshEndpoint("example.test"),
            username = "developer",
            authentication = authentication,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )

        fun hostKey(fingerprint: String) = SshHostKey(
            endpoint = SshEndpoint("example.test"),
            algorithm = "ssh-ed25519",
            publicKeyBase64 = "QUJDREVGR0hJSktMTU5PUA==",
            sha256Fingerprint = fingerprint,
            trustedAtEpochMillis = 1L,
        )
    }
}
