package dev.agentrelay.ssh.api

import java.nio.file.Files
import java.nio.file.Path
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

class SshAuthorizedKeysLockRaceTest {
    @Test
    fun ownerlessObservationFromRemovedLockDoesNotReclaimANewAcquisition() =
        withRemoteHome { home, authorizedKeys ->
            val lockDirectory = lockDirectory(authorizedKeys).createDirectory()
            val waiterPath = home.resolve("waiter-path").createDirectory()
            val acquirerPath = home.resolve("acquirer-path").createDirectory()
            val firstWait = home.resolve("waiter-first-wait")
            val secondWait = home.resolve("waiter-second-wait")
            val releaseFirstWait = home.resolve("release-waiter-first-wait")
            val releaseSecondWait = home.resolve("release-waiter-second-wait")
            val moved = home.resolve("waiter-moved-lock")
            val releaseMove = home.resolve("release-waiter-move")
            val acquired = home.resolve("acquirer-created-lock")
            val releaseAcquirer = home.resolve("release-acquirer")
            val sleepCalls = home.resolve("waiter-sleep-calls")
            executable(
                waiterPath.resolve("sleep"),
                """
                #!/bin/sh
                calls=0
                if [ -f "$sleepCalls" ]; then
                  IFS= read -r calls < "$sleepCalls" || exit 1
                fi
                calls=${'$'}((calls + 1))
                printf '%s\n' "${'$'}calls" > "$sleepCalls"
                case "${'$'}calls" in
                  1)
                    : > "$firstWait"
                    while [ ! -e "$releaseFirstWait" ]; do /bin/sleep 0.01; done
                    ;;
                  2)
                    : > "$secondWait"
                    while [ ! -e "$releaseSecondWait" ]; do /bin/sleep 0.01; done
                    ;;
                  *)
                    exec /bin/sleep "${'$'}@"
                    ;;
                esac
                """,
            )
            executable(
                waiterPath.resolve("mv"),
                """
                #!/bin/sh
                /bin/mv "${'$'}@" || exit "${'$'}?"
                case "${'$'}2" in
                  *.agent-relay-authorized-keys.recovery.*)
                    : > "$moved"
                    while [ ! -e "$releaseMove" ]; do /bin/sleep 0.01; done
                    ;;
                esac
                """,
            )
            executable(
                acquirerPath.resolve("mkdir"),
                """
                #!/bin/sh
                /bin/mkdir "${'$'}@"
                status=${'$'}?
                case "${'$'}*" in
                  *.agent-relay-authorized-keys.lock*)
                    if [ "${'$'}status" -eq 0 ]; then
                      : > "$acquired"
                      while [ ! -e "$releaseAcquirer" ]; do /bin/sleep 0.01; done
                    fi
                    ;;
                esac
                exit "${'$'}status"
                """,
            )

            val waiter = start(home, waiterPath)
            var acquirer: Process? = null
            try {
                assertTrue(waitForPath(firstWait), "The waiter did not observe D0")
                assertTrue(lockDirectory.toFile().deleteRecursively(), "D0 was not removed")
                acquirer = start(home, acquirerPath)
                assertTrue(waitForPath(acquired), "The acquirer did not create D1")
                releaseFirstWait.writeText("")

                val waiterState = waitForAny(moved, secondWait)
                assertTrue(waiterState != null, "The waiter reached neither D1 barrier")
                releaseAcquirer.writeText("")
                releaseSecondWait.writeText("")
                releaseMove.writeText("")
                val acquirerResult = complete(acquirer)
                val waiterResult = complete(waiter)

                assertEquals(0, acquirerResult.exitCode, acquirerResult.stderr)
                assertEquals(0, waiterResult.exitCode, waiterResult.stderr)
                assertEquals(1, authorizedKeys.readText().lineSequence().count { it == MANAGED_KEY })
                assertFalse(Files.exists(lockDirectory))
            } finally {
                listOf(
                    releaseFirstWait,
                    releaseSecondWait,
                    releaseMove,
                    releaseAcquirer,
                ).forEach {
                    runCatching { it.writeText("") }
                }
                listOfNotNull(acquirer, waiter).forEach { process ->
                    if (process.isAlive) {
                        process.descendants().forEach { it.destroyForcibly() }
                        process.destroyForcibly()
                        process.waitFor(5, TimeUnit.SECONDS)
                    }
                }
            }
        }

    @Test
    fun sameForeignRecoveryValueDoesNotCarryConfirmationAcrossGenerations() =
        withRemoteHome { home, authorizedKeys ->
            val lockDirectory = lockDirectory(authorizedKeys).createDirectory()
            lockDirectory.resolve("recovery").writeText("2147483647\n")
            val waiterPath = home.resolve("same-marker-waiter-path").createDirectory()
            val acquirerPath = home.resolve("same-marker-acquirer-path").createDirectory()
            val firstWait = home.resolve("same-marker-waiter-first-wait")
            val secondWait = home.resolve("same-marker-waiter-second-wait")
            val releaseFirstWait = home.resolve("release-same-marker-waiter-first-wait")
            val releaseSecondWait = home.resolve("release-same-marker-waiter-second-wait")
            val moved = home.resolve("same-marker-waiter-moved-lock")
            val releaseMove = home.resolve("release-same-marker-waiter-move")
            val acquired = home.resolve("same-marker-acquirer-created-lock")
            val releaseAcquirer = home.resolve("release-same-marker-acquirer")
            val markerInjected = home.resolve("same-marker-injected")
            val sleepCalls = home.resolve("same-marker-waiter-sleep-calls")
            executable(
                waiterPath.resolve("sleep"),
                """
                #!/bin/sh
                calls=0
                if [ -f "$sleepCalls" ]; then
                  IFS= read -r calls < "$sleepCalls" || exit 1
                fi
                calls=${'$'}((calls + 1))
                printf '%s\n' "${'$'}calls" > "$sleepCalls"
                case "${'$'}calls" in
                  1)
                    : > "$firstWait"
                    while [ ! -e "$releaseFirstWait" ]; do /bin/sleep 0.01; done
                    ;;
                  2)
                    : > "$secondWait"
                    while [ ! -e "$releaseSecondWait" ]; do /bin/sleep 0.01; done
                    ;;
                  *)
                    exec /bin/sleep "${'$'}@"
                    ;;
                esac
                """,
            )
            executable(
                waiterPath.resolve("mv"),
                """
                #!/bin/sh
                /bin/mv "${'$'}@" || exit "${'$'}?"
                case "${'$'}2" in
                  *.agent-relay-authorized-keys.recovery.*)
                    : > "$moved"
                    while [ ! -e "$releaseMove" ]; do /bin/sleep 0.01; done
                    ;;
                esac
                """,
            )
            executable(
                acquirerPath.resolve("mkdir"),
                """
                #!/bin/sh
                /bin/mkdir "${'$'}@"
                status=${'$'}?
                case "${'$'}*" in
                  *.agent-relay-authorized-keys.lock*)
                    if [ "${'$'}status" -eq 0 ] && [ ! -e "$markerInjected" ]; then
                      : > "$markerInjected"
                      printf '%s\n' '2147483647' > "${'$'}1/recovery"
                      : > "$acquired"
                      while [ ! -e "$releaseAcquirer" ]; do /bin/sleep 0.01; done
                    fi
                    ;;
                esac
                exit "${'$'}status"
                """,
            )

            val waiter = start(home, waiterPath)
            var acquirer: Process? = null
            try {
                assertTrue(waitForPath(firstWait), "The waiter did not observe D0")
                assertTrue(lockDirectory.toFile().deleteRecursively(), "D0 was not removed")
                acquirer = start(home, acquirerPath)
                assertTrue(waitForPath(acquired), "The acquirer did not create D1")
                releaseFirstWait.writeText("")

                val waiterState = waitForAny(moved, secondWait)
                assertEquals(
                    secondWait,
                    waiterState,
                    "D1 reused D0 confirmation solely because its stale marker value matched",
                )
                releaseAcquirer.writeText("")
                releaseSecondWait.writeText("")
                releaseMove.writeText("")
                val acquirerResult = complete(acquirer)
                val waiterResult = complete(waiter)

                assertEquals(0, acquirerResult.exitCode, acquirerResult.stderr)
                assertEquals(0, waiterResult.exitCode, waiterResult.stderr)
                assertEquals(1, authorizedKeys.readText().lineSequence().count { it == MANAGED_KEY })
                assertFalse(Files.exists(lockDirectory))
            } finally {
                listOf(
                    releaseFirstWait,
                    releaseSecondWait,
                    releaseMove,
                    releaseAcquirer,
                ).forEach { runCatching { it.writeText("") } }
                listOfNotNull(acquirer, waiter).forEach { process ->
                    if (process.isAlive) {
                        process.descendants().forEach { it.destroyForcibly() }
                        process.destroyForcibly()
                        process.waitFor(5, TimeUnit.SECONDS)
                    }
                }
            }
        }

    @Test
    fun ownerPublishedBeforeQuarantineMakesBothInstallersRetryAndSucceed() =
        withRemoteHome { home, authorizedKeys ->
            val lockDirectory = lockDirectory(authorizedKeys).createDirectory()
            val waiterPath = home.resolve("published-waiter-path").createDirectory()
            val acquirerPath = home.resolve("published-acquirer-path").createDirectory()
            val firstWait = home.resolve("published-waiter-first-wait")
            val secondWait = home.resolve("published-waiter-second-wait")
            val thirdWait = home.resolve("published-waiter-third-wait")
            val releaseFirstWait = home.resolve("release-published-waiter-first-wait")
            val releaseSecondWait = home.resolve("release-published-waiter-second-wait")
            val releaseThirdWait = home.resolve("release-published-waiter-third-wait")
            val acquired = home.resolve("published-acquirer-created-lock")
            val releaseAcquirer = home.resolve("release-published-acquirer")
            val relinquishing = home.resolve("published-acquirer-relinquishing")
            val releaseRelinquish = home.resolve("release-published-acquirer-relinquish")
            val sleepCalls = home.resolve("published-waiter-sleep-calls")
            executable(
                waiterPath.resolve("sleep"),
                """
                #!/bin/sh
                calls=0
                if [ -f "$sleepCalls" ]; then
                  IFS= read -r calls < "$sleepCalls" || exit 1
                fi
                calls=${'$'}((calls + 1))
                printf '%s\n' "${'$'}calls" > "$sleepCalls"
                case "${'$'}calls" in
                  1)
                    : > "$firstWait"
                    while [ ! -e "$releaseFirstWait" ]; do /bin/sleep 0.01; done
                    ;;
                  2)
                    : > "$secondWait"
                    while [ ! -e "$releaseSecondWait" ]; do /bin/sleep 0.01; done
                    ;;
                  3)
                    : > "$thirdWait"
                    while [ ! -e "$releaseThirdWait" ]; do /bin/sleep 0.01; done
                    ;;
                  *)
                    exec /bin/sleep "${'$'}@"
                    ;;
                esac
                """,
            )
            executable(
                acquirerPath.resolve("mkdir"),
                """
                #!/bin/sh
                /bin/mkdir "${'$'}@"
                status=${'$'}?
                case "${'$'}*" in
                  *.agent-relay-authorized-keys.lock*)
                    if [ "${'$'}status" -eq 0 ]; then
                      : > "$acquired"
                      while [ ! -e "$releaseAcquirer" ]; do /bin/sleep 0.01; done
                    fi
                    ;;
                esac
                exit "${'$'}status"
                """,
            )
            executable(
                acquirerPath.resolve("rm"),
                """
                #!/bin/sh
                case "${'$'}*" in
                  *.agent-relay-authorized-keys.lock/owner*)
                    : > "$relinquishing"
                    while [ ! -e "$releaseRelinquish" ]; do /bin/sleep 0.01; done
                    ;;
                esac
                exec /bin/rm "${'$'}@"
                """,
            )

            val waiter = start(home, waiterPath)
            var acquirer: Process? = null
            try {
                assertTrue(waitForPath(firstWait), "The waiter did not observe D0")
                assertTrue(lockDirectory.toFile().deleteRecursively(), "D0 was not removed")
                acquirer = start(home, acquirerPath)
                assertTrue(waitForPath(acquired), "The acquirer did not create D1")
                releaseFirstWait.writeText("")
                assertTrue(waitForPath(secondWait), "D1 was not treated as a fresh observation")

                releaseAcquirer.writeText("")
                assertTrue(waitForPath(relinquishing), "The acquirer did not publish its owner")
                releaseSecondWait.writeText("")
                assertTrue(waitForPath(thirdWait), "The waiter did not defer to the live owner")
                releaseRelinquish.writeText("")
                releaseThirdWait.writeText("")
                val acquirerResult = complete(acquirer)
                val waiterResult = complete(waiter)

                assertEquals(0, acquirerResult.exitCode, acquirerResult.stderr)
                assertEquals(0, waiterResult.exitCode, waiterResult.stderr)
                assertEquals(1, authorizedKeys.readText().lineSequence().count { it == MANAGED_KEY })
                assertFalse(Files.exists(lockDirectory))
            } finally {
                listOf(
                    releaseFirstWait,
                    releaseSecondWait,
                    releaseThirdWait,
                    releaseAcquirer,
                    releaseRelinquish,
                ).forEach { runCatching { it.writeText("") } }
                listOfNotNull(acquirer, waiter).forEach { process ->
                    if (process.isAlive) {
                        process.descendants().forEach { it.destroyForcibly() }
                        process.destroyForcibly()
                        process.waitFor(5, TimeUnit.SECONDS)
                    }
                }
            }
        }

    @Test
    fun failedOwnerPublicationCleanupDoesNotRemoveAReplacementOwnerlessLock() =
        withRemoteHome { home, authorizedKeys ->
            val lockDirectory = lockDirectory(authorizedKeys)
            val displacedLock = authorizedKeys.parent.resolve("displaced-acquisition")
            val script = SshAuthorizedKeysInstallScript.build(MANAGED_KEY)
                .replace(
                    "    lock_owned=1\n    if ! (set -C;",
                    "    lock_owned=1\n    mv \"${'$'}lock_dir\" \"$displacedLock\"\n    if ! (set -C;",
                )
                .replace(
                    "      lock_owned=0\n      if ! owner_publication_failure_is_retryable",
                    "      lock_owned=0\n      mkdir \"${'$'}lock_dir\"\n" +
                        "      if ! owner_publication_failure_is_retryable",
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
    fun signalBeforeRecoveryMarkerPublicationPreservesAReplacementClaim() =
        withRemoteHome { home, authorizedKeys ->
            val lockDirectory = lockDirectory(authorizedKeys).createDirectory()
            val displacedLock = authorizedKeys.parent.resolve("displaced-before-recovery-claim")
            val script = SshAuthorizedKeysInstallScript.build(MANAGED_KEY).replace(
                "    recovery_owned=1\n    if ! (set -C;",
                """
                    recovery_owned=1
                    mv "${'$'}lock_dir" "$displacedLock"
                    mkdir "${'$'}lock_dir"
                    printf '%s\n' '${ProcessHandle.current().pid()}' > "${'$'}lock_recovery_file"
                    kill -TERM "${'$'}${'$'}"
                    if ! (set -C;
                """.trimIndent(),
            )

            val result = execute(home, script = script)

            assertEquals(74, result.exitCode, result.stderr)
            assertTrue(Files.isDirectory(displacedLock))
            assertTrue(Files.isDirectory(lockDirectory))
            assertFalse(Files.exists(lockDirectory.resolve("owner")))
            assertEquals(
                "${ProcessHandle.current().pid()}\n",
                lockDirectory.resolve("recovery").readText(),
            )
            assertContentEquals(ORIGINAL, authorizedKeys.readBytes())
        }
}

class SshAuthorizedKeysLockCleanupTest {
    @Test
    fun signalAfterObservationLinkBeforeOwnershipPreservesTheUnownedSnapshot() =
        withRemoteHome { home, authorizedKeys ->
            val lockDirectory = lockDirectory(authorizedKeys).createDirectory()
            val recoveryMarker = lockDirectory.resolve("recovery")
            recoveryMarker.writeText("2147483647\n")
            val script = SshAuthorizedKeysInstallScript.build(MANAGED_KEY).replace(
                "      if ! ln \"${'$'}lock_recovery_file\" \"${'$'}lock_observation_file\" " +
                    "2>/dev/null; then\n" +
                    "        return 1\n" +
                    "      fi\n" +
                    "      if ! read_regular_file_inode \"${'$'}lock_observation_file\"; then",
                "      if ! ln \"${'$'}lock_recovery_file\" \"${'$'}lock_observation_file\" " +
                    "2>/dev/null; then\n" +
                    "        return 1\n" +
                    "      fi\n" +
                    "      kill -TERM \"${'$'}${'$'}\"\n" +
                    "      if ! read_regular_file_inode \"${'$'}lock_observation_file\"; then",
            )

            val result = execute(home, script = script)
            val observation = authorizedKeys.parent.resolve(
                ".agent-relay-authorized-keys.observation.${result.processId}",
            )

            assertEquals(74, result.exitCode, result.stderr)
            assertTrue(Files.isRegularFile(recoveryMarker))
            assertTrue(Files.isRegularFile(observation))
            assertTrue(Files.isSameFile(recoveryMarker, observation))
            assertContentEquals(ORIGINAL, authorizedKeys.readBytes())
        }

    @Test
    fun preexistingObservationSnapshotFailsClosedWithoutRemovingIt() =
        withRemoteHome { home, authorizedKeys ->
            val lockDirectory = lockDirectory(authorizedKeys).createDirectory()
            val recoveryMarker = lockDirectory.resolve("recovery")
            recoveryMarker.writeText("2147483647\n")
            val fakePath = home.resolve("preexisting-observation-path").createDirectory()
            executable(
                fakePath.resolve("sleep"),
                """
                #!/bin/sh
                exit 0
                """,
            )
            val script = SshAuthorizedKeysInstallScript.build(MANAGED_KEY).replace(
                "recover_stale_lock() {\n  confirmation_required=0",
                "recover_stale_lock() {\n" +
                    "  if [ ! -e \"${'$'}lock_observation_file\" ] && " +
                    "[ ! -L \"${'$'}lock_observation_file\" ]; then\n" +
                    "    printf '%s\\n' 'preexisting' > \"${'$'}lock_observation_file\"\n" +
                    "  fi\n" +
                    "  confirmation_required=0",
            )

            val result = execute(home, fakePath, script)
            val observation = authorizedKeys.parent.resolve(
                ".agent-relay-authorized-keys.observation.${result.processId}",
            )

            assertEquals(75, result.exitCode, result.stderr)
            assertEquals("preexisting\n", observation.readText())
            assertEquals("2147483647\n", recoveryMarker.readText())
            assertContentEquals(ORIGINAL, authorizedKeys.readBytes())
        }

    @Test
    fun replacedOwnedObservationSnapshotIsNotRemovedBySignalCleanup() =
        withRemoteHome { home, authorizedKeys ->
            val lockDirectory = lockDirectory(authorizedKeys).createDirectory()
            val recoveryMarker = lockDirectory.resolve("recovery")
            recoveryMarker.writeText("2147483647\n")
            val displacedObservation = home.resolve("displaced-owned-observation")
            val script = SshAuthorizedKeysInstallScript.build(MANAGED_KEY).replace(
                "      observation_inode=${'$'}observed_inode\n" +
                    "      observation_owned=1\n" +
                    "      observation_marker_created=1",
                "      observation_inode=${'$'}observed_inode\n" +
                    "      observation_owned=1\n" +
                    "      mv \"${'$'}lock_observation_file\" \"$displacedObservation\"\n" +
                    "      printf '%s\\n' 'replacement' > \"${'$'}lock_observation_file\"\n" +
                    "      kill -TERM \"${'$'}${'$'}\"\n" +
                    "      observation_marker_created=1",
            )

            val result = execute(home, script = script)
            val observation = authorizedKeys.parent.resolve(
                ".agent-relay-authorized-keys.observation.${result.processId}",
            )

            assertEquals(74, result.exitCode, result.stderr)
            assertEquals("replacement\n", observation.readText())
            assertTrue(Files.isSameFile(recoveryMarker, displacedObservation))
            assertEquals("2147483647\n", recoveryMarker.readText())
            assertContentEquals(ORIGINAL, authorizedKeys.readBytes())
        }

    @Test
    fun concurrentReclaimersSerializeAndRemoveOnlyTheirOwnObservationSnapshots() =
        withRemoteHome { home, authorizedKeys ->
            val lockDirectory = lockDirectory(authorizedKeys).createDirectory()
            lockDirectory.resolve("recovery").writeText("2147483647\n")
            val fakePath = home.resolve("concurrent-reclaimer-path").createDirectory()
            val moveBarrier = home.resolve("concurrent-reclaimer-moves").createDirectory()
            val releaseMoves = home.resolve("release-concurrent-reclaimer-moves")
            executable(
                fakePath.resolve("mv"),
                """
                #!/bin/sh
                case "${'$'}2" in
                  *.agent-relay-authorized-keys.recovery.*)
                    : > "$moveBarrier/move-${'$'}PPID"
                    while [ ! -e "$releaseMoves" ]; do /bin/sleep 0.01; done
                    ;;
                esac
                exec /bin/mv "${'$'}@"
                """,
            )

            val first = start(home, fakePath)
            val second = start(home, fakePath)
            try {
                assertTrue(
                    waitForPath(moveBarrier.resolve("move-${first.pid()}")),
                    "The first reclaimer did not reach atomic quarantine",
                )
                assertTrue(
                    waitForPath(moveBarrier.resolve("move-${second.pid()}")),
                    "The second reclaimer did not reach atomic quarantine",
                )
                releaseMoves.writeText("")
                val firstResult = complete(first)
                val secondResult = complete(second)

                assertEquals(0, firstResult.exitCode, firstResult.stderr)
                assertEquals(0, secondResult.exitCode, secondResult.stderr)
                assertEquals(1, authorizedKeys.readText().lineSequence().count { it == MANAGED_KEY })
                assertFalse(Files.exists(lockDirectory))
                assertFalse(
                    Files.exists(
                        authorizedKeys.parent.resolve(
                            ".agent-relay-authorized-keys.observation.${first.pid()}",
                        ),
                    ),
                )
                assertFalse(
                    Files.exists(
                        authorizedKeys.parent.resolve(
                            ".agent-relay-authorized-keys.observation.${second.pid()}",
                        ),
                    ),
                )
            } finally {
                runCatching { releaseMoves.writeText("") }
                listOf(first, second).forEach { process ->
                    if (process.isAlive) {
                        process.descendants().forEach { it.destroyForcibly() }
                        process.destroyForcibly()
                        process.waitFor(5, TimeUnit.SECONDS)
                    }
                }
            }
        }

    @Test
    fun staleRecoveryMarkerIsQuarantinedBeforeAReplacementClaimCanBePublished() =
        withRemoteHome { home, authorizedKeys ->
            val lockDirectory = lockDirectory(authorizedKeys).createDirectory()
            lockDirectory.resolve("recovery").writeText("2147483647\n")
            val replacementPublished = home.resolve("replacement-recovery-published")
            val fakePath = home.resolve("stale-recovery-path").createDirectory()
            executable(
                fakePath.resolve("rm"),
                """
                #!/bin/sh
                case "${'$'}*" in
                  *.agent-relay-authorized-keys.lock/recovery*)
                    /bin/rm "${'$'}@" || exit "${'$'}?"
                    printf '%s\n' '${ProcessHandle.current().pid()}' > "$lockDirectory/recovery"
                    : > "$replacementPublished"
                    exit 0
                    ;;
                esac
                exec /bin/rm "${'$'}@"
                """,
            )

            val result = execute(home, fakePath)

            assertEquals(0, result.exitCode, result.stderr)
            assertFalse(Files.exists(replacementPublished))
            assertEquals(1, authorizedKeys.readText().lineSequence().count { it == MANAGED_KEY })
            assertFalse(Files.exists(lockDirectory))
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
}

private fun execute(
    home: Path,
    fakePath: Path? = null,
    script: String = SshAuthorizedKeysInstallScript.build(MANAGED_KEY),
): ProcessResult = complete(start(home, fakePath, script))

private fun start(
    home: Path,
    fakePath: Path? = null,
    script: String = SshAuthorizedKeysInstallScript.build(MANAGED_KEY),
): Process {
    val builder = ProcessBuilder(
        "/bin/sh",
        "-c",
        script,
    )
    builder.environment()["HOME"] = home.toString()
    if (fakePath != null) {
        builder.environment()["PATH"] = "$fakePath:${System.getenv("PATH")}"
    }
    return builder.start()
}

private fun complete(process: Process): ProcessResult {
    if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
        process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        throw AssertionError("Installer process ${process.pid()} did not finish")
    }
    process.inputStream.bufferedReader().use { it.readText() }
    val stderr = process.errorStream.bufferedReader().use { it.readText() }
    return ProcessResult(process.exitValue(), stderr, process.pid())
}

private fun waitForPath(path: Path): Boolean = waitForAny(path) != null

private fun waitForAny(vararg paths: Path): Path? {
    repeat(500) {
        paths.firstOrNull { Files.exists(it) }?.let { return it }
        Thread.sleep(10)
    }
    return paths.firstOrNull { Files.exists(it) }
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

private const val PROCESS_TIMEOUT_SECONDS = 10L
private const val MANAGED_KEY = "ecdsa-sha2-nistp256 AAAA"
private val ORIGINAL = "ssh-ed25519 BBBB unrelated\n".encodeToByteArray()
private val EXECUTABLE_PERMISSIONS = setOf(
    PosixFilePermission.OWNER_READ,
    PosixFilePermission.OWNER_WRITE,
    PosixFilePermission.OWNER_EXECUTE,
)
