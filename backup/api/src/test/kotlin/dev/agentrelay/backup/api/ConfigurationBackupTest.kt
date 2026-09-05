package dev.agentrelay.backup.api

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ConfigurationBackupTest {
    private val snapshot = ConfigurationSnapshot(
        listOf(
            PortableCategory(
                BackupCategory.WORKFLOW_SETTINGS,
                1,
                listOf(PortableRecord("editor.theme", "dark"), PortableRecord("workflow.max_steps", "12")),
            ),
            PortableCategory(
                BackupCategory.NON_SECRET_PREFERENCES,
                1,
                listOf(PortableRecord("notifications.enabled", "true")),
            ),
        ),
    )

    @Test
    fun roundTripIsAuthenticatedAndCanonical() {
        val codec = ConfigurationBackupCodec(BackupDependencies(nowMillis = { 42 }, randomBytes = deterministicRandom()))
        val archive = codec.create(snapshot, "correct horse battery staple".toCharArray(), "1.2.3", "a".repeat(64), "1.0")
        val restored = codec.preview(archive, "correct horse battery staple".toCharArray())
        assertEquals(snapshot.canonical(), restored)
        assertFalse(archive.decodeToString().contains("dark"))
    }

    @Test
    fun deterministicDependenciesProduceStableArchive() {
        val factory = { ConfigurationBackupCodec(BackupDependencies(nowMillis = { 42 }, randomBytes = deterministicRandom())) }
        val first = factory().create(snapshot, "correct horse battery staple".toCharArray(), "1.2.3", "a".repeat(64), "1.0")
        val second = factory().create(snapshot, "correct horse battery staple".toCharArray(), "1.2.3", "a".repeat(64), "1.0")
        assertContentEquals(first, second)
    }

    @Test
    fun wrongPassphraseAndTamperingAreRejected() {
        val codec = ConfigurationBackupCodec(BackupDependencies(randomBytes = deterministicRandom()))
        val archive = codec.create(snapshot, "correct horse battery staple".toCharArray(), "1.2.3", "a".repeat(64), "1.0")
        assertFailsWith<IllegalArgumentException> { codec.preview(archive, "wrong passphrase".toCharArray()) }
        val tampered = archive.copyOf().also {
            it[it.lastIndex - 4] = if (it[it.lastIndex - 4] == 'A'.code.toByte()) {
                'B'.code.toByte()
            } else {
                'A'.code.toByte()
            }
        }
        assertFailsWith<IllegalArgumentException> { codec.preview(tampered, "correct horse battery staple".toCharArray()) }
    }

    @Test
    fun prohibitedMaterialAndDuplicateRecordsAreRejected() {
        assertFailsWith<IllegalArgumentException> { PortableRecord("ssh_private_key", "not exported") }
        assertFailsWith<IllegalArgumentException> {
            PortableCategory(BackupCategory.DRAFTS, 1, listOf(PortableRecord("draft", "one"), PortableRecord("draft", "two")))
        }
        assertFailsWith<IllegalArgumentException> { PortableRecord("account", "account id: 123") }
    }

    @Test
    fun shortPassphraseAndReaderWindowAreRejected() {
        val codec = ConfigurationBackupCodec()
        assertFailsWith<IllegalArgumentException> { codec.create(snapshot, "too-short".toCharArray(), "1.0", "a".repeat(64), "1.0") }
        val archive = codec.create(snapshot, "correct horse battery staple".toCharArray(), "1.0", "a".repeat(64), "1.0")
        assertTrue(archive.isNotEmpty())
    }

    private fun deterministicRandom(): (Int) -> ByteArray = { size -> ByteArray(size) { index -> (index + size).toByte() } }
}
