/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.connection.local

import dev.agentrelay.provider.api.RemoteFileAccessException
import dev.agentrelay.provider.api.RemoteFileReference
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class LocalRemoteFileAccessTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun inspectAndReadStayInsideWorkspaceAndVerifyChecksum() = runTest {
        val project = temporaryFolder.newFolder("project")
        val report = File(project, "reports/result.txt")
        report.parentFile.mkdirs()
        report.writeText("hello")
        val runtime = LocalProcessRuntime(temporaryFolder.root)
        val access = assertNotNull(runtime.fileAccess)
        val reference = RemoteFileReference("project", "reports/result.txt")

        val snapshot = access.inspect(reference, calculateSha256 = true)
        val content = access.read(
            reference = reference,
            expectedRevision = snapshot.revision,
            chunkSizeBytes = 2,
        ).toList().fold(ByteArray(0)) { result, chunk -> result + chunk }

        assertEquals(5, snapshot.revision.sizeBytes)
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            snapshot.revision.sha256,
        )
        assertContentEquals("hello".encodeToByteArray(), content)
        runtime.close()
    }

    @Test
    fun readRejectsAFileChangedAfterInspection() = runTest {
        val project = temporaryFolder.newFolder("project")
        val report = File(project, "result.txt")
        report.writeText("first")
        val runtime = LocalProcessRuntime(temporaryFolder.root)
        val access = assertNotNull(runtime.fileAccess)
        val reference = RemoteFileReference("project", "result.txt")
        val snapshot = access.inspect(reference, calculateSha256 = true)
        report.writeText("changed content")

        val failure = assertFailsWith<RemoteFileAccessException> {
            access.read(reference, snapshot.revision).toList()
        }

        assertEquals("REMOTE_FILE_CHANGED", failure.code)
        runtime.close()
    }

    @Test
    fun inspectRejectsAWorkspaceOutsideTheConfiguredRoot() = runTest {
        val runtime = LocalProcessRuntime(temporaryFolder.newFolder("allowed"))
        val access = assertNotNull(runtime.fileAccess)
        val outside = temporaryFolder.newFolder("outside")
        File(outside, "secret.txt").writeText("secret")
        val reference = RemoteFileReference(outside.absolutePath, "secret.txt")

        val failure = assertFailsWith<RemoteFileAccessException> {
            access.inspect(reference)
        }

        assertEquals("REMOTE_FILE_PATH_DENIED", failure.code)
        runtime.close()
    }
}
