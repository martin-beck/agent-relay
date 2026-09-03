package dev.agentrelay.ssh.api

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class SshAuthorizedKeysInstallScriptTest {
    @Test
    fun restrictedMatchingKeyIsPreservedByteForByteWithoutAnUnrestrictedDuplicate() =
        withRemoteHome(
            """restrict,from="192.0.2.0/24" $MANAGED_KEY retained restriction
            """.trimIndent(),
        ) { home, authorizedKeys, original ->
            val result = execute(home)

            assertEquals(0, result.exitCode, result.stderr)
            assertContentEquals(original, authorizedKeys.readBytes())
            assertFalse(authorizedKeys.readText().lineSequence().any { it == MANAGED_KEY })
        }

    @Test
    fun mixedCasePositiveAndRestrictiveOptionsPreserveTheMatchingKey() =
        withRemoteHome(
            """Agent-Forwarding,PTY,Restrict,From="192.0.2.0/24" $MANAGED_KEY retained
            """.trimIndent(),
        ) { home, authorizedKeys, original ->
            val result = execute(home)

            assertEquals(0, result.exitCode, result.stderr)
            assertContentEquals(original, authorizedKeys.readBytes())
        }

    @Test
    fun certificateAuthorityEntryDoesNotCountAsPlainKeyAuthorization() =
        withRemoteHome("Cert-Authority $MANAGED_KEY trusted CA\n") { home, authorizedKeys, original ->
            val result = execute(home)
            val updated = authorizedKeys.readText()

            assertEquals(0, result.exitCode, result.stderr)
            assertTrue(updated.startsWith(original.decodeToString()))
            assertEquals(1, updated.lineSequence().count { it == MANAGED_KEY })
        }

    @Test
    fun matchingKeyWithATrailingCommentIsPreservedByteForByte() =
        withRemoteHome("$MANAGED_KEY existing device comment\n") { home, authorizedKeys, original ->
            val result = execute(home)

            assertEquals(0, result.exitCode, result.stderr)
            assertContentEquals(original, authorizedKeys.readBytes())
        }

    @Test
    fun restrictedCrLfKeyWithoutCommentIsPreservedByteForByte() =
        withRemoteHome(
            "restrict,from=\"192.0.2.0/24\" $MANAGED_KEY\r\n",
        ) { home, authorizedKeys, original ->
            val result = execute(home)

            assertEquals(0, result.exitCode, result.stderr)
            assertContentEquals(original, authorizedKeys.readBytes())
            assertFalse(authorizedKeys.readText().lineSequence().any { it == MANAGED_KEY })
        }

    @Test
    fun principalsWithoutCertificateAuthorityFailsClosedAndCleansTheLock() =
        withRemoteHome("Principals=\"developer\" $MANAGED_KEY\n") { home, authorizedKeys, original ->
            val result = execute(home)

            assertEquals(70, result.exitCode, result.stderr)
            assertContentEquals(original, authorizedKeys.readBytes())
            assertFalse(
                Files.exists(authorizedKeys.parent.resolve(".agent-relay-authorized-keys.lock")),
            )
        }

    @Test
    fun unrelatedKeyAppendsExactlyOnceAndARerunIsIdempotent() =
        withRemoteHome("ssh-ed25519 BBBB unrelated\n") { home, authorizedKeys, _ ->
            val first = execute(home)
            val afterFirst = authorizedKeys.readBytes()
            val second = execute(home)

            assertEquals(0, first.exitCode, first.stderr)
            assertEquals(0, second.exitCode, second.stderr)
            assertContentEquals(afterFirst, authorizedKeys.readBytes())
            assertEquals(
                1,
                authorizedKeys.readText().lineSequence().count { it == MANAGED_KEY },
            )
        }

    @Test
    fun commentedOutMatchingKeyDoesNotCountAsAuthorized() =
        withRemoteHome("# $MANAGED_KEY disabled\n") { home, authorizedKeys, _ ->
            val result = execute(home)
            val lines = authorizedKeys.readText().lineSequence().toList()

            assertEquals(0, result.exitCode, result.stderr)
            assertEquals("# $MANAGED_KEY disabled", lines.first())
            assertEquals(1, lines.count { it == MANAGED_KEY })
        }

    @Test
    fun missingAwkFailsBeforeMutatingTheAuthorizedKeysFile() =
        withRemoteHome("ssh-ed25519 BBBB existing\n") { home, authorizedKeys, original ->
            val emptyPath = home.resolve("empty-path").createDirectory()
            val result = execute(home, path = emptyPath.toString())

            assertEquals(69, result.exitCode, result.stderr)
            assertContentEquals(original, authorizedKeys.readBytes())
        }

    @Test
    fun brokenAwkFailsBeforeMutatingTheAuthorizedKeysFile() =
        withRemoteHome("ssh-ed25519 BBBB existing\n") { home, authorizedKeys, original ->
            val fakePath = home.resolve("fake-path").createDirectory()
            val fakeAwk = fakePath.resolve("awk")
            fakeAwk.writeText("#!/bin/sh\nexit 2\n")
            Files.setPosixFilePermissions(
                fakeAwk,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )

            val result = execute(home, path = fakePath.toString())

            assertEquals(70, result.exitCode, result.stderr)
            assertContentEquals(original, authorizedKeys.readBytes())
        }

    @Test
    fun successfulInstallRepairsSshDirectoryAndAuthorizedKeysPermissions() =
        withRemoteHome("ssh-ed25519 BBBB existing\n") { home, authorizedKeys, _ ->
            val sshDirectory = authorizedKeys.parent
            Files.setPosixFilePermissions(sshDirectory, ALL_PERMISSIONS)
            Files.setPosixFilePermissions(authorizedKeys, ALL_PERMISSIONS)

            val result = execute(home)

            assertEquals(0, result.exitCode, result.stderr)
            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
                Files.getPosixFilePermissions(sshDirectory),
            )
            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
                Files.getPosixFilePermissions(authorizedKeys),
            )
        }

    @Test
    fun quotedOptionTextCannotMasqueradeAsTheAuthorizedKeyFields() =
        withRemoteHome(
            """command="printf $MANAGED_KEY" ssh-ed25519 BBBB unrelated
            """.trimIndent(),
        ) { home, authorizedKeys, _ ->
            val result = execute(home)

            assertEquals(0, result.exitCode, result.stderr)
            assertEquals(
                1,
                authorizedKeys.readText().lineSequence().count { it == MANAGED_KEY },
            )
        }

    @Test
    fun unrelatedKeyCommentCannotMasqueradeAsTheManagedKeyFields() =
        withRemoteHome(
            "ssh-ed25519 BBBB note $MANAGED_KEY\n",
        ) { home, authorizedKeys, _ ->
            val result = execute(home)

            assertEquals(0, result.exitCode, result.stderr)
            assertEquals(
                1,
                authorizedKeys.readText().lineSequence().count { it == MANAGED_KEY },
            )
        }

    @Test
    fun missingFilesAreCreatedWithTheManagedKeyAndRestrictivePermissions() =
        withRemoteHome(null) { home, authorizedKeys, _ ->
            val result = execute(home)

            assertEquals(0, result.exitCode, result.stderr)
            assertEquals(MANAGED_KEY, authorizedKeys.readText().trim())
            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
                Files.getPosixFilePermissions(authorizedKeys.parent),
            )
            assertEquals(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
                Files.getPosixFilePermissions(authorizedKeys),
            )
        }

    @Test
    fun matchingKeyDoesNotChangeAuthorizedKeysContentOrModificationTime() =
        withRemoteHome("$MANAGED_KEY existing comment\n") { home, authorizedKeys, original ->
            Files.setLastModifiedTime(authorizedKeys, FileTime.fromMillis(1_234_000L))
            val originalModifiedAt = Files.getLastModifiedTime(authorizedKeys)

            val result = execute(home)

            assertEquals(0, result.exitCode, result.stderr)
            assertContentEquals(original, authorizedKeys.readBytes())
            assertEquals(originalModifiedAt, Files.getLastModifiedTime(authorizedKeys))
        }

    @Test
    fun nonRegularAuthorizedKeysPathFailsClosed() =
        withRemoteHome("existing\n") { home, authorizedKeys, _ ->
            Files.delete(authorizedKeys)
            authorizedKeys.createDirectory()

            val result = execute(home)

            assertEquals(73, result.exitCode, result.stderr)
            assertTrue(Files.isDirectory(authorizedKeys))
        }

    @Test
    fun symbolicLinkAuthorizedKeysFailsClosedWithoutTouchingItsTarget() =
        withRemoteHome(null) { home, authorizedKeys, _ ->
            val target = home.resolve("authorized-keys-target")
            val original = "ssh-ed25519 BBBB retained\n".encodeToByteArray()
            Files.write(target, original)
            authorizedKeys.parent.createDirectory()
            Files.createSymbolicLink(authorizedKeys, target)

            val result = execute(home)

            assertEquals(73, result.exitCode, result.stderr)
            assertContentEquals(original, target.readBytes())
            assertTrue(Files.isSymbolicLink(authorizedKeys))
        }

    @Test
    fun regularFileSshPathFailsClosedWithoutChangingTheFile() =
        withRemoteHome(null) { home, authorizedKeys, _ ->
            val sshPath = authorizedKeys.parent
            val original = "not a directory\n".encodeToByteArray()
            Files.write(sshPath, original)

            val result = execute(home)

            assertEquals(73, result.exitCode, result.stderr)
            assertContentEquals(original, sshPath.readBytes())
            assertTrue(Files.isRegularFile(sshPath))
        }

    @Test
    fun fifoAuthorizedKeysPathFailsClosedWithoutOpeningTheFifo() =
        withRemoteHome(null) { home, authorizedKeys, _ ->
            authorizedKeys.parent.createDirectory()
            val mkfifo = ProcessBuilder("mkfifo", authorizedKeys.toString()).start()
            val mkfifoResult = complete(mkfifo)
            assertEquals(0, mkfifoResult.exitCode, mkfifoResult.stderr)

            val result = execute(home)

            assertEquals(73, result.exitCode, result.stderr)
            assertTrue(Files.exists(authorizedKeys))
            assertFalse(Files.isRegularFile(authorizedKeys))
        }

    @Test
    fun symbolicLinkSshDirectoryFailsClosedWithoutTouchingItsTarget() =
        withRemoteHome(null) { home, _, _ ->
            val target = home.resolve("link-target").createDirectory()
            Files.createSymbolicLink(home.resolve(".ssh"), target)

            val result = execute(home)

            assertEquals(73, result.exitCode, result.stderr)
            assertFalse(Files.exists(target.resolve("authorized_keys")))
        }

    @Test
    fun malformedOptionPrefixBeforeTheManagedKeyFailsClosed() =
        withRemoteHome("garbage $MANAGED_KEY\n") { home, authorizedKeys, original ->
            val result = execute(home)

            assertEquals(70, result.exitCode, result.stderr)
            assertContentEquals(original, authorizedKeys.readBytes())
        }

    @Test
    fun staleOwnedLockIsRecoveredAndCleanedAfterInstallation() =
        withRemoteHome("ssh-ed25519 BBBB unrelated\n") { home, authorizedKeys, _ ->
            val lockDirectory =
                authorizedKeys.parent.resolve(".agent-relay-authorized-keys.lock").createDirectory()
            lockDirectory.resolve("owner").writeText("2147483647\n")

            val result = execute(home)

            assertEquals(0, result.exitCode, result.stderr)
            assertEquals(
                1,
                authorizedKeys.readText().lineSequence().count { it == MANAGED_KEY },
            )
            assertFalse(Files.exists(lockDirectory))
        }

    @Test
    fun ownerlessLockIsRecoveredAfterItRemainsUnchanged() =
        withRemoteHome("ssh-ed25519 BBBB unrelated\n") { home, authorizedKeys, _ ->
            val lockDirectory =
                authorizedKeys.parent.resolve(".agent-relay-authorized-keys.lock").createDirectory()

            val result = execute(home)

            assertEquals(0, result.exitCode, result.stderr)
            assertEquals(
                1,
                authorizedKeys.readText().lineSequence().count { it == MANAGED_KEY },
            )
            assertFalse(Files.exists(lockDirectory))
        }

    @Test
    fun malformedOwnerLockIsRecoveredAfterItRemainsUnchanged() =
        withRemoteHome("ssh-ed25519 BBBB unrelated\n") { home, authorizedKeys, _ ->
            val lockDirectory =
                authorizedKeys.parent.resolve(".agent-relay-authorized-keys.lock").createDirectory()
            lockDirectory.resolve("owner").writeText("not-a-pid\n")

            val result = execute(home)

            assertEquals(0, result.exitCode, result.stderr)
            assertEquals(
                1,
                authorizedKeys.readText().lineSequence().count { it == MANAGED_KEY },
            )
            assertFalse(Files.exists(lockDirectory))
        }

    @Test
    fun concurrentInstallersSerializeAndAppendTheManagedKeyOnce() =
        withRemoteHome("ssh-ed25519 BBBB unrelated\n") { home, authorizedKeys, _ ->
            val lockDirectory =
                authorizedKeys.parent.resolve(".agent-relay-authorized-keys.lock").createDirectory()
            val first = start(home)
            val second = start(home)
            Thread.sleep(100)
            Files.delete(lockDirectory)

            val firstResult = complete(first)
            val secondResult = complete(second)

            assertEquals(0, firstResult.exitCode, firstResult.stderr)
            assertEquals(0, secondResult.exitCode, secondResult.stderr)
            assertEquals(
                1,
                authorizedKeys.readText().lineSequence().count { it == MANAGED_KEY },
            )
            assertFalse(Files.exists(lockDirectory))
        }

    @Test
    fun terminationCleansAnOwnedLockAndReturnsInterruptedStatus() =
        withRemoteHome("ssh-ed25519 BBBB unrelated\n") { home, authorizedKeys, original ->
            val fakePath = home.resolve("fake-path").createDirectory()
            val fakeAwk = fakePath.resolve("awk")
            fakeAwk.writeText(
                """
                #!/bin/sh
                case "$*" in
                  *authorized_keys*)
                    while :; do /bin/sleep 1; done
                    ;;
                esac
                exec /usr/bin/awk "$@"
                """.trimIndent() + "\n",
            )
            Files.setPosixFilePermissions(
                fakeAwk,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
            val path = "$fakePath:${System.getenv("PATH")}"
            val lockDirectory =
                authorizedKeys.parent.resolve(".agent-relay-authorized-keys.lock")
            val process = start(home, path)

            try {
                assertTrue(waitForPath(lockDirectory.resolve("owner")))
                val descendants = process.descendants().toList()
                signal(process, "TERM")
                descendants.forEach { it.destroy() }
                assertTrue(process.waitFor(5, TimeUnit.SECONDS))
                val result = complete(process)

                assertEquals(74, result.exitCode, result.stderr)
                assertContentEquals(original, authorizedKeys.readBytes())
                assertFalse(Files.exists(lockDirectory))
            } finally {
                process.descendants().forEach { it.destroyForcibly() }
                if (process.isAlive) {
                    process.destroyForcibly()
                }
            }
        }

    @Test
    fun signalsWhileWaitingReturnInterruptedStatusWithoutRemovingForeignLock() =
        withRemoteHome("ssh-ed25519 BBBB unrelated\n") { home, authorizedKeys, original ->
            val lockDirectory =
                authorizedKeys.parent.resolve(".agent-relay-authorized-keys.lock").createDirectory()
            val owner = lockDirectory.resolve("owner")
            owner.writeText("${ProcessHandle.current().pid()}\n")
            listOf("HUP", "INT", "TERM").forEach { signalName ->
                val process = start(home)
                try {
                    Thread.sleep(100)
                    signal(process, signalName)
                    assertTrue(process.waitFor(5, TimeUnit.SECONDS))
                    val result = complete(process)

                    assertEquals(74, result.exitCode, "$signalName: ${result.stderr}")
                    assertContentEquals(original, authorizedKeys.readBytes())
                    assertTrue(Files.isDirectory(lockDirectory))
                    assertEquals("${ProcessHandle.current().pid()}\n", owner.readText())
                } finally {
                    if (process.isAlive) {
                        process.destroyForcibly()
                    }
                }
            }
        }

    @Test
    fun terminationDuringAcquisitionLeavesAnOwnerlessLockThatTheNextRunRecovers() =
        withRemoteHome("ssh-ed25519 BBBB unrelated\n") { home, authorizedKeys, original ->
            val fakePath = home.resolve("fake-path").createDirectory()
            val fakeMkdir = fakePath.resolve("mkdir")
            fakeMkdir.writeText(
                """
                #!/bin/sh
                /bin/mkdir "${'$'}@"
                status=${'$'}?
                case "${'$'}*" in
                  *.agent-relay-authorized-keys.lock*)
                    if [ "${'$'}status" -eq 0 ]; then
                      /bin/kill -TERM "${'$'}PPID"
                      /bin/sleep 1
                    fi
                    ;;
                esac
                exit "${'$'}status"
                """.trimIndent() + "\n",
            )
            Files.setPosixFilePermissions(fakeMkdir, EXECUTABLE_PERMISSIONS)
            val path = "$fakePath:${System.getenv("PATH")}"

            val interrupted = execute(home, path)
            val lockDirectory =
                authorizedKeys.parent.resolve(".agent-relay-authorized-keys.lock")

            assertEquals(74, interrupted.exitCode, interrupted.stderr)
            assertContentEquals(original, authorizedKeys.readBytes())
            assertTrue(Files.isDirectory(lockDirectory))
            assertFalse(Files.exists(lockDirectory.resolve("owner")))

            val recovered = execute(home)

            assertEquals(0, recovered.exitCode, recovered.stderr)
            assertEquals(
                1,
                authorizedKeys.readText().lineSequence().count { it == MANAGED_KEY },
            )
            assertFalse(Files.exists(lockDirectory))
        }

    @Test
    fun lockAcquiredAfterReclamationRenameIsNotDeletedThroughTheOldPath() =
        withRemoteHome("ssh-ed25519 BBBB unrelated\n") { home, authorizedKeys, original ->
            val lockDirectory =
                authorizedKeys.parent.resolve(".agent-relay-authorized-keys.lock").createDirectory()
            val fakePath = home.resolve("fake-path").createDirectory()
            val fakeMv = fakePath.resolve("mv")
            fakeMv.writeText(
                """
                #!/bin/sh
                /bin/mv "${'$'}@" || exit "${'$'}?"
                /bin/mkdir "${'$'}1"
                printf '%s\n' "${'$'}PPID" > "${'$'}1/owner"
                /bin/kill -TERM "${'$'}PPID"
                """.trimIndent() + "\n",
            )
            Files.setPosixFilePermissions(fakeMv, EXECUTABLE_PERMISSIONS)
            val path = "$fakePath:${System.getenv("PATH")}"
            val process = start(home, path)

            val result = complete(process)

            assertEquals(74, result.exitCode, result.stderr)
            assertContentEquals(original, authorizedKeys.readBytes())
            assertTrue(Files.isDirectory(lockDirectory))
            assertEquals("${process.pid()}\n", lockDirectory.resolve("owner").readText())
            val quarantine = authorizedKeys.parent.resolve(
                ".agent-relay-authorized-keys.recovery.${process.pid()}",
            )
            assertTrue(Files.isDirectory(quarantine))
            assertFalse(Files.exists(quarantine.resolve("recovery")))
        }

    private fun execute(
        home: Path,
        path: String? = null,
    ): ProcessResult = complete(start(home, path))

    private fun start(
        home: Path,
        path: String? = null,
    ): Process {
        val processBuilder = ProcessBuilder(
            "/bin/sh",
            "-c",
            SshAuthorizedKeysInstallScript.build(MANAGED_KEY),
        )
        processBuilder.environment()["HOME"] = home.toString()
        if (path != null) {
            processBuilder.environment()["PATH"] = path
        }
        return processBuilder.start()
    }

    private fun complete(process: Process): ProcessResult {
        runCatching { process.inputStream.bufferedReader().use { it.readText() } }
        val stderr = runCatching {
            process.errorStream.bufferedReader().use { it.readText() }
        }.getOrDefault("")
        return ProcessResult(process.waitFor(), stderr, process.pid())
    }

    private fun signal(
        process: Process,
        signal: String,
    ) {
        val result = complete(
            ProcessBuilder("/bin/kill", "-$signal", process.pid().toString()).start(),
        )
        assertEquals(0, result.exitCode, result.stderr)
    }

    private fun waitForPath(path: Path): Boolean {
        repeat(500) {
            if (Files.exists(path)) {
                return true
            }
            Thread.sleep(10)
        }
        return Files.exists(path)
    }

    private fun withRemoteHome(
        initialAuthorizedKeys: String?,
        block: (home: Path, authorizedKeys: Path, original: ByteArray) -> Unit,
    ) {
        val home = Files.createTempDirectory("agent-relay-authorized-keys-")
        try {
            val authorizedKeys = home.resolve(".ssh").resolve("authorized_keys")
            if (initialAuthorizedKeys != null) {
                authorizedKeys.parent.createDirectories()
                authorizedKeys.writeText(initialAuthorizedKeys)
            }
            val original = if (Files.exists(authorizedKeys)) authorizedKeys.readBytes() else ByteArray(0)
            block(home, authorizedKeys, original)
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    private data class ProcessResult(
        val exitCode: Int,
        val stderr: String,
        val processId: Long,
    )

    companion object {
        private const val MANAGED_KEY = "ecdsa-sha2-nistp256 AAAA"
        private val ALL_PERMISSIONS = PosixFilePermission.entries.toSet()
        private val EXECUTABLE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
    }
}
