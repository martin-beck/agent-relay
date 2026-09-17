/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.speech.api

import java.io.Closeable
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlinx.coroutines.flow.StateFlow

const val MAX_SPEECH_TRANSCRIPT_CHARS = 32_000
const val MAX_SPEECH_PLAYBACK_CHARS = 64_000

@JvmInline
value class SpeechModelId(val value: String) {
    init {
        require(value.matches(Regex("[a-z][a-z0-9._-]{1,63}"))) {
            "Speech model id must be stable and lowercase"
        }
    }

    override fun toString(): String = value
}

@JvmInline
value class SpeechOperationId(val value: Long) {
    init {
        require(value > 0L) { "Speech operation id must be positive" }
    }
}

enum class SpeechModelCapability {
    TRANSCRIPTION,
    SYNTHESIS,
}

data class SpeechModelLicense(
    val name: String,
    val spdxIdentifier: String?,
    val url: String,
) {
    init {
        require(name.isNotBlank()) { "Speech model license name must not be blank" }
        require(spdxIdentifier == null || spdxIdentifier.matches(Regex("[A-Za-z0-9.+-]{1,64}"))) {
            "Speech model SPDX identifier is invalid"
        }
        requireHttpsUrl(url, "Speech model license URL")
    }
}

data class SpeechModelPackage(
    val downloadUrl: String,
    val sha256: String,
    val downloadSizeBytes: Long,
    val installedSizeBytes: Long,
) {
    init {
        requireHttpsUrl(downloadUrl, "Speech model download URL")
        require(sha256.matches(Regex("[a-f0-9]{64}"))) {
            "Speech model package checksum must be lowercase SHA-256"
        }
        require(downloadSizeBytes > 0L) { "Speech model download size must be positive" }
        require(installedSizeBytes > 0L) { "Speech model installed size must be positive" }
    }
}

data class SpeechModelDescriptor(
    val id: SpeechModelId,
    val displayName: String,
    val version: String,
    val languageTags: Set<String>,
    val capabilities: Set<SpeechModelCapability>,
    val license: SpeechModelLicense,
    val modelPackage: SpeechModelPackage,
) {
    init {
        require(displayName.isNotBlank()) { "Speech model display name must not be blank" }
        require(version.isNotBlank()) { "Speech model version must not be blank" }
        require(languageTags.isNotEmpty()) { "Speech model must declare a language" }
        require(languageTags.size <= 64) { "Speech model declares too many languages" }
        require(languageTags.all { it.matches(LANGUAGE_TAG_PATTERN) }) {
            "Speech model language tags must use BCP-47 syntax"
        }
        require(capabilities.isNotEmpty()) { "Speech model must declare a capability" }
    }

    private companion object {
        val LANGUAGE_TAG_PATTERN = Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*")
    }
}

data class SpeechFailure(
    val code: String,
    val actionableMessage: String,
) {
    init {
        require(code.matches(Regex("[A-Z][A-Z0-9_]{2,63}"))) {
            "Speech failure code must be stable and redacted"
        }
        require(actionableMessage.isNotBlank()) { "Speech failure message must not be blank" }
    }
}

sealed interface SpeechModelAvailability {
    data object NotInstalled : SpeechModelAvailability

    data class Downloading(
        val downloadedBytes: Long,
        val totalBytes: Long,
    ) : SpeechModelAvailability {
        init {
            require(totalBytes > 0L) { "Speech model download total must be positive" }
            require(downloadedBytes in 0L..totalBytes) {
                "Speech model download progress is invalid"
            }
        }
    }

    data object Ready : SpeechModelAvailability

    data class Failed(val failure: SpeechFailure) : SpeechModelAvailability
}

data class SpeechModelState(
    val descriptor: SpeechModelDescriptor,
    val availability: SpeechModelAvailability,
)

/** A catalog entry authenticated by the release key, not by the transport alone. */
data class SignedSpeechModelCatalogEntry(
    val descriptor: SpeechModelDescriptor,
    val signingKeyId: String,
    val signatureBase64: String,
) {
    init {
        require(signingKeyId.matches(Regex("[a-z0-9._-]{1,64}"))) {
            "Speech catalog signing key id is invalid"
        }
        require(signatureBase64.matches(Regex("[A-Za-z0-9+/]{1,4096}={0,2}"))) {
            "Speech catalog signature is invalid"
        }
    }
}

/**
 * Admission gate for a small, reviewed model catalog. The signed payload is deterministic and
 * contains metadata only; no prompt, transcript, endpoint credential, or model bytes are signed.
 */
class SignedSpeechModelCatalog(
    entries: List<SignedSpeechModelCatalogEntry>,
    trustedPublicKeys: Map<String, ByteArray>,
) {
    val models: List<SpeechModelDescriptor>

    init {
        require(entries.isNotEmpty()) { "Speech catalog must not be empty" }
        require(entries.map { it.descriptor.id }.toSet().size == entries.size) {
            "Speech catalog model ids must be unique"
        }
        require(trustedPublicKeys.isNotEmpty()) { "Speech catalog needs a trusted signing key" }
        entries.forEach { entry ->
            val encodedKey = requireNotNull(trustedPublicKeys[entry.signingKeyId]) {
                "Speech catalog entry uses an unknown signing key"
            }
            require(verify(entry, encodedKey)) {
                "Speech catalog entry signature is invalid"
            }
        }
        models = entries.map(SignedSpeechModelCatalogEntry::descriptor)
    }

    private fun verify(entry: SignedSpeechModelCatalogEntry, encodedKey: ByteArray): Boolean {
        return runCatching {
            val key = KeyFactory.getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(encodedKey))
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(canonicalPayload(entry.descriptor))
                verify(Base64.getDecoder().decode(entry.signatureBase64))
            }
        }.getOrDefault(false)
    }

    companion object {
        fun canonicalPayload(descriptor: SpeechModelDescriptor): ByteArray = listOf(
            descriptor.id.value,
            descriptor.displayName,
            descriptor.version,
            descriptor.languageTags.sorted().joinToString(","),
            descriptor.capabilities.sortedBy { it.name }.joinToString(",") { it.name },
            descriptor.license.name,
            descriptor.license.spdxIdentifier.orEmpty(),
            descriptor.license.url,
            descriptor.modelPackage.downloadUrl,
            descriptor.modelPackage.sha256,
            descriptor.modelPackage.downloadSizeBytes.toString(),
            descriptor.modelPackage.installedSizeBytes.toString(),
        ).joinToString("\n").toByteArray(StandardCharsets.UTF_8)
    }
}

sealed interface SpeechRecognitionState {
    data object Idle : SpeechRecognitionState

    data class Listening(
        val operationId: SpeechOperationId,
        val modelId: SpeechModelId,
        val startedAtEpochMillis: Long,
    ) : SpeechRecognitionState {
        init {
            require(startedAtEpochMillis >= 0L)
        }
    }

    data class Transcribing(
        val operationId: SpeechOperationId,
        val modelId: SpeechModelId,
    ) : SpeechRecognitionState

    data class Result(
        val operationId: SpeechOperationId,
        val modelId: SpeechModelId,
        val text: String,
    ) : SpeechRecognitionState {
        init {
            require(text.isNotBlank()) { "Speech transcript must not be blank" }
            require(text.length <= MAX_SPEECH_TRANSCRIPT_CHARS) { "Speech transcript is too large" }
        }
    }

    data class Failed(
        val operationId: SpeechOperationId,
        val modelId: SpeechModelId,
        val failure: SpeechFailure,
    ) : SpeechRecognitionState
}

sealed interface SpeechPlaybackState {
    data object Idle : SpeechPlaybackState

    data class Synthesizing(
        val operationId: SpeechOperationId,
        val modelId: SpeechModelId,
        val characterCount: Int,
    ) : SpeechPlaybackState {
        init {
            require(characterCount in 1..MAX_SPEECH_PLAYBACK_CHARS) {
                "Speech playback text size is invalid"
            }
        }
    }

    data class Playing(
        val operationId: SpeechOperationId,
        val modelId: SpeechModelId,
    ) : SpeechPlaybackState

    data class Failed(
        val operationId: SpeechOperationId,
        val modelId: SpeechModelId,
        val failure: SpeechFailure,
    ) : SpeechPlaybackState
}

/**
 * Provider-neutral boundary for fully on-device speech.
 *
 * Operation ids make stop/cancel calls generation-safe: an obsolete UI event cannot stop a
 * replacement capture or playback operation.
 */
interface OfflineSpeechService : Closeable {
    val models: StateFlow<List<SpeechModelState>>
    val recognition: StateFlow<SpeechRecognitionState>
    val playback: StateFlow<SpeechPlaybackState>

    suspend fun installModel(modelId: SpeechModelId)

    suspend fun cancelModelInstall(modelId: SpeechModelId)

    suspend fun removeModel(modelId: SpeechModelId)

    suspend fun startListening(modelId: SpeechModelId): SpeechOperationId

    suspend fun stopListening(operationId: SpeechOperationId)

    suspend fun cancelListening(operationId: SpeechOperationId)

    suspend fun speak(modelId: SpeechModelId, text: String): SpeechOperationId

    suspend fun stopSpeaking(operationId: SpeechOperationId)
}

private fun requireHttpsUrl(value: String, label: String) {
    val uri = runCatching { URI(value) }.getOrNull()
    require(uri != null && uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null) {
        "$label must be an HTTPS URL without user information"
    }
}
