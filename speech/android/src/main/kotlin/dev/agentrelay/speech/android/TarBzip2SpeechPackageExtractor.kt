package dev.agentrelay.speech.android

import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * Decodes the tar.bz2 packages published by the admitted offline-model catalog.
 *
 * Archive paths are normalized before they reach the store's independently confined extraction
 * sink. Links, sparse files, devices, pipes, malformed headers, and unknown entry kinds are
 * rejected instead of being materialized.
 */
class TarBzip2SpeechPackageExtractor : SpeechPackageExtractor {
    override suspend fun extract(
        verifiedPackage: File,
        destination: SpeechModelExtractionSink,
    ) {
        try {
            extractVerifiedPackage(verifiedPackage, destination)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SpeechPackageDeliveryException) {
            throw failure
        } catch (failure: IOException) {
            rejectUnsafePackage(failure)
        } catch (failure: IllegalArgumentException) {
            rejectUnsafePackage(failure)
        }
    }

    private suspend fun extractVerifiedPackage(
        verifiedPackage: File,
        destination: SpeechModelExtractionSink,
    ) {
        val job = checkNotNull(currentCoroutineContext()[Job]) {
            "Speech package extraction requires a coroutine job"
        }
        verifiedPackage.inputStream().buffered().use { encoded ->
            BZip2CompressorInputStream(encoded, false).use { compressed ->
                TarArchiveInputStream(compressed).use { archive ->
                    while (true) {
                        job.ensureActive()
                        val entry = archive.nextEntry ?: break
                        job.ensureActive()
                        extractEntry(archive, entry, destination)
                    }
                }
            }
        }
    }

    private fun extractEntry(
        archive: TarArchiveInputStream,
        entry: TarArchiveEntry,
        destination: SpeechModelExtractionSink,
    ) {
        if (!entry.isCheckSumOK || !archive.canReadEntryData(entry)) {
            rejectUnsafePackage()
        }
        if (entry.isSparse || !entry.isStreamContiguous) {
            rejectUnsafePackage()
        }
        if (entry.isLink || entry.isSymbolicLink) {
            rejectUnsafePackage()
        }
        if (entry.isBlockDevice || entry.isCharacterDevice || entry.isFIFO) {
            rejectUnsafePackage()
        }
        val relativePath = safeRelativePath(entry.name, entry.isDirectory)
        when {
            entry.isDirectory -> {
                if (entry.size != 0L) {
                    rejectUnsafePackage()
                }
                if (relativePath != null) {
                    destination.createDirectory(relativePath)
                }
            }
            entry.isFile -> {
                if (relativePath == null || entry.size < 0L) {
                    rejectUnsafePackage()
                }
                destination.writeFile(relativePath, archive)
            }
            else -> rejectUnsafePackage()
        }
    }
}

private fun safeRelativePath(
    rawName: String,
    directory: Boolean,
): String? {
    if (rawName.isBlank() || rawName.startsWith("/") || WINDOWS_ABSOLUTE_PATH.matches(rawName)) {
        rejectUnsafePackage()
    }
    if (rawName.contains('\\') || rawName.contains("//")) {
        rejectUnsafePackage()
    }
    if (rawName.any { character -> character.code < SPACE_CODE || character.code == DELETE_CODE }) {
        rejectUnsafePackage()
    }
    var normalized = rawName
    if (normalized.startsWith("./")) {
        normalized = normalized.removePrefix("./")
    }
    if (directory && normalized.endsWith("/")) {
        normalized = normalized.dropLast(1)
    }
    if (normalized.isEmpty()) {
        if (directory && rawName == "./") {
            return null
        }
        rejectUnsafePackage()
    }
    val segments = normalized.split('/')
    if (segments.any { segment -> segment.isEmpty() || segment == "." || segment == ".." }) {
        rejectUnsafePackage()
    }
    return normalized
}

private fun rejectUnsafePackage(cause: Throwable? = null): Nothing {
    throw SpeechPackageDeliveryException(
        code = "MODEL_PACKAGE_UNSAFE",
        guidance = "The speech model package is unsafe and was not installed.",
        cause = cause,
    )
}

private val WINDOWS_ABSOLUTE_PATH = Regex("[A-Za-z]:/.*")
private const val SPACE_CODE = 0x20
private const val DELETE_CODE = 0x7f
