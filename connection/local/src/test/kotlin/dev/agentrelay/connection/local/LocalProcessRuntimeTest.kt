/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.connection.local

import dev.agentrelay.provider.api.RemoteCommand
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LocalProcessRuntimeTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun argumentsArePassedLiterallyWithoutAnImplicitShell() = runTest {
        val workspace = temporaryFolder.newFolder("workspace")
        val marker = File(temporaryFolder.root, "must-not-exist")
        val script = executable(
            workspace,
            "arguments.sh",
            """
            #!/bin/sh
            for value in "${'$'}@"; do
              printf '<%s>\n' "${'$'}value"
            done
            """.trimIndent(),
        )
        val runtime = LocalProcessRuntime(workspace)

        val result = runtime.execute(
            RemoteCommand(
                program = script.absolutePath,
                arguments = listOf(
                    "\$(touch ${marker.absolutePath})",
                    "semi;colon",
                    "a'b",
                ),
            ),
        )

        assertEquals(0, result.exitCode)
        assertEquals(
            "<\$(touch ${marker.absolutePath})>\n<semi;colon>\n<a'b>\n",
            result.standardOutput,
        )
        assertFalse(marker.exists())
        runtime.close()
    }

    @Test
    fun workingDirectoryCannotEscapeTheConfiguredRoot() = runTest {
        val workspace = temporaryFolder.newFolder("workspace")
        val outside = temporaryFolder.newFolder("outside")
        val runtime = LocalProcessRuntime(workspace)

        val failure = assertFailsWith<LocalProcessException> {
            runtime.execute(
                RemoteCommand(
                    program = "/bin/pwd",
                    workingDirectory = outside.absolutePath,
                ),
            )
        }

        assertEquals("LOCAL_WORKING_DIRECTORY_DENIED", failure.code)
        runtime.close()
    }

    @Test
    fun symlinkedWorkingDirectoryCannotEscapeTheConfiguredRoot() = runTest {
        val workspace = temporaryFolder.newFolder("workspace")
        val outside = temporaryFolder.newFolder("outside")
        val link = File(workspace, "outside-link")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val runtime = LocalProcessRuntime(workspace)

        val failure = assertFailsWith<LocalProcessException> {
            runtime.execute(
                RemoteCommand(
                    program = "/bin/pwd",
                    workingDirectory = link.absolutePath,
                ),
            )
        }

        assertEquals("LOCAL_WORKING_DIRECTORY_DENIED", failure.code)
        runtime.close()
    }

    @Test
    fun timeoutStopsTheLocalProcess() = runTest {
        val workspace = temporaryFolder.newFolder("workspace")
        val runtime = LocalProcessRuntime(workspace)

        val failure = assertFailsWith<LocalProcessException> {
            runtime.execute(
                RemoteCommand(
                    program = "/bin/sleep",
                    arguments = listOf("5"),
                ),
                timeout = 50.milliseconds,
            )
        }

        assertEquals("LOCAL_COMMAND_TIMEOUT", failure.code)
        runtime.close()
    }

    @Test
    fun duplexProcessStreamsLinesAndAcceptsInput() = runTest {
        val workspace = temporaryFolder.newFolder("workspace")
        val script = executable(
            workspace,
            "duplex.sh",
            """
            #!/bin/sh
            IFS= read -r value
            printf 'reply:%s\n' "${'$'}value"
            """.trimIndent(),
        )
        val runtime = LocalProcessRuntime(workspace)
        val process = runtime.openProcess(RemoteCommand(script.absolutePath))
        val reply = async { process.standardOutputLines.first() }

        process.writeLine("literal;\$(not executed)")

        assertEquals("reply:literal;\$(not executed)", reply.await())
        process.close()
        runtime.close()
    }

    @Test
    fun runtimeCloseReleasesAnUncollectedDuplexProcess() = runTest {
        val workspace = temporaryFolder.newFolder("workspace")
        val runtime = LocalProcessRuntime(workspace)
        val process = runtime.openProcess(
            RemoteCommand(
                program = "/bin/sh",
                arguments = listOf("-c", "while true; do printf 'line\\n'; done"),
            ),
        )

        runtime.close()

        assertEquals(-1, withTimeout(1.seconds) { process.exitCode.filterNotNull().first() })
    }

    @Test
    fun independentProcessesExecuteConcurrently() = runTest {
        val workspace = temporaryFolder.newFolder("workspace")
        val script = executable(
            workspace,
            "print.sh",
            """
            #!/bin/sh
            sleep 0.05
            printf '%s' "${'$'}1"
            """.trimIndent(),
        )
        val runtime = LocalProcessRuntime(workspace)

        val outputs = coroutineScope {
            listOf("first", "second").map { value ->
                async {
                    runtime.execute(
                        RemoteCommand(script.absolutePath, arguments = listOf(value)),
                        timeout = 2.seconds,
                    ).standardOutput
                }
            }.map { it.await() }
        }

        assertEquals(listOf("first", "second"), outputs)
        runtime.close()
    }

    @Test
    fun closedRuntimeRejectsNewProcesses() = runTest {
        val workspace = temporaryFolder.newFolder("workspace")
        val runtime = LocalProcessRuntime(workspace)
        runtime.close()

        val failure = assertFailsWith<LocalProcessException> {
            runtime.execute(RemoteCommand("/bin/true"))
        }

        assertEquals("LOCAL_RUNTIME_CLOSED", failure.code)
        assertTrue(failure.message.orEmpty().contains("Reconnect"))
    }

    private fun executable(
        directory: File,
        name: String,
        contents: String,
    ): File = File(directory, name).apply {
        writeText(contents + "\n")
        assertTrue(setExecutable(true, true))
    }
}
