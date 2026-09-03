package dev.agentrelay.ssh.api

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
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

class SshAuthorizedKeysLockRaceTest {
    @Test
    fun failedOwnerPublicationCleanupDoesNotRemoveAReplacementOwnerlessLock() =
        withRemoteHome { home, authorizedKeys ->
            val lockDirectory = lockDirectory(authorizedKeys)
            val displacedLock = authorizedKeys.parent.resolve("displaced-acquisition")
            val script = SshAuthorizedKeysInstallScript.build(MANAGED_KEY)
                .replace(
                    "lock_owned=1\nif ! (set -C;",
                    "lock_owned=1\nmv \"${'$'}lock_dir\" \"$displacedLock\"\nif ! (set -C;",
                )
                .replace(
                    "then\n  exit 75\nfi\nif ! read_lock_owner",
                    "then\n  mkdir \"${'$'}lock_dir\"\n  exit 75\nfi\nif ! read_lock_owner",
                )

            val result = execute(home, script = script)

            assertEquals(75, result.exitCode, result.stderr)
            assertTrue(Files.isDirectory(displacedLock))
            assertTrue(Files.isDirectory(lockDirectory))
            assertFalse(Files.exists(lockDirectory.resolve("owner")))
            assertContentEquals(ORIGINAL, authorizedKeys.readBytes())
        }

    @Test
    fun ownerPublishedByReplacementBeforeQuarantineIsPreserved() =
        withRemoteHome { home, authorizedKeys ->
            val lockDirectory = lockDirectory(authorizedKeys).createDirectory()
            val displacedLock = authorizedKeys.parent.resolve("displaced-lock")
            val fakePath = home.resolve("fake-path").createDirectory()
            executable(
                fakePath.resolve("sleep"),
                """
                #!/bin/sh
                if [ ! -e "$displacedLock" ]; then
                  /bin/mv "$lockDirectory" "$displacedLock"
                  /bin/mkdir "$lockDirectory"
                fi
                exec /bin/sleep "${'$'}@"
                """,
            )
            executable(
                fakePath.resolve("mv"),
                """
                #!/bin/sh
                printf '%s\n' '${ProcessHandle.current().pid()}' > "${'$'}1/owner"
                exec /bin/mv "${'$'}@"
                """,
            )

            val result = execute(home, fakePath)
            val quarantine = authorizedKeys.parent.resolve(
                ".agent-relay-authorized-keys.recovery.${result.processId}",
            )

            assertEquals(0, result.exitCode, result.stderr)
            assertEquals("${ProcessHandle.current().pid()}\n", quarantine.resolve("owner").readText())
            assertFalse(Files.exists(quarantine.resolve("recovery")))
            assertTrue(Files.isDirectory(displacedLock))
        }

    @Test
    fun acquisitionHandshakeRejectsARecoveryClaimPublishedBeforeOwnerWrite() =
        withRemoteHome { home, authorizedKeys ->
            val fakePath = home.resolve("fake-path").createDirectory()
            executable(
                fakePath.resolve("mkdir"),
                """
                #!/bin/sh
                /bin/mkdir "${'$'}@" || exit "${'$'}?"
                case "${'$'}*" in
                  *.agent-relay-authorized-keys.lock*)
                    printf '%s\n' '${ProcessHandle.current().pid()}' > "${'$'}1/recovery"
                    ;;
                esac
                """,
            )

            val result = execute(home, fakePath)
            val lockDirectory = lockDirectory(authorizedKeys)

            assertEquals(75, result.exitCode, result.stderr)
            assertContentEquals(ORIGINAL, authorizedKeys.readBytes())
            assertFalse(Files.exists(lockDirectory.resolve("owner")))
            assertEquals(
                "${ProcessHandle.current().pid()}\n",
                lockDirectory.resolve("recovery").readText(),
            )
        }

    @Test
    fun signalAfterRecoveryClaimDoesNotLeaveAPermanentMarker() =
        withRemoteHome { home, authorizedKeys ->
            val lockDirectory = lockDirectory(authorizedKeys).createDirectory()
            val fakePath = home.resolve("fake-path").createDirectory()
            executable(
                fakePath.resolve("mv"),
                """
                #!/bin/sh
                /bin/kill -TERM "${'$'}PPID"
                /bin/sleep 1
                exit 1
                """,
            )

            val interrupted = execute(home, fakePath)

            assertEquals(74, interrupted.exitCode, interrupted.stderr)
            assertContentEquals(ORIGINAL, authorizedKeys.readBytes())
            assertTrue(Files.isDirectory(lockDirectory))
            assertFalse(Files.exists(lockDirectory.resolve("recovery")))

            val recovered = execute(home)

            assertEquals(0, recovered.exitCode, recovered.stderr)
            assertFalse(Files.exists(lockDirectory))
        }

    @Test
    fun deadRecoveryOwnerMarkerIsReclaimed() =
        withRemoteHome { home, authorizedKeys ->
            val lockDirectory = lockDirectory(authorizedKeys).createDirectory()
            lockDirectory.resolve("recovery").writeText("2147483647\n")

            val result = execute(home)

            assertEquals(0, result.exitCode, result.stderr)
            assertEquals(1, authorizedKeys.readText().lineSequence().count { it == MANAGED_KEY })
            assertFalse(Files.exists(lockDirectory))
        }

    @Test
    fun emptyRecoveryOwnerMarkerIsReclaimed() =
        withRemoteHome { home, authorizedKeys ->
            val lockDirectory = lockDirectory(authorizedKeys).createDirectory()
            lockDirectory.resolve("recovery").writeText("")

            val result = execute(home)

            assertEquals(0, result.exitCode, result.stderr)
            assertEquals(1, authorizedKeys.readText().lineSequence().count { it == MANAGED_KEY })
            assertFalse(Files.exists(lockDirectory))
        }

    @Test
    fun unterminatedDeadRecoveryOwnerMarkerIsReclaimed() =
        withRemoteHome { home, authorizedKeys ->
            val lockDirectory = lockDirectory(authorizedKeys).createDirectory()
            lockDirectory.resolve("recovery").writeText("2147483647")

            val result = execute(home)

            assertEquals(0, result.exitCode, result.stderr)
            assertEquals(1, authorizedKeys.readText().lineSequence().count { it == MANAGED_KEY })
            assertFalse(Files.exists(lockDirectory))
        }

    @Test
    fun unterminatedLiveRecoveryOwnerMarkerIsPreserved() =
        withRemoteHome { home, authorizedKeys ->
            val lockDirectory = lockDirectory(authorizedKeys).createDirectory()
            val marker = lockDirectory.resolve("recovery")
            marker.writeText(ProcessHandle.current().pid().toString())

            val result = execute(home)

            assertEquals(75, result.exitCode, result.stderr)
            assertContentEquals(ORIGINAL, authorizedKeys.readBytes())
            assertEquals(ProcessHandle.current().pid().toString(), marker.readText())
            assertTrue(Files.isDirectory(lockDirectory))
        }

    private fun execute(
        home: Path,
        fakePath: Path? = null,
        script: String = SshAuthorizedKeysInstallScript.build(MANAGED_KEY),
    ): ProcessResult {
        val builder = ProcessBuilder(
            "/bin/sh",
            "-c",
            script,
        )
        builder.environment()["HOME"] = home.toString()
        if (fakePath != null) {
            builder.environment()["PATH"] = "$fakePath:${System.getenv("PATH")}"
        }
        val process = builder.start()
        process.inputStream.bufferedReader().use { it.readText() }
        val stderr = process.errorStream.bufferedReader().use { it.readText() }
        return ProcessResult(process.waitFor(), stderr, process.pid())
    }

    private fun executable(path: Path, source: String) {
        path.writeText(source.trimIndent() + "\n")
        Files.setPosixFilePermissions(path, EXECUTABLE_PERMISSIONS)
    }

    private fun lockDirectory(authorizedKeys: Path): Path =
        authorizedKeys.parent.resolve(".agent-relay-authorized-keys.lock")

    private fun withRemoteHome(block: (Path, Path) -> Unit) {
        val home = Files.createTempDirectory("agent-relay-authorized-keys-race-")
        try {
            val authorizedKeys = home.resolve(".ssh/authorized_keys")
            authorizedKeys.parent.createDirectories()
            Files.write(authorizedKeys, ORIGINAL)
            block(home, authorizedKeys)
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
        private val ORIGINAL = "ssh-ed25519 BBBB unrelated\n".encodeToByteArray()
        private val EXECUTABLE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
    }
}
