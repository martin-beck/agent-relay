package dev.agentrelay.speech.sherpa

import dev.agentrelay.speech.api.MAX_SPEECH_TRANSCRIPT_CHARS
import dev.agentrelay.speech.api.SpeechModelCapability
import dev.agentrelay.speech.api.SpeechModelId
import dev.agentrelay.speech.api.SpeechOperationId
import dev.agentrelay.speech.android.InstalledSpeechModel
import dev.agentrelay.speech.android.SpeechInferenceEngine
import dev.agentrelay.speech.android.SpeechPcmFormat
import dev.agentrelay.speech.android.SpeechPcmFrame
import dev.agentrelay.speech.android.SpeechSynthesisRequest
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Generation-safe sherpa-onnx streaming transcription.
 *
 * The native recognizer is created per operation so model removal cannot leave a hidden native
 * model cache alive. This first adapter intentionally exposes no synthesis implementation.
 */
class SherpaOnnxSpeechInferenceEngine internal constructor(
    specs: Collection<SherpaStreamingTransducerSpec>,
    private val recognizerFactory: SherpaOnlineRecognizerFactory,
    private val dispatcher: CoroutineDispatcher,
) : SpeechInferenceEngine {
    constructor(
        specs: Collection<SherpaStreamingTransducerSpec>,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(specs, NativeSherpaOnlineRecognizerFactory, dispatcher)

    private val specsById = specs.associateBy(SherpaStreamingTransducerSpec::modelId)
    private val stateMonitor = Any()
    private var activeRecognition: ActiveRecognition? = null
    private var closed = false

    init {
        require(specs.isNotEmpty()) { "Sherpa model specifications must not be empty" }
        require(specsById.size == specs.size) { "Sherpa model specification ids must be unique" }
    }

    override suspend fun transcribe(
        operationId: SpeechOperationId,
        model: InstalledSpeechModel,
        audio: Flow<SpeechPcmFrame>,
    ): String = transcribeResolved(
        operationId = operationId,
        modelId = model.descriptor.id,
        capabilities = model.descriptor.capabilities,
        directory = model.directory,
        audio = audio,
    )

    internal suspend fun transcribeResolved(
        operationId: SpeechOperationId,
        modelId: SpeechModelId,
        capabilities: Set<SpeechModelCapability>,
        directory: File,
        audio: Flow<SpeechPcmFrame>,
    ): String = withContext(dispatcher) {
        check(SpeechModelCapability.TRANSCRIPTION in capabilities) {
            "Sherpa model does not support transcription"
        }
        val spec = checkNotNull(specsById[modelId]) {
            "Sherpa model runtime specification is unavailable"
        }
        val runtimeConfig = resolveRuntimeConfig(directory, spec)
        val active = ActiveRecognition(operationId)
        synchronized(stateMonitor) {
            check(!closed) { "Sherpa inference engine is closed" }
            check(activeRecognition == null) { "Sherpa recognition is already active" }
            activeRecognition = active
        }

        var recognizer: SherpaOnlineRecognizer? = null
        var stream: SherpaOnlineStream? = null
        try {
            ensureCurrent(active)
            recognizer = recognizerFactory.create(runtimeConfig)
            ensureCurrent(active)
            stream = recognizer.createStream()
            ensureCurrent(active)

            val transcript = StringBuilder()
            audio.collect { frame ->
                processFrame(active, spec, recognizer, stream, frame, transcript)
            }
            ensureCurrent(active)
            stream.inputFinished()
            drainReady(active, recognizer, stream)
            appendTranscript(transcript, recognizer.resultText(stream))
            transcript.toString()
        } finally {
            synchronized(stateMonitor) {
                if (activeRecognition === active) {
                    activeRecognition = null
                }
            }
            runCatching { stream?.close() }
            runCatching { recognizer?.close() }
        }
    }

    override fun synthesize(request: SpeechSynthesisRequest): Flow<SpeechPcmFrame> =
        flow {
            check(request.model.descriptor.id in specsById) {
                "Sherpa model runtime specification is unavailable"
            }
            error("Sherpa speech synthesis is not implemented")
        }

    override suspend fun cancel(operationId: SpeechOperationId) {
        synchronized(stateMonitor) {
            activeRecognition?.takeIf { it.operationId == operationId }?.cancelled = true
        }
    }

    override fun close() {
        synchronized(stateMonitor) {
            if (closed) {
                return
            }
            closed = true
            activeRecognition?.cancelled = true
        }
    }

    private suspend fun processFrame(
        active: ActiveRecognition,
        spec: SherpaStreamingTransducerSpec,
        recognizer: SherpaOnlineRecognizer,
        stream: SherpaOnlineStream,
        frame: SpeechPcmFrame,
        transcript: StringBuilder,
    ) {
        ensureCurrent(active)
        check(frame.format == SpeechPcmFormat(spec.sampleRateHz, channelCount = 1)) {
            "Sherpa transcription requires model-rate mono PCM"
        }

        val normalized = FloatArray(frame.samples.size)
        try {
            frame.samples.forEachIndexed { index, sample ->
                normalized[index] = sample / PCM_SCALE
            }
            stream.acceptWaveform(normalized, spec.sampleRateHz)
        } finally {
            normalized.fill(0.0f)
        }
        drainReady(active, recognizer, stream)
        if (recognizer.isEndpoint(stream)) {
            appendTranscript(transcript, recognizer.resultText(stream))
            recognizer.reset(stream)
        }
    }

    private suspend fun drainReady(
        active: ActiveRecognition,
        recognizer: SherpaOnlineRecognizer,
        stream: SherpaOnlineStream,
    ) {
        var decoded = 0
        while (recognizer.isReady(stream)) {
            ensureCurrent(active)
            check(decoded < MAX_DECODE_STEPS_PER_FRAME) {
                "Sherpa recognizer did not drain a PCM frame"
            }
            recognizer.decode(stream)
            decoded += 1
        }
    }

    private suspend fun ensureCurrent(active: ActiveRecognition) {
        currentCoroutineContext().ensureActive()
        val isCurrent = synchronized(stateMonitor) {
            !closed && !active.cancelled && activeRecognition === active
        }
        if (!isCurrent) {
            throw CancellationException("Sherpa recognition was cancelled")
        }
    }

    private data class ActiveRecognition(
        val operationId: SpeechOperationId,
        var cancelled: Boolean = false,
    )

    private companion object {
        const val PCM_SCALE = 32_768.0f
        const val MAX_DECODE_STEPS_PER_FRAME = 1_000
    }
}

private fun resolveRuntimeConfig(
    directory: File,
    spec: SherpaStreamingTransducerSpec,
): SherpaOnlineRuntimeConfig {
    check(directory.isAbsolute) { "Sherpa model directory must be absolute" }
    val directoryPath = directory.toPath()
    check(
        Files.isDirectory(directoryPath, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(directoryPath),
    ) {
        "Sherpa model directory is unavailable"
    }
    val realDirectory = directoryPath.toRealPath()
    fun resolve(relativePath: String): File {
        val candidate = directoryPath.resolve(relativePath).normalize()
        check(candidate.startsWith(directoryPath)) {
            "Sherpa model file escaped its directory"
        }
        check(
            Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(candidate),
        ) {
            "Sherpa model file is unavailable"
        }
        val realFile = candidate.toRealPath()
        check(realFile.startsWith(realDirectory)) {
            "Sherpa model file escaped its directory"
        }
        return realFile.toFile()
    }

    return SherpaOnlineRuntimeConfig(
        encoder = resolve(spec.encoderFile),
        decoder = resolve(spec.decoderFile),
        joiner = resolve(spec.joinerFile),
        tokens = resolve(spec.tokensFile),
        modelType = spec.modelType,
        sampleRateHz = spec.sampleRateHz,
        featureDimension = spec.featureDimension,
        numThreads = spec.numThreads,
    )
}

private fun appendTranscript(
    transcript: StringBuilder,
    segment: String,
) {
    val normalized = segment.trim()
    if (normalized.isEmpty()) {
        return
    }
    val separatorChars = if (transcript.isEmpty()) 0 else 1
    check(transcript.length + separatorChars + normalized.length <= MAX_SPEECH_TRANSCRIPT_CHARS) {
        "Sherpa transcript exceeded its size limit"
    }
    if (separatorChars > 0) {
        transcript.append(' ')
    }
    transcript.append(normalized)
}
