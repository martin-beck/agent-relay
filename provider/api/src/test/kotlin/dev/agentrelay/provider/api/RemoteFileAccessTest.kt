package dev.agentrelay.provider.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemoteFileAccessTest {
    @Test
    fun referenceAcceptsOnlyUnambiguousWorkspaceRelativePaths() {
        val reference = RemoteFileReference(
            workspaceRoot = "/workspace/project",
            relativePath = "reports/result.json",
        )

        assertEquals("reports/result.json", reference.relativePath)
        listOf(
            "/etc/passwd",
            "../secret",
            "reports/../secret",
            "reports//result.json",
            "reports/./result.json",
            "C:/secret.txt",
            "reports\\result.json",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) {
                RemoteFileReference("/workspace/project", value)
            }
        }
    }

    @Test
    fun revisionRejectsMalformedChecksums() {
        assertFailsWith<IllegalArgumentException> {
            RemoteFileRevision(
                sizeBytes = 1,
                modifiedAtEpochMillis = 2,
                sha256 = "not-a-checksum",
            )
        }
    }
}
