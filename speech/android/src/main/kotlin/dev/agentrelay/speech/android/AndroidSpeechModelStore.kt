package dev.agentrelay.speech.android

import android.content.Context
import dev.agentrelay.speech.api.SpeechFailure
import dev.agentrelay.speech.api.SpeechModelAvailability
import dev.agentrelay.speech.api.SpeechModelCapability
import dev.agentrelay.speech.api.SpeechModelDescriptor
import dev.agentrelay.speech.api.SpeechModelId
import dev.agentrelay.speech.api.SpeechModelState
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.InvalidPathException
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Comparator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class AndroidSpeechModelStore internal constructor(
    private val root: File,
    catalog: List<SpeechModelDescriptor>,
    downloader: SpeechPackageDownloader,
    private val extractor: SpeechPackageExtractor,
    private val storageCapacity: SpeechStorageCapacity,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SpeechModelStore {
    private val modelOrder = catalog.map(SpeechModelDescriptor::id)
    private val descriptors = catalog.associateBy(SpeechModelDescriptor::id)
    private val packageDownloads = DurableSpeechPackageDownload(root, downloader)
    private val stateMonitor = Any()
    private val activeInstallMonitor = Any()
    private val activeInstalls = mutableMapOf<SpeechModelId, Job>()
    private var closed = false
    private val availability = descriptors.mapValues { (_, descriptor) ->
        if (isReadyDirectory(descriptor)) {
            SpeechModelAvailability.Ready
        } else {
            SpeechModelAvailability.NotInstalled
        }
    }.toMutableMap()
    private val mutableModels = MutableStateFlow(currentStates())

    init {
        require(catalog.isNotEmpty()) { "Speech model catalog must not be empty" }
        require(descriptors.size == catalog.size) { "Speech model catalog ids must be unique" }
        require(root.isAbsolute) { "Speech model root must be absolute" }
    }

    constructor(
        context: Context,
        catalog: List<SpeechModelDescriptor>,
        downloader: SpeechPackageDownloader,
        extractor: SpeechPackageExtractor,
        storageCapacity: SpeechStorageCapacity = SpeechStorageCapacity(File::getUsableSpace),
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        root = File(context.noBackupFilesDir, MODEL_DIRECTORY),
        catalog = catalog,
        downloader = downloader,
        extractor = extractor,
        storageCapacity = storageCapacity,
        dispatcher = dispatcher,
    )

    override val models: StateFlow<List<SpeechModelState>> = mutableModels.asStateFlow()

    override suspend fun install(modelId: SpeechModelId) {
        val descriptor = descriptor(modelId)
        val job = checkNotNull(currentCoroutineContext()[Job]) {
            "Speech model installation requires a coroutine job"
        }
        val shouldInstall = synchronized(activeInstallMonitor) {
            checkOpen()
            when {
                availability(modelId) == SpeechModelAvailability.Ready -> false
                activeInstalls.containsKey(modelId) -> false
                else -> {
                    activeInstalls[modelId] = job
                    updateAvailability(
                        modelId,
                        SpeechModelAvailability.Downloading(
                            downloadedBytes = 0L,
                            totalBytes = descriptor.modelPackage.downloadSizeBytes,
                        ),
                    )
                    true
                }
            }
        }
        if (!shouldInstall) {
            return
        }
        var wasCancelled = false

        try {
            withContext(dispatcher) {
                installPackage(descriptor)
            }
            updateAvailability(modelId, SpeechModelAvailability.Ready)
        } catch (cancelled: CancellationException) {
            wasCancelled = true
            throw cancelled
        } catch (failure: Throwable) {
            updateAvailability(
                modelId,
                SpeechModelAvailability.Failed(failure.toSpeechFailure()),
            )
        } finally {
            if (wasCancelled || job.isCancelled) {
                val state = withContext(NonCancellable + dispatcher) {
                    if (isReadyDirectory(descriptor)) {
                        SpeechModelAvailability.Ready
                    } else {
                        SpeechModelAvailability.NotInstalled
                    }
                }
                updateAvailability(modelId, state)
            }
            synchronized(activeInstallMonitor) {
                if (activeInstalls[modelId] === job) {
                    activeInstalls.remove(modelId)
                }
            }
        }
    }

    override suspend fun cancelInstall(modelId: SpeechModelId) {
        val descriptor = descriptor(modelId)
        val install = synchronized(activeInstallMonitor) {
            checkOpen()
            activeInstalls[modelId]
        } ?: return
        install.cancelAndJoin()
        withContext(NonCancellable + dispatcher) {
            packageDownloads.remove(descriptor)
        }
        val state = withContext(NonCancellable + dispatcher) {
            if (isReadyDirectory(descriptor)) {
                SpeechModelAvailability.Ready
            } else {
                SpeechModelAvailability.NotInstalled
            }
        }
        synchronized(activeInstallMonitor) {
            val active = activeInstalls[modelId]
            if ((active == null || active === install) &&
                availability(modelId) is SpeechModelAvailability.Downloading
            ) {
                updateAvailability(modelId, state)
            }
        }
    }

    override suspend fun remove(modelId: SpeechModelId) {
        descriptor(modelId)
        checkOpenSynchronized()
        cancelInstall(modelId)
        withContext(dispatcher) {
            prepareRoot()
            modelDirectories(modelId).forEach(::deleteTree)
            stagingDirectories(modelId).forEach(::deleteTree)
            packageDownloads.remove(modelId)
        }
        updateAvailability(modelId, SpeechModelAvailability.NotInstalled)
    }

    override suspend fun resolve(
        modelId: SpeechModelId,
        capability: SpeechModelCapability,
    ): InstalledSpeechModel? {
        val descriptor = descriptor(modelId)
        checkOpenSynchronized()
        if (capability !in descriptor.capabilities ||
            availability(modelId) != SpeechModelAvailability.Ready
        ) {
            return null
        }
        return withContext(dispatcher) {
            val directory = activeDirectory(descriptor)
            if (isReadyDirectory(descriptor)) {
                InstalledSpeechModel(descriptor, directory)
            } else {
                updateAvailability(modelId, SpeechModelAvailability.NotInstalled)
                null
            }
        }
    }

    override fun close() {
        val installs = synchronized(activeInstallMonitor) {
            if (closed) {
                emptyList()
            } else {
                closed = true
                activeInstalls.values.toList().also { activeInstalls.clear() }
            }
        }
        installs.forEach(Job::cancel)
    }

    private suspend fun installPackage(descriptor: SpeechModelDescriptor) {
        prepareRoot()
        val installJob = checkNotNull(currentCoroutineContext()[Job]) {
            "Speech model installation requires a coroutine job"
        }
        stagingDirectories(descriptor.id).forEach(::deleteTree)
        val modelPackage = descriptor.modelPackage
        packageDownloads.removeObsolete(descriptor)
        val existingBytes = packageDownloads.persistedBytes(descriptor)
        val requiredBytes = try {
            Math.addExact(modelPackage.downloadSizeBytes - existingBytes, modelPackage.installedSizeBytes)
        } catch (_: ArithmeticException) {
            throw SpeechModelInstallException(
                code = "MODEL_SIZE_INVALID",
                guidance = "The selected speech model has invalid size metadata.",
            )
        }
        if (storageCapacity.availableBytes(root) < requiredBytes) {
            throw SpeechModelInstallException(
                code = "MODEL_STORAGE_FULL",
                guidance = "Free app storage before downloading this speech model.",
            )
        }

        val staging = Files.createTempDirectory(
            root.toPath(),
            ".install-${descriptor.id.value}-",
        ).toFile()
        restrictToOwner(staging)
        var activated = false
        try {
            val packageFile = packageDownloads.download(descriptor) { downloadedBytes ->
                updateAvailability(
                    descriptor.id,
                    SpeechModelAvailability.Downloading(downloadedBytes, modelPackage.downloadSizeBytes),
                )
            }
            verifyDownloadedPackage(descriptor, packageFile)

            val unpacked = File(staging, UNPACKED_DIRECTORY)
            if (!unpacked.mkdir()) {
                throw SpeechModelInstallException(
                    code = "MODEL_INSTALL_FAILED",
                    guidance = "The speech model could not be prepared. Retry the download.",
                )
            }
            restrictToOwner(unpacked)
            val extraction =
                ConfinedExtractionSink(unpacked, modelPackage.installedSizeBytes, installJob)
            extractor.extract(packageFile, extraction)
            installJob.ensureActive()
            extraction.requirePayload()
            validateExtractedTree(unpacked, modelPackage.installedSizeBytes)
            writeReadyMarker(unpacked, modelPackage.sha256)
            installJob.ensureActive()
            activate(descriptor, unpacked)
            activated = true
        } finally {
            val stagingCleanupFailure = runCatching { deleteTree(staging) }.exceptionOrNull()
            val partialCleanupFailure = if (activated) {
                runCatching { packageDownloads.remove(descriptor) }.exceptionOrNull()
            } else {
                null
            }
            if (activated &&
                (stagingCleanupFailure != null || partialCleanupFailure != null)
            ) {
                rejectCleanupFailure()
            }
        }
    }

    private fun verifyDownloadedPackage(
        descriptor: SpeechModelDescriptor,
        packageFile: File,
    ) {
        try {
            verifyChecksum(packageFile, descriptor.modelPackage.sha256)
        } catch (failure: Throwable) {
            packageDownloads.remove(descriptor)
            throw failure
        }
    }
    private fun verifyChecksum(
        packageFile: File,
        expectedSha256: String,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        packageFile.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) {
                    break
                }
                digest.update(buffer, 0, read)
            }
            buffer.fill(0)
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != expectedSha256) {
            throw SpeechModelInstallException(
                code = "MODEL_CHECKSUM_MISMATCH",
                guidance = "The speech model download failed verification. Retry it.",
            )
        }
    }

    private fun validateExtractedTree(
        directory: File,
        maximumBytes: Long,
        includesReadyMarker: Boolean = false,
    ) {
        val rootPath = directory.toPath().toAbsolutePath().normalize()
        val readyMarkerPath = rootPath.resolve(READY_MARKER)
        var entryCount = 0
        var installedBytes = 0L
        Files.walk(rootPath).use { paths ->
            paths.forEach { path ->
                if (path == rootPath) {
                    return@forEach
                }
                if (!path.toAbsolutePath().normalize().startsWith(rootPath) ||
                    Files.isSymbolicLink(path)
                ) {
                    rejectUnsafePackage()
                }
                if (includesReadyMarker && path == readyMarkerPath) {
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        rejectUnsafePackage()
                    }
                    return@forEach
                }
                entryCount += 1
                if (entryCount > MAX_MODEL_ENTRIES) {
                    rejectUnsafePackage()
                }
                when {
                    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> Unit
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) -> {
                        installedBytes = try {
                            Math.addExact(installedBytes, Files.size(path))
                        } catch (_: ArithmeticException) {
                            rejectUnsafePackage()
                        }
                        if (installedBytes > maximumBytes) {
                            rejectOversizedPackage()
                        }
                    }
                    else -> rejectUnsafePackage()
                }
            }
        }
        if (entryCount == 0 || installedBytes == 0L) {
            rejectUnsafePackage()
        }
    }

    private fun activate(
        descriptor: SpeechModelDescriptor,
        unpacked: File,
    ) {
        val target = activeDirectory(descriptor)
        if (target.exists()) {
            deleteTree(target)
        }
        moveDirectory(unpacked.toPath(), target.toPath())
        restrictToOwner(target)
        modelDirectories(descriptor.id)
            .filterNot { it == target }
            .forEach { obsolete -> runCatching { deleteTree(obsolete) } }
    }

    private fun moveDirectory(
        source: Path,
        target: Path,
    ) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target)
        }
    }

    private fun writeReadyMarker(
        directory: File,
        checksum: String,
    ) {
        val marker = File(directory, READY_MARKER)
        FileOutputStream(marker).use { output ->
            output.write(checksum.encodeToByteArray())
            output.fd.sync()
        }
        restrictToOwner(marker)
    }

    private fun isReadyDirectory(descriptor: SpeechModelDescriptor): Boolean {
        val directory = activeDirectory(descriptor)
        if (!directory.isDirectory) {
            return false
        }
        val marker = File(directory, READY_MARKER)
        if (!marker.isFile || marker.length() != SHA256_CHARACTERS.toLong()) {
            return false
        }
        if (marker.readText() != descriptor.modelPackage.sha256) {
            return false
        }
        return try {
            validateExtractedTree(
                directory,
                descriptor.modelPackage.installedSizeBytes,
                includesReadyMarker = true,
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun activeDirectory(descriptor: SpeechModelDescriptor): File =
        File(root, "${descriptor.id.value}.${descriptor.modelPackage.sha256}")

    private fun modelDirectories(modelId: SpeechModelId): List<File> =
        root.listFiles()
            .orEmpty()
            .filter { it.name.startsWith("${modelId.value}.") && it.isDirectory }

    private fun stagingDirectories(modelId: SpeechModelId): List<File> =
        root.listFiles()
            .orEmpty()
            .filter { it.name.startsWith(".install-${modelId.value}-") }

    private fun prepareRoot() {
        if (!root.exists() && !root.mkdirs()) {
            throw SpeechModelInstallException(
                code = "MODEL_STORAGE_UNAVAILABLE",
                guidance = "App storage is unavailable. Retry after checking device storage.",
            )
        }
        if (!root.isDirectory) {
            throw SpeechModelInstallException(
                code = "MODEL_STORAGE_UNAVAILABLE",
                guidance = "App storage is unavailable. Retry after checking device storage.",
            )
        }
        restrictToOwner(root)
    }

    private fun deleteTree(directory: File) {
        if (!directory.exists() && !Files.isSymbolicLink(directory.toPath())) {
            return
        }
        Files.walk(directory.toPath()).use { paths ->
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

    private fun descriptor(modelId: SpeechModelId): SpeechModelDescriptor =
        requireNotNull(descriptors[modelId]) { "Unknown speech model id" }

    private fun availability(modelId: SpeechModelId): SpeechModelAvailability =
        synchronized(stateMonitor) {
            checkNotNull(availability[modelId])
        }

    private fun updateAvailability(
        modelId: SpeechModelId,
        value: SpeechModelAvailability,
    ) {
        synchronized(stateMonitor) {
            availability[modelId] = value
            mutableModels.value = currentStates()
        }
    }

    private fun currentStates(): List<SpeechModelState> =
        modelOrder.map { modelId ->
            SpeechModelState(
                descriptor = checkNotNull(descriptors[modelId]),
                availability = checkNotNull(availability[modelId]),
            )
        }

    private fun checkOpenSynchronized() {
        synchronized(activeInstallMonitor) {
            checkOpen()
        }
    }

    private fun checkOpen() {
        check(!closed) { "Speech model store is closed" }
    }

    private fun unsafePackage() = SpeechModelInstallException(
        code = "MODEL_PACKAGE_UNSAFE",
        guidance = "The speech model package is unsafe and was not installed.",
    )

    private fun rejectCleanupFailure(): Nothing {
        throw SpeechModelInstallException(
            code = "MODEL_CLEANUP_FAILED",
            guidance = "The speech model was installed, but temporary storage could not be cleaned.",
        )
    }

    private fun rejectUnsafePackage(): Nothing = throw unsafePackage()

    private fun rejectOversizedPackage(): Nothing {
        throw SpeechModelInstallException(
            code = "MODEL_PACKAGE_TOO_LARGE",
            guidance = "The speech model package exceeds its declared size.",
        )
    }

    private fun Throwable.toSpeechFailure(): SpeechFailure =
        when (this) {
            is SpeechModelInstallException -> SpeechFailure(code, guidance)
            is SpeechPackageDeliveryException -> SpeechFailure(code, guidance)
            else -> SpeechFailure(
                code = "MODEL_INSTALL_FAILED",
                actionableMessage = "The speech model could not be installed. Retry the download.",
            )
        }

    private inner class ConfinedExtractionSink(
        destination: File,
        private val maximumBytes: Long,
        private val installJob: Job,
    ) : SpeechModelExtractionSink {
        private val rootPath = destination.toPath().toAbsolutePath().normalize()
        private val createdPaths = mutableSetOf<Path>()
        private var installedBytes = 0L

        override fun createDirectory(relativePath: String) {
            val path = resolve(relativePath)
            register(path)
            try {
                Files.createDirectories(path)
            } catch (_: Exception) {
                rejectUnsafePackage()
            }
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(path)
            ) {
                rejectUnsafePackage()
            }
            restrictToOwner(path.toFile())
        }

        override fun writeFile(
            relativePath: String,
            source: InputStream,
        ) {
            val path = resolve(relativePath)
            register(path)
            val parent = checkNotNull(path.parent)
            try {
                Files.createDirectories(parent)
            } catch (_: Exception) {
                rejectUnsafePackage()
            }
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            try {
                Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE_NEW).use { output ->
                    while (true) {
                        installJob.ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) {
                            break
                        }
                        installJob.ensureActive()
                        if (installedBytes > maximumBytes - read) {
                            rejectOversizedPackage()
                        }
                        output.write(buffer, 0, read)
                        installedBytes += read
                    }
                }
                restrictToOwner(path.toFile())
            } catch (failure: Throwable) {
                Files.deleteIfExists(path)
                throw failure
            } finally {
                buffer.fill(0)
            }
        }

        fun requirePayload() {
            if (createdPaths.isEmpty() || installedBytes == 0L) {
                rejectUnsafePackage()
            }
        }

        private fun resolve(relativePath: String): Path {
            val segments = relativePath.split('/')
            val invalidText = relativePath.isBlank() ||
                relativePath.length > MAX_MODEL_ENTRY_PATH_CHARACTERS ||
                '\\' in relativePath
            if (invalidText) {
                rejectUnsafePackage()
            }
            if (segments.any { it.isBlank() || it == "." || it == ".." }) {
                rejectUnsafePackage()
            }
            val path = try {
                Paths.get(relativePath)
            } catch (_: InvalidPathException) {
                rejectUnsafePackage()
            }
            if (path.isAbsolute) {
                rejectUnsafePackage()
            }
            val resolved = rootPath.resolve(path).normalize()
            if (!resolved.startsWith(rootPath) || resolved == rootPath) {
                rejectUnsafePackage()
            }
            return resolved
        }

        private fun register(path: Path) {
            if (!createdPaths.add(path) || createdPaths.size > MAX_MODEL_ENTRIES) {
                rejectUnsafePackage()
            }
            installJob.ensureActive()
            val parent = path.parent
            if (parent == null || !parent.startsWith(rootPath)) {
                rejectUnsafePackage()
            }
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                rejectUnsafePackage()
            }
            if (parent != rootPath && Files.isSymbolicLink(parent)) {
                rejectUnsafePackage()
            }
        }
    }

    private companion object {
        const val MODEL_DIRECTORY = "agent-relay-speech-models"
        const val UNPACKED_DIRECTORY = "unpacked"
        const val READY_MARKER = ".agent-relay-ready"
        const val SHA256_CHARACTERS = 64
        const val MAX_MODEL_ENTRIES = 20_000
        const val MAX_MODEL_ENTRY_PATH_CHARACTERS = 512
    }
}
