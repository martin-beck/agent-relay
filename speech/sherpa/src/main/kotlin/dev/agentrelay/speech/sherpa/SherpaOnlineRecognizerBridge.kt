package dev.agentrelay.speech.sherpa

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

internal data class SherpaOnlineRuntimeConfig(
    val encoder: File,
    val decoder: File,
    val joiner: File,
    val tokens: File,
    val modelType: String,
    val sampleRateHz: Int,
    val featureDimension: Int,
    val numThreads: Int,
)

internal fun interface SherpaOnlineRecognizerFactory {
    fun create(config: SherpaOnlineRuntimeConfig): SherpaOnlineRecognizer
}

internal interface SherpaOnlineRecognizer : AutoCloseable {
    fun createStream(): SherpaOnlineStream

    fun isReady(stream: SherpaOnlineStream): Boolean

    fun decode(stream: SherpaOnlineStream)

    fun isEndpoint(stream: SherpaOnlineStream): Boolean

    fun resultText(stream: SherpaOnlineStream): String

    fun reset(stream: SherpaOnlineStream)
}

internal interface SherpaOnlineStream : AutoCloseable {
    fun acceptWaveform(
        samples: FloatArray,
        sampleRateHz: Int,
    )

    fun inputFinished()
}

internal object NativeSherpaOnlineRecognizerFactory : SherpaOnlineRecognizerFactory {
    override fun create(config: SherpaOnlineRuntimeConfig): SherpaOnlineRecognizer {
        val recognizerConfig = OnlineRecognizerConfig(
            featConfig = FeatureConfig(
                sampleRate = config.sampleRateHz,
                featureDim = config.featureDimension,
                dither = 0.0f,
            ),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = config.encoder.absolutePath,
                    decoder = config.decoder.absolutePath,
                    joiner = config.joiner.absolutePath,
                ),
                tokens = config.tokens.absolutePath,
                numThreads = config.numThreads,
                debug = false,
                provider = "cpu",
                modelType = config.modelType,
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        )
        return NativeSherpaOnlineRecognizer(OnlineRecognizer(config = recognizerConfig))
    }
}

private class NativeSherpaOnlineRecognizer(
    private val delegate: OnlineRecognizer,
) : SherpaOnlineRecognizer {
    private val closed = AtomicBoolean(false)

    override fun createStream(): SherpaOnlineStream {
        check(!closed.get()) { "Sherpa recognizer is closed" }
        return NativeSherpaOnlineStream(delegate.createStream())
    }

    override fun isReady(stream: SherpaOnlineStream): Boolean =
        delegate.isReady(stream.nativeStream())

    override fun decode(stream: SherpaOnlineStream) {
        delegate.decode(stream.nativeStream())
    }

    override fun isEndpoint(stream: SherpaOnlineStream): Boolean =
        delegate.isEndpoint(stream.nativeStream())

    override fun resultText(stream: SherpaOnlineStream): String =
        delegate.getResult(stream.nativeStream()).text

    override fun reset(stream: SherpaOnlineStream) {
        delegate.reset(stream.nativeStream())
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            delegate.release()
        }
    }
}

private class NativeSherpaOnlineStream(
    val delegate: OnlineStream,
) : SherpaOnlineStream {
    private val closed = AtomicBoolean(false)

    override fun acceptWaveform(
        samples: FloatArray,
        sampleRateHz: Int,
    ) {
        check(!closed.get()) { "Sherpa stream is closed" }
        delegate.acceptWaveform(samples, sampleRateHz)
    }

    override fun inputFinished() {
        check(!closed.get()) { "Sherpa stream is closed" }
        delegate.inputFinished()
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            delegate.release()
        }
    }
}

private fun SherpaOnlineStream.nativeStream(): OnlineStream =
    (this as? NativeSherpaOnlineStream)?.delegate
        ?: error("Sherpa native recognizer received a foreign stream")
