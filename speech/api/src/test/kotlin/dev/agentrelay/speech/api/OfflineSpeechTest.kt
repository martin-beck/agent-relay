package dev.agentrelay.speech.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OfflineSpeechTest {
    @Test
    fun modelDescriptorCarriesAuditableDistributionMetadata() {
        val descriptor = testDescriptor()

        assertEquals(setOf("en-US", "de-DE"), descriptor.languageTags)
        assertEquals(
            setOf(SpeechModelCapability.TRANSCRIPTION),
            descriptor.capabilities,
        )
        assertEquals(42_000_000L, descriptor.modelPackage.downloadSizeBytes)
        assertEquals("Apache-2.0", descriptor.license.spdxIdentifier)
    }

    @Test
    fun modelIdentifiersRejectDisplayTextAndUnstableValues() {
        listOf("", "A", "English model", "../model", "UPPERCASE").forEach { value ->
            assertFailsWith<IllegalArgumentException> {
                SpeechModelId(value)
            }
        }
    }

    @Test
    fun modelPackageRequiresSafeSourceChecksumAndSizes() {
        assertFailsWith<IllegalArgumentException> {
            testPackage(downloadUrl = "http://models.example/model.tar.bz2")
        }
        assertFailsWith<IllegalArgumentException> {
            testPackage(downloadUrl = "https://user@models.example/model.tar.bz2")
        }
        assertFailsWith<IllegalArgumentException> {
            testPackage(sha256 = "not-a-checksum")
        }
        assertFailsWith<IllegalArgumentException> {
            testPackage(downloadSizeBytes = 0L)
        }
        assertFailsWith<IllegalArgumentException> {
            testPackage(installedSizeBytes = -1L)
        }
    }

    @Test
    fun descriptorRejectsMissingCapabilitiesAndInvalidLanguageTags() {
        assertFailsWith<IllegalArgumentException> {
            testDescriptor(capabilities = emptySet())
        }
        assertFailsWith<IllegalArgumentException> {
            testDescriptor(languageTags = emptySet())
        }
        assertFailsWith<IllegalArgumentException> {
            testDescriptor(languageTags = setOf("not_a_language"))
        }
    }

    @Test
    fun downloadProgressIsBoundedByDeclaredTotal() {
        assertEquals(
            40L,
            SpeechModelAvailability.Downloading(
                downloadedBytes = 40L,
                totalBytes = 100L,
            ).downloadedBytes,
        )
        assertFailsWith<IllegalArgumentException> {
            SpeechModelAvailability.Downloading(downloadedBytes = -1L, totalBytes = 100L)
        }
        assertFailsWith<IllegalArgumentException> {
            SpeechModelAvailability.Downloading(downloadedBytes = 101L, totalBytes = 100L)
        }
    }

    @Test
    fun operationIdsAndPayloadsAreBounded() {
        assertFailsWith<IllegalArgumentException> {
            SpeechOperationId(0L)
        }
        assertFailsWith<IllegalArgumentException> {
            SpeechRecognitionState.Listening(
                operationId = SpeechOperationId(1L),
                modelId = SpeechModelId("english-model"),
                startedAtEpochMillis = -1L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SpeechRecognitionState.Result(
                operationId = SpeechOperationId(1L),
                modelId = SpeechModelId("english-model"),
                text = " ",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SpeechRecognitionState.Result(
                operationId = SpeechOperationId(1L),
                modelId = SpeechModelId("english-model"),
                text = "a".repeat(MAX_SPEECH_TRANSCRIPT_CHARS + 1),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SpeechPlaybackState.Synthesizing(
                operationId = SpeechOperationId(1L),
                modelId = SpeechModelId("english-model"),
                characterCount = 0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SpeechPlaybackState.Synthesizing(
                operationId = SpeechOperationId(1L),
                modelId = SpeechModelId("english-model"),
                characterCount = MAX_SPEECH_PLAYBACK_CHARS + 1,
            )
        }
    }

    @Test
    fun failuresRequireRedactedStableCodesAndGuidance() {
        assertFailsWith<IllegalArgumentException> {
            SpeechFailure("download failed", "Retry the model download.")
        }
        assertFailsWith<IllegalArgumentException> {
            SpeechFailure("MODEL_DOWNLOAD_FAILED", " ")
        }
    }

    private fun testDescriptor(
        languageTags: Set<String> = setOf("en-US", "de-DE"),
        capabilities: Set<SpeechModelCapability> =
            setOf(SpeechModelCapability.TRANSCRIPTION),
    ) = SpeechModelDescriptor(
        id = SpeechModelId("test-model"),
        displayName = "Test model",
        version = "2026.09",
        languageTags = languageTags,
        capabilities = capabilities,
        license = SpeechModelLicense(
            name = "Apache License 2.0",
            spdxIdentifier = "Apache-2.0",
            url = "https://licenses.example/Apache-2.0",
        ),
        modelPackage = testPackage(),
    )

    private fun testPackage(
        downloadUrl: String = "https://models.example/model.tar.bz2",
        sha256: String = "a".repeat(64),
        downloadSizeBytes: Long = 42_000_000L,
        installedSizeBytes: Long = 80_000_000L,
    ) = SpeechModelPackage(
        downloadUrl = downloadUrl,
        sha256 = sha256,
        downloadSizeBytes = downloadSizeBytes,
        installedSizeBytes = installedSizeBytes,
    )
}
