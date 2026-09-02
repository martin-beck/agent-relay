package dev.agentrelay.speech.android

import dev.agentrelay.speech.api.SpeechModelAvailability
import dev.agentrelay.speech.api.SpeechModelCapability
import dev.agentrelay.speech.api.SpeechModelDescriptor
import dev.agentrelay.speech.api.SpeechModelId
import dev.agentrelay.speech.api.SpeechModelLicense
import dev.agentrelay.speech.api.SpeechModelPackage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TarBzip2SpeechPackageExtractorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun conventionalRootDirectoriesAndFilesAreDecodedThroughTheSink() = runTest {
        val acoustic = byteArrayOf(1, 2, 3)
        val tokens = "hello".encodeToByteArray()
        val archive = archive(
            Entry("./", TarConstants.LF_DIR),
            Entry("./acoustic/", TarConstants.LF_DIR),
            Entry("./acoustic/model.onnx", payload = acoustic),
            Entry("./tokens.txt", payload = tokens),
        )
        val sink = RecordingExtractionSink()

        TarBzip2SpeechPackageExtractor().extract(packageFile(archive), sink)

        assertEquals(listOf("acoustic"), sink.directories)
        assertContentEquals(acoustic, sink.files.getValue("acoustic/model.onnx"))
        assertContentEquals(tokens, sink.files.getValue("tokens.txt"))
    }

    @Test
    fun traversalWindowsAndAmbiguousPathsAreRejected() = runTest {
        listOf(
            "../escape",
            "models/../../escape",
            "models\\escape",
            "models//model.onnx",
            "././model.onnx",
            "C:/model.onnx",
            "models/line\nfeed",
        ).forEachIndexed { index, path ->
            val failure = assertFailsWith<SpeechPackageDeliveryException> {
                TarBzip2SpeechPackageExtractor().extract(
                    packageFile(archive(Entry(path, payload = byteArrayOf(1))), "path-$index"),
                    RecordingExtractionSink(),
                )
            }
            assertEquals("MODEL_PACKAGE_UNSAFE", failure.code)
            assertFalse(failure.guidance.contains(path))
        }
    }

    @Test
    fun linksSparseEntriesDevicesAndPipesAreRejected() = runTest {
        listOf(
            TarConstants.LF_LINK,
            TarConstants.LF_SYMLINK,
            TarConstants.LF_GNUTYPE_SPARSE,
            TarConstants.LF_CHR,
            TarConstants.LF_BLK,
            TarConstants.LF_FIFO,
        ).forEachIndexed { index, type ->
            val failure = assertFailsWith<SpeechPackageDeliveryException> {
                TarBzip2SpeechPackageExtractor().extract(
                    packageFile(archive(Entry("special-$index", type)), "special-$index"),
                    RecordingExtractionSink(),
                )
            }
            assertEquals("MODEL_PACKAGE_UNSAFE", failure.code)
        }
    }

    @Test
    fun unknownEntryKindsAreRejected() = runTest {
        val failure = assertFailsWith<SpeechPackageDeliveryException> {
            TarBzip2SpeechPackageExtractor().extract(
                packageFile(archive(Entry("unknown", 'X'.code.toByte())), "unknown"),
                RecordingExtractionSink(),
            )
        }
        assertEquals("MODEL_PACKAGE_UNSAFE", failure.code)
    }

    @Test
    fun malformedBzip2AndTarPayloadsAreRejectedWithRedactedFailure() = runTest {
        val malformedTar = ByteArrayOutputStream().also { encoded ->
            BZip2CompressorOutputStream(encoded).use { compressed ->
                compressed.write(ByteArray(1_024) { 1 })
            }
        }.toByteArray()

        listOf(byteArrayOf(1, 2, 3), malformedTar).forEachIndexed { index, bytes ->
            val failure = assertFailsWith<SpeechPackageDeliveryException> {
                TarBzip2SpeechPackageExtractor().extract(
                    packageFile(bytes, "malformed-$index"),
                    RecordingExtractionSink(),
                )
            }
            assertEquals("MODEL_PACKAGE_UNSAFE", failure.code)
            assertEquals(
                "The speech model package is unsafe and was not installed.",
                failure.guidance,
            )
        }
    }

    @Test
    fun deliveryFailureCodeIsPreservedByTheModelStore() = runTest {
        val archive = byteArrayOf(1, 2, 3)
        val descriptor = descriptor(archive)
        val root = temporaryFolder.newFolder("store")
        val store = AndroidSpeechModelStore(
            root = root,
            catalog = listOf(descriptor),
            downloader = SpeechPackageDownloader { _, destination -> destination.write(archive) },
            extractor = TarBzip2SpeechPackageExtractor(),
            storageCapacity = SpeechStorageCapacity { Long.MAX_VALUE },
            dispatcher = Dispatchers.Unconfined,
        )

        store.install(descriptor.id)

        val failed = assertIs<SpeechModelAvailability.Failed>(
            store.models.value.single().availability,
        )
        assertEquals("MODEL_PACKAGE_UNSAFE", failed.failure.code)
        assertTrue(root.listFiles().orEmpty().none { file -> file.name.startsWith(".install-") })
    }

    private fun packageFile(
        bytes: ByteArray,
        name: String = "model",
    ): File = temporaryFolder.newFile("$name.tar.bz2").apply {
        writeBytes(bytes)
    }

    private fun descriptor(payload: ByteArray) = SpeechModelDescriptor(
        id = SpeechModelId("extractor-test"),
        displayName = "Extractor test",
        version = "1",
        languageTags = setOf("en-US"),
        capabilities = setOf(SpeechModelCapability.TRANSCRIPTION),
        license = SpeechModelLicense(
            name = "Apache License 2.0",
            spdxIdentifier = "Apache-2.0",
            url = "https://licenses.example/Apache-2.0",
        ),
        modelPackage = SpeechModelPackage(
            downloadUrl = "https://models.example/model.tar.bz2",
            sha256 = MessageDigest.getInstance("SHA-256")
                .digest(payload)
                .joinToString("") { byte -> "%02x".format(byte) },
            downloadSizeBytes = payload.size.toLong(),
            installedSizeBytes = 1024L,
        ),
    )
}

private data class Entry(
    val name: String,
    val type: Byte = TarConstants.LF_NORMAL,
    val payload: ByteArray = byteArrayOf(),
)

private fun archive(vararg entries: Entry): ByteArray {
    val encoded = ByteArrayOutputStream()
    BZip2CompressorOutputStream(encoded).use { compressed ->
        TarArchiveOutputStream(compressed).use { archive ->
            entries.forEach(archive::writeEntry)
        }
    }
    return encoded.toByteArray()
}

private fun TarArchiveOutputStream.writeEntry(item: Entry) {
    val entry = TarArchiveEntry(item.name, item.type).apply {
        size = item.payload.size.toLong()
        if (isLink || isSymbolicLink) {
            linkName = "target"
        }
    }
    putArchiveEntry(entry)
    write(item.payload)
    closeArchiveEntry()
}

private class RecordingExtractionSink : SpeechModelExtractionSink {
    val directories = mutableListOf<String>()
    val files = mutableMapOf<String, ByteArray>()

    override fun createDirectory(relativePath: String) {
        directories += relativePath
    }

    override fun writeFile(
        relativePath: String,
        source: InputStream,
    ) {
        files[relativePath] = source.readBytes()
    }
}
