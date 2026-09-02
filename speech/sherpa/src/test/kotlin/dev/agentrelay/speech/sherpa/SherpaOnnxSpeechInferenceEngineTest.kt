package dev.agentrelay.speech.sherpa

import dev.agentrelay.speech.api.SpeechModelCapability
import dev.agentrelay.speech.api.SpeechModelId
import dev.agentrelay.speech.api.SpeechOperationId
import dev.agentrelay.speech.android.SpeechPcmFormat
import dev.agentrelay.speech.android.SpeechPcmFrame
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SherpaOnnxSpeechInferenceEngineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun streamsNormalizedPcmAndCombinesEndpointWithFinalTranscript() = runTest {
        val spec = spec()
        val modelDirectory = createModelDirectory(spec)
        val factory = FakeRecognizerFactory()
        val engine = engine(spec, factory, StandardTestDispatcher(testScheduler))

        val transcript = engine.transcribeResolved(
            operationId = operation(1),
            modelId = spec.modelId,
            capabilities = transcriptionCapabilities,
            directory = modelDirectory,
            audio = flowOf(
                frame(shortArrayOf(Short.MIN_VALUE, 0, 16_384)),
                frame(shortArrayOf(Short.MAX_VALUE)),
            ),
        )

        assertEquals("first segment final segment", transcript)
        val config = factory.configs.single()
        assertEquals(File(modelDirectory, spec.encoderFile).canonicalFile, config.encoder)
        assertEquals(File(modelDirectory, spec.decoderFile).canonicalFile, config.decoder)
        assertEquals(File(modelDirectory, spec.joinerFile).canonicalFile, config.joiner)
        assertEquals(File(modelDirectory, spec.tokensFile).canonicalFile, config.tokens)
        assertEquals("zipformer2", config.modelType)
        assertEquals(16_000, config.sampleRateHz)
        assertEquals(80, config.featureDimension)
        assertEquals(2, config.numThreads)

        val recognizer = factory.recognizers.single()
        assertContentEquals(floatArrayOf(-1.0f, 0.0f, 0.5f), recognizer.stream.acceptedSamples[0])
        assertEquals(Short.MAX_VALUE / 32_768.0f, recognizer.stream.acceptedSamples[1].single())
        assertTrue(recognizer.stream.retainedSamples.all { samples -> samples.all { it == 0.0f } })
        assertEquals(listOf(16_000, 16_000), recognizer.stream.sampleRates)
        assertEquals(1, recognizer.stream.inputFinishedCalls)
        assertEquals(3, recognizer.decodeCalls)
        assertEquals(1, recognizer.resetCalls)
        assertEquals(1, recognizer.stream.closeCalls)
        assertEquals(1, recognizer.closeCalls)
        engine.close()
    }

    @Test
    fun wrongPcmFormatFailsAndReleasesNativeResources() = runTest {
        val spec = spec()
        val factory = FakeRecognizerFactory()
        val engine = engine(spec, factory, StandardTestDispatcher(testScheduler))
        val failure = runCatching {
            engine.transcribeResolved(
                operationId = operation(1),
                modelId = spec.modelId,
                capabilities = transcriptionCapabilities,
                directory = createModelDirectory(spec),
                audio = flowOf(
                    SpeechPcmFrame(
                        SpeechPcmFormat(sampleRateHz = 8_000, channelCount = 1),
                        shortArrayOf(1),
                    ),
                ),
            )
        }.exceptionOrNull()

        assertIs<IllegalStateException>(failure)
        val recognizer = factory.recognizers.single()
        assertTrue(recognizer.stream.acceptedSamples.isEmpty())
        assertEquals(1, recognizer.stream.closeCalls)
        assertEquals(1, recognizer.closeCalls)
        engine.close()
    }

    @Test
    fun missingAndSymlinkEscapedFilesAreRejectedBeforeNativeCreation() = runTest {
        val spec = spec()
        val missingFactory = FakeRecognizerFactory()
        val missingDirectory = createModelDirectory(spec)
        assertTrue(File(missingDirectory, spec.tokensFile).delete())
        val missingEngine = engine(spec, missingFactory, StandardTestDispatcher(testScheduler))

        val missingFailure = transcribeFailure(missingEngine, spec, missingDirectory)

        assertIs<IllegalStateException>(missingFailure)
        assertTrue(missingFactory.configs.isEmpty())
        missingEngine.close()

        val linkedSpec = spec(tokensFile = "linked/tokens.txt")
        val linkedFactory = FakeRecognizerFactory()
        val linkedDirectory = temporaryFolder.newFolder("linked-model").absoluteFile
        createModelFiles(linkedDirectory, linkedSpec, includeTokens = false)
        val outsideDirectory = temporaryFolder.newFolder("outside-model").absoluteFile
        File(outsideDirectory, "tokens.txt").writeBytes(byteArrayOf(1))
        Files.createSymbolicLink(
            File(linkedDirectory, "linked").toPath(),
            outsideDirectory.toPath(),
        )
        val linkedEngine = engine(
            linkedSpec,
            linkedFactory,
            StandardTestDispatcher(testScheduler),
        )

        val linkedFailure = transcribeFailure(linkedEngine, linkedSpec, linkedDirectory)

        assertIs<IllegalStateException>(linkedFailure)
        assertTrue(linkedFactory.configs.isEmpty())
        linkedEngine.close()
    }

    @Test
    fun unsupportedCatalogStateAndDuplicateSpecsNeverCreateNativeState() = runTest {
        val spec = spec()
        val directory = createModelDirectory(spec)
        val factory = FakeRecognizerFactory()
        val engine = engine(spec, factory, StandardTestDispatcher(testScheduler))

        val missingCapability = runCatching {
            engine.transcribeResolved(
                operationId = operation(1),
                modelId = spec.modelId,
                capabilities = emptySet(),
                directory = directory,
                audio = flowOf(frame()),
            )
        }.exceptionOrNull()
        val unknownModel = runCatching {
            engine.transcribeResolved(
                operationId = operation(2),
                modelId = SpeechModelId("unknown-asr"),
                capabilities = transcriptionCapabilities,
                directory = directory,
                audio = flowOf(frame()),
            )
        }.exceptionOrNull()
        val duplicateSpecs = runCatching {
            engine(
                listOf(spec, spec),
                FakeRecognizerFactory(),
                StandardTestDispatcher(testScheduler),
            )
        }.exceptionOrNull()

        assertIs<IllegalStateException>(missingCapability)
        assertIs<IllegalStateException>(unknownModel)
        assertIs<IllegalArgumentException>(duplicateSpecs)
        assertTrue(factory.configs.isEmpty())
        engine.close()
    }

    @Test
    fun staleCancelDoesNotAffectReplacementAndCurrentCancelStopsNextFrame() = runTest {
        val spec = spec()
        val directory = createModelDirectory(spec)
        val factory = FakeRecognizerFactory()
        val engine = engine(spec, factory, StandardTestDispatcher(testScheduler))
        engine.transcribeResolved(
            operationId = operation(1),
            modelId = spec.modelId,
            capabilities = transcriptionCapabilities,
            directory = directory,
            audio = flowOf(frame()),
        )
        val replacementAudio = Channel<SpeechPcmFrame>(Channel.UNLIMITED)
        val replacement = async {
            runCatching {
                engine.transcribeResolved(
                    operationId = operation(2),
                    modelId = spec.modelId,
                    capabilities = transcriptionCapabilities,
                    directory = directory,
                    audio = replacementAudio.receiveAsFlow(),
                )
            }
        }
        runCurrent()

        engine.cancel(operation(1))
        replacementAudio.send(frame(shortArrayOf(2)))
        runCurrent()
        assertEquals(1, factory.recognizers[1].stream.acceptedSamples.size)

        engine.cancel(operation(2))
        replacementAudio.send(frame(shortArrayOf(3)))
        advanceUntilIdle()

        assertIs<CancellationException>(replacement.await().exceptionOrNull())
        val replacementRecognizer = factory.recognizers[1]
        assertEquals(1, replacementRecognizer.stream.acceptedSamples.size)
        assertEquals(1, replacementRecognizer.stream.closeCalls)
        assertEquals(1, replacementRecognizer.closeCalls)
        replacementAudio.close()
        engine.close()
    }

    @Test
    fun closeCancelsActiveRecognitionAndRejectsLaterOperations() = runTest {
        val spec = spec()
        val directory = createModelDirectory(spec)
        val factory = FakeRecognizerFactory()
        val engine = engine(spec, factory, StandardTestDispatcher(testScheduler))
        val audio = Channel<SpeechPcmFrame>(Channel.UNLIMITED)
        val active = async {
            runCatching {
                engine.transcribeResolved(
                    operationId = operation(1),
                    modelId = spec.modelId,
                    capabilities = transcriptionCapabilities,
                    directory = directory,
                    audio = audio.receiveAsFlow(),
                )
            }
        }
        runCurrent()

        engine.close()
        engine.close()
        audio.send(frame())
        advanceUntilIdle()

        assertIs<CancellationException>(active.await().exceptionOrNull())
        assertEquals(1, factory.recognizers.single().stream.closeCalls)
        assertEquals(1, factory.recognizers.single().closeCalls)
        val closedFailure = runCatching {
            engine.transcribeResolved(
                operationId = operation(2),
                modelId = spec.modelId,
                capabilities = transcriptionCapabilities,
                directory = directory,
                audio = flowOf(frame()),
            )
        }.exceptionOrNull()
        assertIs<IllegalStateException>(closedFailure)
        assertEquals(1, factory.recognizers.size)
        audio.close()
    }

    @Test
    fun recognizerThatNeverDrainsIsBoundedAndReleased() = runTest {
        val spec = spec()
        val factory = FakeRecognizerFactory {
            FakeRecognizer(alwaysReady = true)
        }
        val engine = engine(spec, factory, StandardTestDispatcher(testScheduler))

        val failure = transcribeFailure(engine, spec, createModelDirectory(spec))

        assertIs<IllegalStateException>(failure)
        val recognizer = factory.recognizers.single()
        assertEquals(1_000, recognizer.decodeCalls)
        assertEquals(1, recognizer.stream.closeCalls)
        assertEquals(1, recognizer.closeCalls)
        engine.close()
    }

    private suspend fun transcribeFailure(
        engine: SherpaOnnxSpeechInferenceEngine,
        spec: SherpaStreamingTransducerSpec,
        directory: File,
    ): Throwable? = runCatching {
        engine.transcribeResolved(
            operationId = operation(1),
            modelId = spec.modelId,
            capabilities = transcriptionCapabilities,
            directory = directory,
            audio = flowOf(frame()),
        )
    }.exceptionOrNull()

    private fun createModelDirectory(spec: SherpaStreamingTransducerSpec): File =
        temporaryFolder.newFolder().absoluteFile.also { directory ->
            createModelFiles(directory, spec)
        }

    private fun createModelFiles(
        directory: File,
        spec: SherpaStreamingTransducerSpec,
        includeTokens: Boolean = true,
    ) {
        val paths = buildList {
            add(spec.encoderFile)
            add(spec.decoderFile)
            add(spec.joinerFile)
            if (includeTokens) {
                add(spec.tokensFile)
            }
        }
        paths.forEach { relativePath ->
            File(directory, relativePath).also { file ->
                val parent = checkNotNull(file.parentFile)
                check(parent.mkdirs() || parent.isDirectory)
                file.writeBytes(byteArrayOf(1))
            }
        }
    }

    private class FakeRecognizerFactory(
        private val createRecognizer: () -> FakeRecognizer = { FakeRecognizer() },
    ) : SherpaOnlineRecognizerFactory {
        val configs = mutableListOf<SherpaOnlineRuntimeConfig>()
        val recognizers = mutableListOf<FakeRecognizer>()

        override fun create(config: SherpaOnlineRuntimeConfig): SherpaOnlineRecognizer {
            configs += config
            return createRecognizer().also { recognizers += it }
        }
    }

    private class FakeRecognizer(
        private val alwaysReady: Boolean = false,
    ) : SherpaOnlineRecognizer {
        val stream = FakeStream(::onWaveform, ::onInputFinished)
        var decodeCalls = 0
        var resetCalls = 0
        var closeCalls = 0
        private var pendingDecodes = 0
        private var closed = false

        override fun createStream(): SherpaOnlineStream {
            check(!closed)
            return stream
        }

        override fun isReady(stream: SherpaOnlineStream): Boolean {
            check(stream === this.stream)
            return alwaysReady || pendingDecodes > 0
        }

        override fun decode(stream: SherpaOnlineStream) {
            check(stream === this.stream)
            decodeCalls += 1
            if (!alwaysReady) {
                pendingDecodes -= 1
            }
        }

        override fun isEndpoint(stream: SherpaOnlineStream): Boolean {
            check(stream === this.stream)
            return resetCalls == 0 && this.stream.acceptedSamples.size == 1
        }

        override fun resultText(stream: SherpaOnlineStream): String {
            check(stream === this.stream)
            return if (resetCalls == 0) " first segment " else " final segment "
        }

        override fun reset(stream: SherpaOnlineStream) {
            check(stream === this.stream)
            resetCalls += 1
        }

        override fun close() {
            if (!closed) {
                closed = true
                closeCalls += 1
            }
        }

        private fun onWaveform() {
            pendingDecodes += 1
        }

        private fun onInputFinished() {
            pendingDecodes += 1
        }
    }

    private class FakeStream(
        private val onWaveform: () -> Unit,
        private val onInputFinished: () -> Unit,
    ) : SherpaOnlineStream {
        val acceptedSamples = mutableListOf<FloatArray>()
        val retainedSamples = mutableListOf<FloatArray>()
        val sampleRates = mutableListOf<Int>()
        var inputFinishedCalls = 0
        var closeCalls = 0
        private var closed = false

        override fun acceptWaveform(
            samples: FloatArray,
            sampleRateHz: Int,
        ) {
            check(!closed)
            retainedSamples += samples
            acceptedSamples += samples.copyOf()
            sampleRates += sampleRateHz
            onWaveform()
        }

        override fun inputFinished() {
            check(!closed)
            inputFinishedCalls += 1
            onInputFinished()
        }

        override fun close() {
            if (!closed) {
                closed = true
                closeCalls += 1
            }
        }
    }

    private companion object {
        val transcriptionCapabilities = setOf(SpeechModelCapability.TRANSCRIPTION)

        fun operation(value: Long) = SpeechOperationId(value)

        fun frame(samples: ShortArray = shortArrayOf(1)) = SpeechPcmFrame(
            format = SpeechPcmFormat(sampleRateHz = 16_000, channelCount = 1),
            samples = samples,
        )

        fun spec(
            tokensFile: String = "tokens.txt",
        ) = SherpaStreamingTransducerSpec(
            modelId = SpeechModelId("test-asr"),
            encoderFile = "encoder.onnx",
            decoderFile = "decoder.onnx",
            joinerFile = "joiner.onnx",
            tokensFile = tokensFile,
            modelType = "zipformer2",
        )

        fun engine(
            spec: SherpaStreamingTransducerSpec,
            factory: FakeRecognizerFactory,
            dispatcher: CoroutineDispatcher,
        ) = engine(listOf(spec), factory, dispatcher)

        fun engine(
            specs: Collection<SherpaStreamingTransducerSpec>,
            factory: FakeRecognizerFactory,
            dispatcher: CoroutineDispatcher,
        ) = SherpaOnnxSpeechInferenceEngine(specs, factory, dispatcher)
    }
}
