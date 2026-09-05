package dev.agentrelay.session.api

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IncidentBundleModelsTest {
    private val manifest = IncidentBundleManifest(
        schemaVersion = 1,
        sourceRevision = "f5a69fca1c2",
        generatedAtMillis = 100,
        environmentFingerprint = digest("environment"),
        evidenceRecords = listOf(digest("event-1"), digest("event-2")),
        selfChecks = listOf(
            SelfCheckResult(SelfCheckKind.CONNECTION, SelfCheckStatus.HEALTHY, 99, 4, 0, digest("check")),
            SelfCheckResult(SelfCheckKind.POLICY, SelfCheckStatus.DEGRADED, 99, 3, 1, null),
        ),
        artifacts = listOf(IncidentArtifactReference("journal.sha256", 64, digest("journal"))),
        omittedEvidenceCount = 2,
        redactionPolicy = "manifest-only:no-prompts:no-transcripts:no-credentials:no-routes:no-paths:no-commands",
    )

    @Test
    fun authorizedBundleGenerationIsDeterministicAndIntegrityVerifiable() {
        val builder = IncidentBundleBuilder(ByteArray(32) { 7 })
        val authorization = IncidentBundleAuthorization(true, true)
        val first = builder.build(manifest, authorization, ByteArray(12) { 3 })
        val second = builder.build(manifest, authorization, ByteArray(12) { 3 })
        assertTrue(first.ciphertext == second.ciphertext)
        assertTrue(builder.verify(first))
        assertTrue(builder.verify(second))
    }

    @Test
    fun missingAuthorizationAndTamperingFailClosed() {
        val builder = IncidentBundleBuilder(ByteArray(32) { 7 })
        assertFailsWith<IllegalArgumentException> {
            builder.build(manifest, IncidentBundleAuthorization(false, false), ByteArray(12))
        }
        val bundle = builder.build(manifest, IncidentBundleAuthorization(true, true), ByteArray(12))
        val tampered = bundle.copy(ciphertext = bundle.ciphertext.dropLast(1) + "A")
        assertFalse(builder.verify(tampered))
    }

    @Test
    fun forbiddenManifestFieldsAndOversizedEvidenceAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            IncidentArtifactReference("/secret/path", 1, digest("x"))
        }
        assertFailsWith<IllegalArgumentException> {
            manifest.copy(evidenceRecords = List(2_001) { digest("x$it") })
        }
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
