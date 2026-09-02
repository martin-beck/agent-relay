package dev.agentrelay.speech.android

import dev.agentrelay.speech.api.MAX_SPEECH_PLAYBACK_CHARS
import dev.agentrelay.speech.api.SpeechModelAvailability
import dev.agentrelay.speech.api.SpeechModelCapability
import dev.agentrelay.speech.api.SpeechModelDescriptor
import dev.agentrelay.speech.api.SpeechModelId
import dev.agentrelay.speech.api.SpeechModelLicense
import dev.agentrelay.speech.api.SpeechModelPackage
import dev.agentrelay.speech.api.SpeechOperationId
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidSpeechModelStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun verifiedPackageIsActivatedResolvedAndRestored() = runTest {
        val root = temporaryFolder.newFolder("models")
        val payload = "verified-package".encodeToByteArray()
        val descriptor = descriptor(payload)
        val staleStaging = File(root, ".install-${descriptor.id}-crashed")
        assertTrue(staleStaging.mkdir())
        File(staleStaging, "partial").writeText("partial")
        val store = store(
            root = root,
            descriptor = descriptor,
            downloader = SpeechPackageDownloader { observed, destination ->
                assertEquals(descriptor, observed)
                destination.write(payload)
            },
            extractor = SpeechPackageExtractor { verifiedPackage, destination ->
                assertContentEquals(payload, verifiedPackage.readBytes())
                destination.createDirectory("acoustic")
                destination.writeFile(
                    "acoustic/model.onnx",
                    ByteArrayInputStream(byteArrayOf(1, 2, 3)),
                )
            },
        )

        store.install(descriptor.id)

        assertIs<SpeechModelAvailability.Ready>(availability(store))
        val installed = assertNotNull(
            store.resolve(descriptor.id, SpeechModelCapability.TRANSCRIPTION),
        )
        assertTrue(installed.directory.isDirectory)
        assertEquals(descriptor, installed.descriptor)
        assertNull(store.resolve(descriptor.id, SpeechModelCapability.SYNTHESIS))
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".install-") })

        val restored = store(
            root = root,
            descriptor = descriptor,
            downloader = unexpectedDownloader(),
            extractor = unexpectedExtractor(),
        )
        assertIs<SpeechModelAvailability.Ready>(availability(restored))
        assertNotNull(restored.resolve(descriptor.id, SpeechModelCapability.TRANSCRIPTION))

        restored.remove(descriptor.id)
        assertIs<SpeechModelAvailability.NotInstalled>(availability(restored))
        assertTrue(root.listFiles().orEmpty().none { it.name.startsWith("${descriptor.id}.") })
    }

    @Test
    fun restartRejectsReadyMarkerWithoutModelPayload() = runTest {
        val root = temporaryFolder.newFolder("missing-payload")
        val payload = "verified-package".encodeToByteArray()
        val descriptor = descriptor(payload)
        val installed = store(
            root,
            descriptor,
            downloader = downloader(payload),
            extractor = modelExtractor(byteArrayOf(1)),
        )
        installed.install(descriptor.id)
        val active = assertNotNull(
            installed.resolve(descriptor.id, SpeechModelCapability.TRANSCRIPTION),
        ).directory
        assertTrue(File(active, "model.onnx").delete())

        val restored = store(
            root,
            descriptor,
            downloader = unexpectedDownloader(),
            extractor = unexpectedExtractor(),
        )

        assertIs<SpeechModelAvailability.NotInstalled>(availability(restored))
        assertNull(restored.resolve(descriptor.id, SpeechModelCapability.TRANSCRIPTION))
    }

    @Test
    fun checksumMismatchNeverReachesExtractorOrActivation() = runTest {
        val root = temporaryFolder.newFolder("checksum")
        val payload = "download".encodeToByteArray()
        val descriptor = descriptor(payload, checksum = "0".repeat(64))
        var extracted = false
        val store = store(
            root,
            descriptor,
            downloader = SpeechPackageDownloader { _, destination -> destination.write(payload) },
            extractor = SpeechPackageExtractor { _, _ -> extracted = true },
        )

        store.install(descriptor.id)

        assertEquals(
            "MODEL_CHECKSUM_MISMATCH",
            assertIs<SpeechModelAvailability.Failed>(availability(store)).failure.code,
        )
        assertFalse(extracted)
        assertNoInstallArtifacts(root, descriptor.id)
    }

    @Test
    fun truncatedAndOversizedDownloadsAreRejected() = runTest {
        val payload = "declared-download".encodeToByteArray()
        val descriptor = descriptor(payload)
        val truncatedRoot = temporaryFolder.newFolder("truncated")
        val truncated = store(
            truncatedRoot,
            descriptor,
            downloader = SpeechPackageDownloader { _, destination ->
                destination.write(payload.copyOf(payload.size - 1))
            },
            extractor = unexpectedExtractor(),
        )

        truncated.install(descriptor.id)

        assertEquals(
            "MODEL_DOWNLOAD_INCOMPLETE",
            assertIs<SpeechModelAvailability.Failed>(availability(truncated)).failure.code,
        )
        assertNoInstallArtifacts(truncatedRoot, descriptor.id)

        val oversizedRoot = temporaryFolder.newFolder("oversized")
        val oversized = store(
            oversizedRoot,
            descriptor,
            downloader = SpeechPackageDownloader { _, destination ->
                destination.write(payload + 1)
            },
            extractor = unexpectedExtractor(),
        )

        oversized.install(descriptor.id)

        assertEquals(
            "MODEL_DOWNLOAD_INVALID",
            assertIs<SpeechModelAvailability.Failed>(availability(oversized)).failure.code,
        )
        assertNoInstallArtifacts(oversizedRoot, descriptor.id)
    }

    @Test
    fun storageCapacityIsCheckedBeforeDownload() = runTest {
        val root = temporaryFolder.newFolder("capacity")
        val payload = "model".encodeToByteArray()
        val descriptor = descriptor(payload, installedSizeBytes = 64L)
        var downloaded = false
        val store = store(
            root,
            descriptor,
            downloader = SpeechPackageDownloader { _, _ -> downloaded = true },
            extractor = unexpectedExtractor(),
            capacity = payload.size.toLong() + 63L,
        )

        store.install(descriptor.id)

        assertEquals(
            "MODEL_STORAGE_FULL",
            assertIs<SpeechModelAvailability.Failed>(availability(store)).failure.code,
        )
        assertFalse(downloaded)
        assertNoInstallArtifacts(root, descriptor.id)
    }

    @Test
    fun traversalAndOversizedExtractedTreesAreRejected() = runTest {
        val payload = "archive".encodeToByteArray()
        val descriptor = descriptor(payload, installedSizeBytes = 8L)
        val traversalRoot = temporaryFolder.newFolder("traversal")
        val traversal = store(
            traversalRoot,
            descriptor,
            downloader = downloader(payload),
            extractor = SpeechPackageExtractor { _, destination ->
                destination.writeFile("../escaped", ByteArrayInputStream(byteArrayOf(1)))
            },
        )

        traversal.install(descriptor.id)

        assertEquals(
            "MODEL_PACKAGE_UNSAFE",
            assertIs<SpeechModelAvailability.Failed>(availability(traversal)).failure.code,
        )
        assertNoInstallArtifacts(traversalRoot, descriptor.id)

        val largeRoot = temporaryFolder.newFolder("large")
        val large = store(
            largeRoot,
            descriptor,
            downloader = downloader(payload),
            extractor = SpeechPackageExtractor { _, destination ->
                destination.writeFile("model.onnx", ByteArrayInputStream(ByteArray(9)))
            },
        )

        large.install(descriptor.id)

        assertEquals(
            "MODEL_PACKAGE_TOO_LARGE",
            assertIs<SpeechModelAvailability.Failed>(availability(large)).failure.code,
        )
        assertNoInstallArtifacts(largeRoot, descriptor.id)
    }

    @Test
    fun cancellationCleansPartialPackageAndReturnsToNotInstalled() = runTest {
        val root = temporaryFolder.newFolder("cancel")
        val payload = "cancel-me".encodeToByteArray()
        val descriptor = descriptor(payload)
        val enteredDownload = CompletableDeferred<Unit>()
        val store = store(
            root,
            descriptor,
            downloader = SpeechPackageDownloader { _, destination ->
                destination.write(payload, 0, 1)
                enteredDownload.complete(Unit)
                awaitCancellation()
            },
            extractor = unexpectedExtractor(),
        )
        val install = launch {
            store.install(descriptor.id)
        }
        enteredDownload.await()
        assertEquals(
            1L,
            assertIs<SpeechModelAvailability.Downloading>(availability(store)).downloadedBytes,
        )

        store.cancelInstall(descriptor.id)
        install.join()

        assertIs<SpeechModelAvailability.NotInstalled>(availability(store))
        assertNoInstallArtifacts(root, descriptor.id)
    }

    @Test
    fun cancellationRejectsLateWritesFromNonCooperativeDownloader() = runTest {
        val root = temporaryFolder.newFolder("late-download")
        val payload = "late-package".encodeToByteArray()
        val descriptor = descriptor(payload)
        val enteredDownload = CompletableDeferred<Unit>()
        val releaseDownload = CompletableDeferred<Unit>()
        val store = store(
            root,
            descriptor,
            downloader = SpeechPackageDownloader { _, destination ->
                enteredDownload.complete(Unit)
                withContext(NonCancellable) {
                    releaseDownload.await()
                    destination.write(payload)
                }
            },
            extractor = unexpectedExtractor(),
        )
        val install = launch {
            store.install(descriptor.id)
        }
        enteredDownload.await()
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            store.cancelInstall(descriptor.id)
        }

        releaseDownload.complete(Unit)
        cancellation.join()
        install.join()

        assertIs<SpeechModelAvailability.NotInstalled>(availability(store))
        assertNoInstallArtifacts(root, descriptor.id)
    }

    @Test
    fun cancellationStopsAReadThatReturnsAfterTheExtractorIgnoresCancellation() = runTest {
        val root = temporaryFolder.newFolder("late-extraction")
        val payload = "verified-package".encodeToByteArray()
        val descriptor = descriptor(payload)
        val enteredRead = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        val readCalls = AtomicInteger()
        val delayedSource = object : InputStream() {
            override fun read(): Int = error("Bulk reads are required")

            override fun read(
                buffer: ByteArray,
                offset: Int,
                length: Int,
            ): Int {
                check(length > 0)
                val call = readCalls.incrementAndGet()
                if (call > 1) {
                    return -1
                }
                enteredRead.complete(Unit)
                runBlocking(NonCancellable) {
                    releaseRead.await()
                }
                buffer[offset] = 1
                return 1
            }
        }
        val store = store(
            root,
            descriptor,
            downloader = downloader(payload),
            extractor = SpeechPackageExtractor { _, destination ->
                destination.writeFile("model.onnx", delayedSource)
            },
            dispatcher = Dispatchers.IO,
        )
        val install = launch {
            store.install(descriptor.id)
        }
        enteredRead.await()
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            store.cancelInstall(descriptor.id)
        }

        releaseRead.complete(Unit)
        cancellation.join()
        install.join()

        assertTrue(install.isCancelled)
        assertTrue(cancellation.isCompleted && !cancellation.isCancelled)
        assertEquals(1, readCalls.get())
        assertIs<SpeechModelAvailability.NotInstalled>(availability(store))
        assertNoInstallArtifacts(root, descriptor.id)
    }

    @Test
    fun replacementActivatesNewChecksumBeforeRemovingOldDirectory() = runTest {
        val root = temporaryFolder.newFolder("replace")
        val firstPayload = "first-package".encodeToByteArray()
        val first = descriptor(firstPayload)
        store(
            root,
            first,
            downloader = downloader(firstPayload),
            extractor = modelExtractor(byteArrayOf(1)),
        ).install(first.id)
        val firstDirectory = root.listFiles().orEmpty().single { it.name.startsWith("${first.id}.") }

        val secondPayload = "other-package".encodeToByteArray()
        val second = descriptor(secondPayload)
        val replacement = store(
            root,
            second,
            downloader = downloader(secondPayload),
            extractor = modelExtractor(byteArrayOf(2)),
        )
        assertIs<SpeechModelAvailability.NotInstalled>(availability(replacement))

        replacement.install(second.id)

        assertIs<SpeechModelAvailability.Ready>(availability(replacement))
        val active = assertNotNull(
            replacement.resolve(second.id, SpeechModelCapability.TRANSCRIPTION),
        ).directory
        assertFalse(firstDirectory.exists())
        assertTrue(active.name.endsWith(second.modelPackage.sha256))
        assertContentEquals(byteArrayOf(2), File(active, "model.onnx").readBytes())
    }

    @Test
    fun closedStoreRejectsNewWork() = runTest {
        val payload = "model".encodeToByteArray()
        val descriptor = descriptor(payload)
        val store = store(
            temporaryFolder.newFolder("closed"),
            descriptor,
            downloader = unexpectedDownloader(),
            extractor = unexpectedExtractor(),
        )

        store.close()
        store.close()

        assertFailsWith<IllegalStateException> {
            store.install(descriptor.id)
        }
    }

    @Test
    fun audioAndInferenceBoundaryPayloadsAreBounded() {
        assertFailsWith<IllegalArgumentException> {
            SpeechPcmFormat(sampleRateHz = 7_999, channelCount = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            SpeechPcmFormat(sampleRateHz = 16_000, channelCount = 3)
        }
        val format = SpeechPcmFormat(sampleRateHz = 16_000, channelCount = 1)
        assertFailsWith<IllegalArgumentException> {
            SpeechPcmFrame(format, shortArrayOf())
        }
        assertFailsWith<IllegalArgumentException> {
            SpeechPcmFrame(format, ShortArray(16_001))
        }
        assertFailsWith<IllegalArgumentException> {
            InstalledSpeechModel(
                descriptor = descriptor("model".encodeToByteArray()),
                directory = File("relative"),
            )
        }

        val installed = InstalledSpeechModel(
            descriptor = descriptor("model".encodeToByteArray()),
            directory = temporaryFolder.root.absoluteFile,
        )
        assertFailsWith<IllegalArgumentException> {
            SpeechSynthesisRequest(SpeechOperationId(1L), installed, " ")
        }
        assertFailsWith<IllegalArgumentException> {
            SpeechSynthesisRequest(
                SpeechOperationId(1L),
                installed,
                "x".repeat(MAX_SPEECH_PLAYBACK_CHARS + 1),
            )
        }
    }

    private fun store(
        root: File,
        descriptor: SpeechModelDescriptor,
        downloader: SpeechPackageDownloader,
        extractor: SpeechPackageExtractor,
        capacity: Long = Long.MAX_VALUE,
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
    ) = AndroidSpeechModelStore(
        root = root,
        catalog = listOf(descriptor),
        downloader = downloader,
        extractor = extractor,
        storageCapacity = SpeechStorageCapacity { capacity },
        dispatcher = dispatcher,
    )

    private fun availability(store: AndroidSpeechModelStore): SpeechModelAvailability =
        store.models.value.single().availability

    private fun downloader(payload: ByteArray) =
        SpeechPackageDownloader { _, destination -> destination.write(payload) }

    private fun modelExtractor(payload: ByteArray) =
        SpeechPackageExtractor { _, destination ->
            destination.writeFile("model.onnx", ByteArrayInputStream(payload))
        }

    private fun unexpectedDownloader() = SpeechPackageDownloader { _, _ ->
        error("Download should not be attempted")
    }

    private fun unexpectedExtractor() = SpeechPackageExtractor { _, _ ->
        error("Extraction should not be attempted")
    }

    private fun assertNoInstallArtifacts(
        root: File,
        modelId: SpeechModelId,
    ) {
        assertTrue(
            root.listFiles()
                .orEmpty()
                .none { it.name.startsWith(".install-") || it.name.startsWith("$modelId.") },
        )
    }

    private fun descriptor(
        payload: ByteArray,
        checksum: String = sha256(payload),
        installedSizeBytes: Long = 128L,
    ) = SpeechModelDescriptor(
        id = SpeechModelId("test-model"),
        displayName = "Test model",
        version = "2026.09",
        languageTags = setOf("en-US"),
        capabilities = setOf(SpeechModelCapability.TRANSCRIPTION),
        license = SpeechModelLicense(
            name = "Apache License 2.0",
            spdxIdentifier = "Apache-2.0",
            url = "https://licenses.example/Apache-2.0",
        ),
        modelPackage = SpeechModelPackage(
            downloadUrl = "https://models.example/model.package",
            sha256 = checksum,
            downloadSizeBytes = payload.size.toLong(),
            installedSizeBytes = installedSizeBytes,
        ),
    )

    private fun sha256(payload: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }
}
