/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.speech.android

import dev.agentrelay.speech.api.SpeechModelAvailability
import dev.agentrelay.speech.api.SpeechModelCapability
import dev.agentrelay.speech.api.SpeechModelDescriptor
import dev.agentrelay.speech.api.SpeechModelId
import dev.agentrelay.speech.api.SpeechModelLicense
import dev.agentrelay.speech.api.SpeechModelPackage
import dev.agentrelay.speech.api.SpeechModelState
import dev.agentrelay.speech.api.SpeechOperationId
import dev.agentrelay.speech.api.SpeechPlaybackState
import dev.agentrelay.speech.api.SpeechRecognitionState
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidOfflineSpeechServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun stoppingCapturePublishesReviewedRecognitionResult() = runTest {
        val fixture = fixture()
        fixture.inference.transcript = "review this draft"
        val service = fixture.service(StandardTestDispatcher(testScheduler))

        val operationId = service.startListening(fixture.descriptor.id)

        assertEquals(
            SpeechRecognitionState.Listening(
                operationId,
                fixture.descriptor.id,
                TEST_TIME_MILLIS,
            ),
            service.recognition.value,
        )
        runCurrent()
        fixture.capture.emit(operationId, pcmFrame())
        service.stopListening(operationId)
        assertIs<SpeechRecognitionState.Transcribing>(service.recognition.value)

        advanceUntilIdle()

        val result = assertIs<SpeechRecognitionState.Result>(service.recognition.value)
        assertEquals(operationId, result.operationId)
        assertEquals("review this draft", result.text)
        assertEquals(listOf(operationId), fixture.capture.stopped)
        assertEquals(1, fixture.inference.transcribedFrames.single().size)
        service.close()
    }

    @Test
    fun missingModelsAndInvalidPlaybackTextUseStableRedactedFailures() = runTest {
        val unavailable = fixture(ready = false)
        val unavailableService = unavailable.service(StandardTestDispatcher(testScheduler))

        val recognitionId = unavailableService.startListening(unavailable.descriptor.id)

        val recognitionFailure =
            assertIs<SpeechRecognitionState.Failed>(unavailableService.recognition.value)
        assertEquals(recognitionId, recognitionFailure.operationId)
        assertEquals("MODEL_NOT_READY", recognitionFailure.failure.code)
        assertTrue(unavailable.capture.captured.isEmpty())

        val ready = fixture()
        val readyService = ready.service(StandardTestDispatcher(testScheduler))
        val playbackId = readyService.speak(ready.descriptor.id, " ")

        val playbackFailure = assertIs<SpeechPlaybackState.Failed>(readyService.playback.value)
        assertEquals(playbackId, playbackFailure.operationId)
        assertEquals("SPEECH_TEXT_INVALID", playbackFailure.failure.code)
        assertTrue(ready.inference.synthesisRequests.isEmpty())

        unavailableService.close()
        readyService.close()
    }

    @Test
    fun canceledLateRecognitionCannotReplaceANewerGeneration() = runTest {
        val fixture = fixture()
        val service = fixture.service(StandardTestDispatcher(testScheduler))
        val first = service.startListening(fixture.descriptor.id)
        fixture.inference.nonCooperativeOperation = first
        runCurrent()

        val cancellation = launch { service.cancelListening(first) }
        runCurrent()
        assertIs<SpeechRecognitionState.Idle>(service.recognition.value)

        val second = service.startListening(fixture.descriptor.id)
        runCurrent()
        val secondState = assertIs<SpeechRecognitionState.Listening>(service.recognition.value)
        assertEquals(second, secondState.operationId)

        fixture.inference.releaseLateResult.complete("obsolete secret result")
        cancellation.join()
        runCurrent()

        val stillSecond = assertIs<SpeechRecognitionState.Listening>(service.recognition.value)
        assertEquals(second, stillSecond.operationId)
        service.stopListening(first)
        assertEquals(listOf(first), fixture.capture.stopped)

        service.cancelListening(second)
        assertIs<SpeechRecognitionState.Idle>(service.recognition.value)
        assertEquals(listOf(first, second), fixture.capture.stopped)
        service.close()
    }

    @Test
    fun playbackPublishesPlayingAndStopsOnlyTheObservedGeneration() = runTest {
        val fixture = fixture()
        fixture.playback.release = CompletableDeferred()
        val service = fixture.service(StandardTestDispatcher(testScheduler))

        val operationId = service.speak(fixture.descriptor.id, "read this")
        assertIs<SpeechPlaybackState.Synthesizing>(service.playback.value)
        runCurrent()

        val playing = assertIs<SpeechPlaybackState.Playing>(service.playback.value)
        assertEquals(operationId, playing.operationId)
        assertEquals("read this", fixture.inference.synthesisRequests.single().text)
        assertEquals(1, fixture.playback.frames.size)

        service.stopSpeaking(SpeechOperationId(operationId.value + 100L))
        assertIs<SpeechPlaybackState.Playing>(service.playback.value)
        assertTrue(fixture.playback.stopped.isEmpty())

        service.stopSpeaking(operationId)

        assertIs<SpeechPlaybackState.Idle>(service.playback.value)
        assertEquals(listOf(operationId), fixture.playback.stopped)
        assertEquals(listOf(operationId), fixture.inference.cancelled)
        service.close()
    }

    @Test
    fun closeStopsActivePlatformOperationsBeforeReleasingTheService() = runTest {
        val recognitionFixture = fixture()
        val recognitionService = recognitionFixture.service(StandardTestDispatcher(testScheduler))
        val recognitionId = recognitionService.startListening(recognitionFixture.descriptor.id)
        runCurrent()
        recognitionService.close()
        assertEquals(listOf(recognitionId), recognitionFixture.capture.stopped)
        assertEquals(listOf(recognitionId), recognitionFixture.inference.cancelled)

        val playbackFixture = fixture()
        playbackFixture.playback.release = CompletableDeferred()
        val playbackService = playbackFixture.service(StandardTestDispatcher(testScheduler))
        val playbackId = playbackService.speak(playbackFixture.descriptor.id, "read this")
        runCurrent()
        playbackService.close()
        assertEquals(listOf(playbackId), playbackFixture.playback.stopped)
        assertEquals(listOf(playbackId), playbackFixture.inference.cancelled)
    }

    @Test
    fun removingAnActiveModelStopsItsPipelineBeforeDeletingFiles() = runTest {
        val events = mutableListOf<String>()
        val fixture = fixture(events = events)
        val service = fixture.service(StandardTestDispatcher(testScheduler))
        val operationId = service.startListening(fixture.descriptor.id)
        runCurrent()

        service.removeModel(fixture.descriptor.id)

        assertIs<SpeechRecognitionState.Idle>(service.recognition.value)
        assertEquals(operationId, fixture.capture.stopped.single())
        assertEquals(
            listOf("capture-stop", "inference-cancel", "store-remove"),
            events,
        )
        assertEquals(listOf(fixture.descriptor.id), fixture.store.removed)
        service.close()
    }

    @Test
    fun inferenceExceptionsNeverExposeExceptionPayloads() = runTest {
        val fixture = fixture()
        fixture.inference.transcriptionFailure =
            IllegalStateException("private transcript and model path")
        val service = fixture.service(StandardTestDispatcher(testScheduler))
        val operationId = service.startListening(fixture.descriptor.id)
        runCurrent()
        fixture.capture.emit(operationId, pcmFrame())

        service.stopListening(operationId)
        advanceUntilIdle()

        val failed = assertIs<SpeechRecognitionState.Failed>(service.recognition.value)
        assertEquals("SPEECH_RECOGNITION_FAILED", failed.failure.code)
        assertTrue("private" !in failed.failure.actionableMessage)
        assertTrue("path" !in failed.failure.actionableMessage)
        service.close()
    }

    private fun fixture(
        ready: Boolean = true,
        events: MutableList<String> = mutableListOf(),
    ): Fixture {
        val descriptor = descriptor()
        return Fixture(
            descriptor = descriptor,
            store = FakeModelStore(
                descriptor,
                temporaryFolder.newFolder("model-" + System.nanoTime()),
                ready,
                events,
            ),
            capture = FakeCapture(events),
            inference = FakeInference(events),
            playback = FakePlayback(),
        )
    }

    private data class Fixture(
        val descriptor: SpeechModelDescriptor,
        val store: FakeModelStore,
        val capture: FakeCapture,
        val inference: FakeInference,
        val playback: FakePlayback,
    ) {
        fun service(dispatcher: CoroutineDispatcher) = AndroidOfflineSpeechService(
            modelStore = store,
            capture = capture,
            inference = inference,
            audioPlayback = playback,
            dispatcher = dispatcher,
            clock = { TEST_TIME_MILLIS },
        )
    }

    private class FakeModelStore(
        private val descriptor: SpeechModelDescriptor,
        directory: File,
        private var ready: Boolean,
        private val events: MutableList<String>,
    ) : SpeechModelStore {
        private val installed = InstalledSpeechModel(descriptor, directory)
        private val mutableModels = MutableStateFlow(
            listOf(
                SpeechModelState(
                    descriptor,
                    if (ready) {
                        SpeechModelAvailability.Ready
                    } else {
                        SpeechModelAvailability.NotInstalled
                    },
                ),
            ),
        )
        val removed = mutableListOf<SpeechModelId>()

        override val models: StateFlow<List<SpeechModelState>> = mutableModels

        override suspend fun install(modelId: SpeechModelId) {
            require(modelId == descriptor.id)
            ready = true
            mutableModels.value =
                listOf(SpeechModelState(descriptor, SpeechModelAvailability.Ready))
        }

        override suspend fun cancelInstall(modelId: SpeechModelId) {
            require(modelId == descriptor.id)
        }

        override suspend fun remove(modelId: SpeechModelId) {
            require(modelId == descriptor.id)
            events += "store-remove"
            removed += modelId
            ready = false
            mutableModels.value =
                listOf(SpeechModelState(descriptor, SpeechModelAvailability.NotInstalled))
        }

        override suspend fun resolve(
            modelId: SpeechModelId,
            capability: SpeechModelCapability,
        ): InstalledSpeechModel? {
            require(modelId == descriptor.id)
            return installed.takeIf { ready && capability in descriptor.capabilities }
        }

        override fun close() = Unit
    }

    private class FakeCapture(
        private val events: MutableList<String>,
    ) : SpeechAudioCapture {
        private val channels = mutableMapOf<SpeechOperationId, Channel<SpeechPcmFrame>>()
        val captured = mutableListOf<SpeechOperationId>()
        val stopped = mutableListOf<SpeechOperationId>()

        override fun capture(operationId: SpeechOperationId): Flow<SpeechPcmFrame> {
            captured += operationId
            return Channel<SpeechPcmFrame>(Channel.UNLIMITED).also {
                check(channels.put(operationId, it) == null)
            }.receiveAsFlow()
        }

        suspend fun emit(
            operationId: SpeechOperationId,
            frame: SpeechPcmFrame,
        ) {
            checkNotNull(channels[operationId]).send(frame)
        }

        override suspend fun stop(operationId: SpeechOperationId) {
            events += "capture-stop"
            stopped += operationId
            channels[operationId]?.close()
        }

        override fun close() {
            channels.values.forEach { it.close() }
        }
    }

    private class FakeInference(
        private val events: MutableList<String>,
    ) : SpeechInferenceEngine {
        var transcript = "recognized"
        var transcriptionFailure: Throwable? = null
        var nonCooperativeOperation: SpeechOperationId? = null
        val releaseLateResult = CompletableDeferred<String>()
        val transcribedFrames = mutableListOf<List<SpeechPcmFrame>>()
        val synthesisRequests = mutableListOf<SpeechSynthesisRequest>()
        val cancelled = mutableListOf<SpeechOperationId>()

        override suspend fun transcribe(
            operationId: SpeechOperationId,
            model: InstalledSpeechModel,
            audio: Flow<SpeechPcmFrame>,
        ): String {
            check(model.descriptor.capabilities.contains(SpeechModelCapability.TRANSCRIPTION))
            val frames = audio.toList()
            transcribedFrames += frames
            transcriptionFailure?.let { throw it }
            return if (operationId == nonCooperativeOperation) {
                withContext(NonCancellable) { releaseLateResult.await() }
            } else {
                transcript
            }
        }

        override fun synthesize(request: SpeechSynthesisRequest): Flow<SpeechPcmFrame> {
            synthesisRequests += request
            return flow { emit(pcmFrame()) }
        }

        override suspend fun cancel(operationId: SpeechOperationId) {
            events += "inference-cancel"
            cancelled += operationId
        }

        override fun close() = Unit
    }

    private class FakePlayback : SpeechAudioPlayback {
        val frames = mutableListOf<SpeechPcmFrame>()
        val stopped = mutableListOf<SpeechOperationId>()
        var release: CompletableDeferred<Unit>? = null

        override suspend fun play(
            operationId: SpeechOperationId,
            audio: Flow<SpeechPcmFrame>,
        ) {
            audio.collect { frames += it }
            release?.await()
        }

        override suspend fun stop(operationId: SpeechOperationId) {
            stopped += operationId
            release?.complete(Unit)
        }

        override fun close() = Unit
    }

    private companion object {
        const val TEST_TIME_MILLIS = 1_234L

        fun pcmFrame() = SpeechPcmFrame(
            SpeechPcmFormat(sampleRateHz = 16_000, channelCount = 1),
            shortArrayOf(1, 2, 3),
        )

        fun descriptor() = SpeechModelDescriptor(
            id = SpeechModelId("test-speech"),
            displayName = "Test speech",
            version = "1",
            languageTags = setOf("en-US"),
            capabilities = setOf(
                SpeechModelCapability.TRANSCRIPTION,
                SpeechModelCapability.SYNTHESIS,
            ),
            license = SpeechModelLicense(
                name = "Test license",
                spdxIdentifier = "Apache-2.0",
                url = "https://licenses.example/test",
            ),
            modelPackage = SpeechModelPackage(
                downloadUrl = "https://models.example/test.tar.bz2",
                sha256 = "a".repeat(64),
                downloadSizeBytes = 1L,
                installedSizeBytes = 1L,
            ),
        )
    }
}
