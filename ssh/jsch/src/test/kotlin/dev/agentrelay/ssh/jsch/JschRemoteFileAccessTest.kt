package dev.agentrelay.ssh.jsch

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JschRemoteFileAccessTest {
    @Test
    fun canonicalPathNormalizationPreservesSegmentBoundaries() {
        assertEquals("/", normalizeCanonicalSftpPath("/"))
        assertEquals("/workspace/project", normalizeCanonicalSftpPath("//workspace//project/"))
        assertFailsWith<IllegalArgumentException> {
            normalizeCanonicalSftpPath("/workspace/../secret")
        }
        assertFailsWith<IllegalArgumentException> {
            normalizeCanonicalSftpPath("workspace/project")
        }
    }

    @Test
    fun workspaceConfinementDoesNotAcceptSiblingPrefixes() {
        assertTrue(isWithinSftpWorkspace("/workspace/project", "/workspace/project/result.txt"))
        assertTrue(isWithinSftpWorkspace("/", "/workspace/project/result.txt"))
        assertFalse(isWithinSftpWorkspace("/workspace/project", "/workspace/project-old/result.txt"))
        assertFalse(isWithinSftpWorkspace("/workspace/project", "/workspace/secret.txt"))
    }
}
