package dev.agentrelay.ssh.jsch

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import dev.agentrelay.ssh.api.SshEndpoint
import dev.agentrelay.ssh.api.SshHostKeyDisposition
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StrictHostKeyRepositoryTest {
    private val endpoint = SshEndpoint("example.test", 2200)

    @Test
    fun unknownKeyIsCapturedButNeverAdded() {
        val phases = mutableListOf<Boolean>()
        val repository = StrictHostKeyRepository(
            endpoint = endpoint,
            trustedKeys = emptyList(),
            nowEpochMillis = { 123L },
            onCheck = phases::add,
        )
        val key = ed25519Key(1)

        assertEquals(HostKeyRepository.NOT_INCLUDED, repository.check("ignored", key))
        val challenge = requireNotNull(repository.observedChallenge())
        assertEquals(SshHostKeyDisposition.UNKNOWN, challenge.disposition)
        assertEquals(endpoint, challenge.candidate.endpoint)
        assertEquals("ssh-ed25519", challenge.candidate.algorithm)
        assertEquals(Base64.getEncoder().encodeToString(key), challenge.candidate.publicKeyBase64)
        assertEquals(fingerprint(key), challenge.candidate.sha256Fingerprint)
        assertEquals(123L, challenge.candidate.trustedAtEpochMillis)
        assertEquals(listOf(false), phases)
        assertTrue(repository.hostKey.isEmpty())
    }

    @Test
    fun exactKeyIsAcceptedAndDifferentKeyIsReportedAsChanged() {
        val firstRepository = StrictHostKeyRepository(endpoint, emptyList(), { 1L })
        val trustedBytes = ed25519Key(2)
        firstRepository.check("ignored", trustedBytes)
        val trusted = requireNotNull(firstRepository.observedChallenge()).candidate

        val repository = StrictHostKeyRepository(endpoint, listOf(trusted), { 2L })
        assertEquals(HostKeyRepository.OK, repository.check("ignored", trustedBytes))
        assertNull(repository.observedChallenge())
        assertEquals(1, repository.hostKey.size)

        assertEquals(HostKeyRepository.CHANGED, repository.check("ignored", ed25519Key(3)))
        val changed = requireNotNull(repository.observedChallenge())
        assertEquals(SshHostKeyDisposition.CHANGED, changed.disposition)
        assertEquals(listOf(trusted.sha256Fingerprint), changed.trustedFingerprints)
    }

    @Test
    fun repositoryMutationMethodsAlwaysFailClosed() {
        val repository = StrictHostKeyRepository(endpoint, emptyList(), { 1L })
        val hostKey = HostKey(endpoint.displayName, ed25519Key(4))

        assertFailsWith<UnsupportedOperationException> {
            repository.add(hostKey, null)
        }
        assertFailsWith<UnsupportedOperationException> {
            repository.remove(endpoint.displayName, "ssh-ed25519")
        }
        assertFailsWith<UnsupportedOperationException> {
            repository.remove(endpoint.displayName, "ssh-ed25519", ed25519Key(4))
        }
    }

    private fun ed25519Key(seed: Int): ByteArray {
        val algorithm = "ssh-ed25519".encodeToByteArray()
        val point = ByteArray(32) { (it + seed).toByte() }
        return sshString(algorithm) + sshString(point)
    }

    private fun sshString(value: ByteArray): ByteArray =
        ByteBuffer.allocate(Int.SIZE_BYTES + value.size)
            .putInt(value.size)
            .put(value)
            .array()

    private fun fingerprint(key: ByteArray): String = "SHA256:" +
        Base64.getEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(key),
        )
}
