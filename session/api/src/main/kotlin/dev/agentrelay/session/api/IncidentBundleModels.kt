package dev.agentrelay.session.api

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class SelfCheckKind { STORAGE, JOURNAL, CONNECTION, PROVIDER, POLICY, EXECUTION }

enum class SelfCheckStatus { HEALTHY, DEGRADED, FAILED, UNAVAILABLE }

data class SelfCheckResult(
    val kind: SelfCheckKind,
    val status: SelfCheckStatus,
    val checkedAtMillis: Long,
    val invariantCount: Int,
    val failedInvariantCount: Int,
    val evidenceDigest: String?,
) {
    init {
        require(checkedAtMillis >= 0)
        require(invariantCount in 0..MAX_INVARIANTS)
        require(failedInvariantCount in 0..invariantCount)
        evidenceDigest?.let { require(it.matches(HEX_DIGEST_PATTERN)) }
    }
}

data class IncidentArtifactReference(val name: String, val sizeBytes: Long, val sha256: String) {
    init {
        require(name.matches(ARTIFACT_NAME_PATTERN))
        require(sizeBytes in 0..MAX_ARTIFACT_BYTES)
        require(sha256.matches(HEX_DIGEST_PATTERN))
    }
}

data class IncidentBundleManifest(
    val schemaVersion: Int,
    val sourceRevision: String,
    val generatedAtMillis: Long,
    val environmentFingerprint: String,
    val evidenceRecords: List<String>,
    val selfChecks: List<SelfCheckResult>,
    val artifacts: List<IncidentArtifactReference>,
    val omittedEvidenceCount: Long,
    val redactionPolicy: String,
) {
    init {
        require(schemaVersion == 1)
        require(sourceRevision.matches(SOURCE_REVISION_PATTERN))
        require(generatedAtMillis >= 0)
        require(environmentFingerprint.matches(HEX_DIGEST_PATTERN))
        require(evidenceRecords.size <= MAX_EVIDENCE_RECORDS)
        require(evidenceRecords.all { it.matches(HEX_DIGEST_PATTERN) })
        require(selfChecks.size <= SelfCheckKind.entries.size)
        require(selfChecks.map(SelfCheckResult::kind).toSet().size == selfChecks.size)
        require(artifacts.size <= MAX_ARTIFACTS)
        require(omittedEvidenceCount >= 0)
        require(redactionPolicy == REDACTION_POLICY)
    }

    fun canonicalBytes(): ByteArray = buildString {
        append(schemaVersion).append('|').append(sourceRevision).append('|')
        append(generatedAtMillis).append('|').append(environmentFingerprint).append('|')
        append(evidenceRecords.sorted().joinToString(",")).append('|')
        selfChecks.sortedBy { it.kind.name }.forEach {
            append(it.kind).append(':').append(it.status).append(':').append(it.checkedAtMillis)
                .append(':').append(it.invariantCount).append(':').append(it.failedInvariantCount)
                .append(':').append(it.evidenceDigest ?: "-").append(';')
        }
        append('|').append(artifacts.sortedBy { it.name }.joinToString(",") { "${it.name}:${it.sizeBytes}:${it.sha256}" })
        append('|').append(omittedEvidenceCount).append('|').append(redactionPolicy)
    }.toByteArray(StandardCharsets.UTF_8)
}

data class IncidentBundleAuthorization(val explicitlyAuthorized: Boolean, val oneDocumentGrant: Boolean) {
    init {
        require(explicitlyAuthorized || !oneDocumentGrant) { "Document grant cannot bypass explicit authorization" }
    }
}

data class IncidentBundle(
    val manifest: IncidentBundleManifest,
    val nonce: String,
    val ciphertext: String,
    val integrityDigest: String,
) {
    init {
        require(nonce.matches(BASE64_PATTERN))
        require(ciphertext.matches(BASE64_PATTERN))
        require(integrityDigest.matches(HEX_DIGEST_PATTERN))
    }
}

class IncidentBundleBuilder(private val key: ByteArray) {
    init {
        require(key.size == 32) { "Incident bundle key must be 256-bit" }
    }

    fun build(
        manifest: IncidentBundleManifest,
        authorization: IncidentBundleAuthorization,
        nonce: ByteArray,
    ): IncidentBundle {
        require(authorization.explicitlyAuthorized && authorization.oneDocumentGrant) {
            "Incident export requires explicit authorization and one-document grant"
        }
        require(nonce.size == GCM_NONCE_BYTES)
        val plaintext = manifest.canonicalBytes()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext)
        val encodedNonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
        val encodedCiphertext = Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext)
        return IncidentBundle(
            manifest,
            encodedNonce,
            encodedCiphertext,
            sha256(manifest.canonicalBytes() + ciphertext),
        )
    }

    fun verify(bundle: IncidentBundle): Boolean {
        val nonce = Base64.getUrlDecoder().decode(bundle.nonce)
        val ciphertext = Base64.getUrlDecoder().decode(bundle.ciphertext)
        return sha256(bundle.manifest.canonicalBytes() + ciphertext) == bundle.integrityDigest && runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
            cipher.doFinal(ciphertext).contentEquals(bundle.manifest.canonicalBytes())
        }.getOrDefault(false)
    }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }

private const val MAX_INVARIANTS = 10_000
private const val MAX_EVIDENCE_RECORDS = 2_000
private const val MAX_ARTIFACTS = 128
private const val MAX_ARTIFACT_BYTES = 1_073_741_824L
private const val GCM_NONCE_BYTES = 12
private const val GCM_TAG_BITS = 128
private const val REDACTION_POLICY = "manifest-only:no-prompts:no-transcripts:no-credentials:no-routes:no-paths:no-commands"
private val SOURCE_REVISION_PATTERN = Regex("[0-9a-f]{7,64}")
private val HEX_DIGEST_PATTERN = Regex("[0-9a-f]{64}")
private val BASE64_PATTERN = Regex("[A-Za-z0-9_-]+")
private val ARTIFACT_NAME_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,127}")
