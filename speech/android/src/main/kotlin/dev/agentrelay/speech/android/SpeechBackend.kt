package dev.agentrelay.speech.android

import dev.agentrelay.speech.api.MAX_SPEECH_PLAYBACK_CHARS
import dev.agentrelay.speech.api.SpeechModelCapability
import dev.agentrelay.speech.api.SpeechModelDescriptor
import dev.agentrelay.speech.api.SpeechModelId
import dev.agentrelay.speech.api.SpeechModelState
import dev.agentrelay.speech.api.SpeechOperationId
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A model directory admitted by [SpeechModelStore].
 *
 * The path is app-private implementation data. Callers must never log it or expose it to UI state.
 */
class InstalledSpeechModel internal constructor(
    val descriptor: SpeechModelDescriptor,
    val directory: File,
) {
    init {
        require(directory.isAbsolute) { "Installed speech model directory must be absolute" }
    }
}

/**
 * Owns app-private model installation and resolves only completely activated packages.
 */
interface SpeechModelStore : Closeable {
    val models: StateFlow<List<SpeechModelState>>

    suspend fun install(modelId: SpeechModelId)

    suspend fun cancelInstall(modelId: SpeechModelId)

    suspend fun remove(modelId: SpeechModelId)

    suspend fun resolve(
        modelId: SpeechModelId,
        capability: SpeechModelCapability,
    ): InstalledSpeechModel?
}

/**
 * Streams one exact catalog package into [destination].
 *
 * Implementations must not close [destination], add credentials, downgrade HTTPS, or retain a
 * package after this call returns.
 */
fun interface SpeechPackageDownloader {
    suspend fun download(
        descriptor: SpeechModelDescriptor,
        destination: OutputStream,
    )
}

/**
 * Outcome of a bounded attempt to append a persisted model-package prefix.
 */
enum class SpeechPackageResumeResult {
    APPENDED,
    RESTART_REQUIRED,
}

/**
 * Extends [SpeechPackageDownloader] for a previously persisted package prefix.
 *
 * [offsetBytes] is bound to the exact catalog checksum and size by the model store. Implementations
 * return [SpeechPackageResumeResult.APPENDED] only after appending bytes from that exact offset.
 * [SpeechPackageResumeResult.RESTART_REQUIRED] must be returned without writing to [destination].
 */
interface ResumableSpeechPackageDownloader : SpeechPackageDownloader {
    suspend fun resumeDownload(
        descriptor: SpeechModelDescriptor,
        offsetBytes: Long,
        destination: OutputStream,
    ): SpeechPackageResumeResult
}

/**
 * Decodes a verified archive through a path-confined sink.
 *
 * Implementations must reject archive link and special-file entries. They receive no destination
 * path: every regular file and directory is admitted by [SpeechModelExtractionSink].
 */
fun interface SpeechPackageExtractor {
    suspend fun extract(
        verifiedPackage: File,
        destination: SpeechModelExtractionSink,
    )
}

interface SpeechModelExtractionSink {
    fun createDirectory(relativePath: String)

    /**
     * Consumes [source] without closing it and creates one new regular file.
     */
    fun writeFile(
        relativePath: String,
        source: InputStream,
    )
}

fun interface SpeechStorageCapacity {
    fun availableBytes(directory: File): Long
}

data class SpeechPcmFormat(
    val sampleRateHz: Int,
    val channelCount: Int,
) {
    init {
        require(sampleRateHz in 8_000..192_000) { "Speech sample rate is unsupported" }
        require(channelCount in 1..2) { "Speech channel count is unsupported" }
    }
}

/**
 * Signed 16-bit PCM owned by one pipeline stage.
 *
 * Producers must not mutate [samples] after emission. Consumers must not retain it after the
 * collecting call returns and should clear temporary copies that outlive immediate processing.
 */
class SpeechPcmFrame(
    val format: SpeechPcmFormat,
    val samples: ShortArray,
) {
    init {
        require(samples.isNotEmpty()) { "Speech PCM frame must not be empty" }
        require(samples.size <= format.sampleRateHz * format.channelCount) {
            "Speech PCM frame must contain at most one second of audio"
        }
    }
}

data class SpeechSynthesisRequest(
    val operationId: SpeechOperationId,
    val model: InstalledSpeechModel,
    val text: String,
) {
    init {
        require(text.isNotBlank()) { "Speech synthesis text must not be blank" }
        require(text.length <= MAX_SPEECH_PLAYBACK_CHARS) {
            "Speech synthesis text is too large"
        }
    }
}

/**
 * Foreground audio-capture boundary. Implementations own permission and Android lifecycle checks.
 */
interface SpeechAudioCapture : Closeable {
    fun capture(operationId: SpeechOperationId): Flow<SpeechPcmFrame>

    suspend fun stop(operationId: SpeechOperationId)
}

/**
 * Offline inference boundary implemented by the separately pinned sherpa-onnx adapter.
 */
interface SpeechInferenceEngine : Closeable {
    suspend fun transcribe(
        operationId: SpeechOperationId,
        model: InstalledSpeechModel,
        audio: Flow<SpeechPcmFrame>,
    ): String

    fun synthesize(request: SpeechSynthesisRequest): Flow<SpeechPcmFrame>

    suspend fun cancel(operationId: SpeechOperationId)
}

/**
 * Android audio-focus and output-routing boundary.
 */
interface SpeechAudioPlayback : Closeable {
    suspend fun play(
        operationId: SpeechOperationId,
        audio: Flow<SpeechPcmFrame>,
    )

    suspend fun stop(operationId: SpeechOperationId)
}
