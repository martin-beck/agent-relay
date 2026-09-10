/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.speech.android

import dev.agentrelay.speech.api.SpeechModelDescriptor
import dev.agentrelay.speech.api.SpeechModelId
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Comparator
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Owns checksum-bound partial packages independently from extraction and activation staging.
 */
internal class DurableSpeechPackageDownload(
    private val root: File,
    private val downloader: SpeechPackageDownloader,
) {
    fun persistedBytes(descriptor: SpeechModelDescriptor): Long =
        validPartialPackage(descriptor)?.length() ?: 0L

    fun remove(descriptor: SpeechModelDescriptor) {
        deleteTree(partialDirectory(descriptor))
    }

    fun remove(modelId: SpeechModelId) {
        partialDirectories(modelId).forEach(::deleteTree)
    }

    fun removeObsolete(descriptor: SpeechModelDescriptor) {
        val current = partialDirectory(descriptor)
        partialDirectories(descriptor.id).filterNot { it == current }.forEach(::deleteTree)
    }

    suspend fun download(
        descriptor: SpeechModelDescriptor,
        onProgress: (Long) -> Unit,
    ): File {
        val expectedBytes = descriptor.modelPackage.downloadSizeBytes
        val resumableDownloader = downloader as? ResumableSpeechPackageDownloader
        val existingPackage = validPartialPackage(descriptor)
        val target = preparePartialPackage(descriptor, existingPackage)
        val existingBytes = target.length()
        onProgress(existingBytes)
        if (existingBytes == expectedBytes) {
            return target
        }

        if (existingBytes > 0L && resumableDownloader != null) {
            try {
                writeDownload(descriptor, target, existingBytes, onProgress) { destination ->
                    when (resumableDownloader.resumeDownload(descriptor, existingBytes, destination)) {
                        SpeechPackageResumeResult.APPENDED -> Unit
                        SpeechPackageResumeResult.RESTART_REQUIRED ->
                            throw SpeechPackageRestartRequiredException()
                    }
                }
                return target
            } catch (_: SpeechPackageRestartRequiredException) {
                remove(descriptor)
                val restarted = preparePartialPackage(descriptor, existingPackage = null)
                writeDownload(descriptor, restarted, 0L, onProgress) { destination ->
                    downloader.download(descriptor, destination)
                }
                return restarted
            }
        }

        val fresh = if (existingBytes > 0L) {
            remove(descriptor)
            preparePartialPackage(descriptor, existingPackage = null)
        } else {
            target
        }
        try {
            writeDownload(descriptor, fresh, 0L, onProgress) { destination ->
                downloader.download(descriptor, destination)
            }
        } catch (failure: Throwable) {
            if (resumableDownloader == null) {
                remove(descriptor)
            }
            throw failure
        }
        return fresh
    }

    private suspend fun writeDownload(
        descriptor: SpeechModelDescriptor,
        target: File,
        initialBytes: Long,
        onProgress: (Long) -> Unit,
        transfer: suspend (OutputStream) -> Unit,
    ) {
        val expectedBytes = descriptor.modelPackage.downloadSizeBytes
        val installJob = checkNotNull(currentCoroutineContext()[Job]) {
            "Speech model installation requires a coroutine job"
        }
        FileOutputStream(target, initialBytes > 0L).use { fileOutput ->
            restrictToOwner(target)
            val output = ProgressOutputStream(
                expectedBytes = expectedBytes,
                initialBytes = initialBytes,
                delegate = fileOutput,
                installJob = installJob,
                onProgress = onProgress,
            )
            try {
                transfer(output)
                installJob.ensureActive()
                output.flush()
                fileOutput.fd.sync()
                output.requireComplete()
            } catch (failure: Throwable) {
                runCatching { fileOutput.fd.sync() }
                throw failure
            }
        }
        restrictToOwner(target)
    }

    private fun validPartialPackage(descriptor: SpeechModelDescriptor): File? {
        val directory = partialDirectory(descriptor)
        val directoryPath = directory.toPath()
        if (!Files.exists(directoryPath, LinkOption.NOFOLLOW_LINKS)) {
            return null
        }
        val packageFile = File(directory, PARTIAL_PACKAGE_FILE)
        val children = directory.listFiles()
        val valid = Files.isDirectory(directoryPath, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(directoryPath) &&
            children != null &&
            children.size == 1 &&
            children.single() == packageFile &&
            Files.isRegularFile(packageFile.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(packageFile.toPath()) &&
            packageFile.length() <= descriptor.modelPackage.downloadSizeBytes
        if (!valid) {
            deleteTree(directory)
            return null
        }
        restrictToOwner(directory)
        restrictToOwner(packageFile)
        return packageFile
    }

    private fun preparePartialPackage(
        descriptor: SpeechModelDescriptor,
        existingPackage: File?,
    ): File {
        if (existingPackage != null) {
            return existingPackage
        }
        val directory = partialDirectory(descriptor)
        if (!directory.mkdir()) {
            rejectStorage()
        }
        restrictToOwner(directory)
        val packageFile = File(directory, PARTIAL_PACKAGE_FILE)
        if (!packageFile.createNewFile()) {
            deleteTree(directory)
            throw SpeechModelInstallException(
                code = "MODEL_INSTALL_FAILED",
                guidance = "The speech model download could not be prepared. Retry it.",
            )
        }
        restrictToOwner(packageFile)
        return packageFile
    }

    private fun partialDirectory(descriptor: SpeechModelDescriptor): File =
        File(root, ".download-${descriptor.id.value}.${descriptor.modelPackage.sha256}")

    private fun partialDirectories(modelId: SpeechModelId): List<File> =
        root.listFiles()
            .orEmpty()
            .filter { it.name.startsWith(".download-${modelId.value}.") }

    private fun deleteTree(file: File) {
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            return
        }
        Files.walk(file.toPath()).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun restrictToOwner(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setExecutable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
        if (file.isDirectory) {
            file.setExecutable(true, true)
        }
    }

    private fun rejectStorage(): Nothing {
        throw SpeechModelInstallException(
            code = "MODEL_STORAGE_UNAVAILABLE",
            guidance = "App storage is unavailable. Retry after checking device storage.",
        )
    }

    private class ProgressOutputStream(
        private val expectedBytes: Long,
        initialBytes: Long,
        private val delegate: OutputStream,
        private val installJob: Job,
        private val onProgress: (Long) -> Unit,
    ) : OutputStream() {
        private var writtenBytes = initialBytes

        init {
            require(initialBytes in 0..expectedBytes) {
                "Speech download progress starts outside the declared package size"
            }
        }

        override fun write(value: Int) {
            ensureCapacity(1)
            delegate.write(value)
            progress(1)
        }

        override fun write(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ) {
            ensureCapacity(length)
            delegate.write(buffer, offset, length)
            progress(length)
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            flush()
        }

        fun requireComplete() {
            if (writtenBytes != expectedBytes) {
                throw SpeechModelInstallException(
                    code = "MODEL_DOWNLOAD_INCOMPLETE",
                    guidance = "The speech model download was incomplete. Retry it.",
                )
            }
        }

        private fun ensureCapacity(length: Int) {
            installJob.ensureActive()
            if (length < 0 || writtenBytes > expectedBytes - length) {
                throw SpeechModelInstallException(
                    code = "MODEL_DOWNLOAD_INVALID",
                    guidance = "The speech model download exceeded its declared size.",
                )
            }
        }

        private fun progress(length: Int) {
            writtenBytes += length
            onProgress(writtenBytes)
        }
    }

    private companion object {
        const val PARTIAL_PACKAGE_FILE = "model.package.partial"
    }
}

internal class SpeechModelInstallException(
    val code: String,
    val guidance: String,
) : Exception(guidance)
