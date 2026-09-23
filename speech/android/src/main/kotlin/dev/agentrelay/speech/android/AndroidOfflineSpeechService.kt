/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.speech.android

import dev.agentrelay.speech.api.MAX_SPEECH_TRANSCRIPT_CHARS
import dev.agentrelay.speech.api.OfflineSpeechService
import dev.agentrelay.speech.api.SpeechFailure
import dev.agentrelay.speech.api.SpeechModelCapability
import dev.agentrelay.speech.api.SpeechModelId
import dev.agentrelay.speech.api.SpeechModelState
import dev.agentrelay.speech.api.SpeechOperationId
import dev.agentrelay.speech.api.SpeechPlaybackState
import dev.agentrelay.speech.api.SpeechRecognitionState
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coordinates one generation-safe recognition operation and one playback operation.
 *
 * Model delivery, Android capture, native inference, and output routing remain injected. Errors
 * crossing this boundary are stable and redacted; exception messages are never exposed.
 */
class AndroidOfflineSpeechService(
    private val modelStore: SpeechModelStore,
    private val capture: SpeechAudioCapture,
    private val inference: SpeechInferenceEngine,
    private val audioPlayback: SpeechAudioPlayback,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
) : OfflineSpeechService {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val operationIds = AtomicLong(0L)
    private val stateMonitor = Any()
    private val recognitionStart = Mutex()
    private val playbackStart = Mutex()
    private var activeRecognition: ActiveRecognition? = null
    private var activePlayback: ActivePlayback? = null
    private var closed = false

    private val mutableRecognition =
        MutableStateFlow<SpeechRecognitionState>(SpeechRecognitionState.Idle)
    private val mutablePlayback = MutableStateFlow<SpeechPlaybackState>(SpeechPlaybackState.Idle)

    override val models: StateFlow<List<SpeechModelState>> = modelStore.models
    override val recognition: StateFlow<SpeechRecognitionState> =
        mutableRecognition.asStateFlow()
    override val playback: StateFlow<SpeechPlaybackState> = mutablePlayback.asStateFlow()

    override suspend fun installModel(modelId: SpeechModelId) {
        checkOpen()
        modelStore.install(modelId)
    }

    override suspend fun cancelModelInstall(modelId: SpeechModelId) {
        checkOpen()
        modelStore.cancelInstall(modelId)
    }

    override suspend fun removeModel(modelId: SpeechModelId) =
        recognitionStart.withLock {
            playbackStart.withLock {
                checkOpen()
                synchronized(stateMonitor) {
                    activeRecognition?.takeIf { it.modelId == modelId }?.operationId
                }?.let { cancelListening(it) }
                synchronized(stateMonitor) {
                    activePlayback?.takeIf { it.modelId == modelId }?.operationId
                }?.let { stopSpeaking(it) }
                modelStore.remove(modelId)
            }
        }

    override suspend fun startListening(modelId: SpeechModelId): SpeechOperationId =
        recognitionStart.withLock {
            checkOpen()
            check(synchronized(stateMonitor) { activeRecognition == null }) {
                "A speech recognition operation is already active"
            }
            val operationId = nextOperationId()
            val model = try {
                modelStore.resolve(modelId, SpeechModelCapability.TRANSCRIPTION)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                publishRecognitionFailure(
                    operationId,
                    modelId,
                    failure("MODEL_UNAVAILABLE", "Retry after checking the speech model."),
                )
                return@withLock operationId
            }
            currentCoroutineContext().ensureActive()
            if (model == null) {
                publishRecognitionFailure(
                    operationId,
                    modelId,
                    failure("MODEL_NOT_READY", "Install the transcription model before listening."),
                )
                return@withLock operationId
            }

            lateinit var active: ActiveRecognition
            val job = scope.launch(start = CoroutineStart.LAZY) {
                runRecognition(active, model)
            }
            active = ActiveRecognition(operationId, modelId, job)
            synchronized(stateMonitor) {
                checkOpenLocked()
                check(activeRecognition == null) {
                    "A speech recognition operation is already active"
                }
                activeRecognition = active
                mutableRecognition.value = SpeechRecognitionState.Listening(
                    operationId = operationId,
                    modelId = modelId,
                    startedAtEpochMillis = clock().coerceAtLeast(0L),
                )
            }
            job.start()
            operationId
        }

    override suspend fun stopListening(operationId: SpeechOperationId) {
        val active = synchronized(stateMonitor) {
            activeRecognition?.takeIf { it.operationId == operationId }?.also {
                mutableRecognition.value =
                    SpeechRecognitionState.Transcribing(operationId, it.modelId)
            }
        } ?: return

        try {
            withContext(NonCancellable) { capture.stop(operationId) }
        } catch (_: Throwable) {
            failRecognition(
                active,
                failure("SPEECH_CAPTURE_FAILED", "Check microphone access and try again."),
            )
        }
    }

    override suspend fun cancelListening(operationId: SpeechOperationId) {
        val active = detachRecognition(operationId) ?: return
        cancelRecognition(active)
    }

    override suspend fun speak(
        modelId: SpeechModelId,
        text: String,
    ): SpeechOperationId = playbackStart.withLock {
        checkOpen()
        check(synchronized(stateMonitor) { activePlayback == null }) {
            "A speech playback operation is already active"
        }
        val operationId = nextOperationId()
        val model = try {
            modelStore.resolve(modelId, SpeechModelCapability.SYNTHESIS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            publishPlaybackFailure(
                operationId,
                modelId,
                failure("MODEL_UNAVAILABLE", "Retry after checking the speech model."),
            )
            return@withLock operationId
        }
        currentCoroutineContext().ensureActive()
        if (model == null) {
            publishPlaybackFailure(
                operationId,
                modelId,
                failure("MODEL_NOT_READY", "Install the speech voice before playback."),
            )
            return@withLock operationId
        }

        val request = try {
            SpeechSynthesisRequest(operationId, model, text)
        } catch (_: IllegalArgumentException) {
            publishPlaybackFailure(
                operationId,
                modelId,
                failure("SPEECH_TEXT_INVALID", "Choose non-empty text within the playback limit."),
            )
            return@withLock operationId
        }

        lateinit var active: ActivePlayback
        val job = scope.launch(start = CoroutineStart.LAZY) {
            runPlayback(active, request)
        }
        active = ActivePlayback(operationId, modelId, job)
        synchronized(stateMonitor) {
            checkOpenLocked()
            check(activePlayback == null) { "A speech playback operation is already active" }
            activePlayback = active
            mutablePlayback.value =
                SpeechPlaybackState.Synthesizing(operationId, modelId, text.length)
        }
        job.start()
        operationId
    }

    override suspend fun stopSpeaking(operationId: SpeechOperationId) {
        val active = detachPlayback(operationId) ?: return
        cancelPlayback(active)
    }

    override fun close() {
        val operations = synchronized(stateMonitor) {
            if (closed) {
                return
            }
            closed = true
            val result = activeRecognition to activePlayback
            activeRecognition = null
            activePlayback = null
            mutableRecognition.value = SpeechRecognitionState.Idle
            mutablePlayback.value = SpeechPlaybackState.Idle
            result
        }
        // AutoCloseable cannot be suspend. Stop platform/native operations synchronously before
        // cancelling the coordinator scope so a lifecycle close cannot leave microphone, audio,
        // or inference work running after the owning screen has gone away.
        runBlocking(Dispatchers.IO) {
            operations.first?.let { stopRecognitionDependencies(it.operationId) }
            operations.second?.let { stopPlaybackDependencies(it.operationId) }
        }
        operations.first?.job?.cancel()
        operations.second?.job?.cancel()
        scope.cancel()
        listOf(
            { capture.close() },
            { inference.close() },
            { audioPlayback.close() },
            { modelStore.close() },
        ).forEach { closeAction -> runCatching(closeAction) }
    }

    private suspend fun runRecognition(
        active: ActiveRecognition,
        model: InstalledSpeechModel,
    ) {
        try {
            val audio = capture.capture(active.operationId).catch {
                throw SpeechPipelineException(
                    "SPEECH_CAPTURE_FAILED",
                    "Check microphone access and try again.",
                )
            }
            val transcript = inference.transcribe(active.operationId, model, audio)
            if (transcript.isBlank() || transcript.length > MAX_SPEECH_TRANSCRIPT_CHARS) {
                throw SpeechPipelineException(
                    "SPEECH_RESULT_INVALID",
                    "No usable speech result was produced. Try again.",
                )
            }
            synchronized(stateMonitor) {
                if (activeRecognition === active) {
                    mutableRecognition.value =
                        SpeechRecognitionState.Transcribing(active.operationId, active.modelId)
                    mutableRecognition.value = SpeechRecognitionState.Result(
                        active.operationId,
                        active.modelId,
                        transcript,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SpeechPipelineException) {
            publishRecognitionFailure(active, failure.toFailure())
            stopRecognitionDependencies(active.operationId)
        } catch (_: Throwable) {
            publishRecognitionFailure(
                active,
                failure("SPEECH_RECOGNITION_FAILED", "Check the model and try listening again."),
            )
            stopRecognitionDependencies(active.operationId)
        } finally {
            synchronized(stateMonitor) {
                if (activeRecognition === active) {
                    activeRecognition = null
                }
            }
        }
    }

    private suspend fun runPlayback(
        active: ActivePlayback,
        request: SpeechSynthesisRequest,
    ) {
        try {
            var emittedAudio = false
            val audio = synthesisAudio(request).onEach {
                if (!emittedAudio) {
                    emittedAudio = true
                    synchronized(stateMonitor) {
                        if (activePlayback === active) {
                            mutablePlayback.value =
                                SpeechPlaybackState.Playing(active.operationId, active.modelId)
                        }
                    }
                }
            }
            playAudio(active.operationId, audio)
            requireAudio(emittedAudio)
            synchronized(stateMonitor) {
                if (activePlayback === active) {
                    activePlayback = null
                    mutablePlayback.value = SpeechPlaybackState.Idle
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: SpeechPipelineException) {
            publishPlaybackFailure(active, failure.toFailure())
            stopPlaybackDependencies(active.operationId)
        } catch (_: Throwable) {
            publishPlaybackFailure(
                active,
                failure("SPEECH_PLAYBACK_FAILED", "Check audio output and try playback again."),
            )
            stopPlaybackDependencies(active.operationId)
        } finally {
            synchronized(stateMonitor) {
                if (activePlayback === active) {
                    activePlayback = null
                }
            }
        }
    }

    private fun synthesisAudio(request: SpeechSynthesisRequest): Flow<SpeechPcmFrame> {
        val audio = try {
            inference.synthesize(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw SpeechPipelineException(
                "SPEECH_SYNTHESIS_FAILED",
                "Check the speech voice and try again.",
            )
        }
        return audio.catch {
            throw SpeechPipelineException(
                "SPEECH_SYNTHESIS_FAILED",
                "Check the speech voice and try again.",
            )
        }
    }

    private suspend fun playAudio(
        operationId: SpeechOperationId,
        audio: Flow<SpeechPcmFrame>,
    ) {
        try {
            audioPlayback.play(operationId, audio)
        } catch (failure: SpeechPipelineException) {
            throw failure
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            throw SpeechPipelineException(
                "SPEECH_PLAYBACK_FAILED",
                "Check audio output and try playback again.",
            )
        }
    }

    private fun requireAudio(emittedAudio: Boolean) {
        if (!emittedAudio) {
            throw SpeechPipelineException(
                "SPEECH_OUTPUT_EMPTY",
                "The speech voice produced no playable audio.",
            )
        }
    }

    private fun detachRecognition(operationId: SpeechOperationId): ActiveRecognition? =
        synchronized(stateMonitor) {
            activeRecognition?.takeIf { it.operationId == operationId }?.also {
                activeRecognition = null
                mutableRecognition.value = SpeechRecognitionState.Idle
            }
        }

    private fun detachPlayback(operationId: SpeechOperationId): ActivePlayback? =
        synchronized(stateMonitor) {
            activePlayback?.takeIf { it.operationId == operationId }?.also {
                activePlayback = null
                mutablePlayback.value = SpeechPlaybackState.Idle
            }
        }

    private suspend fun cancelRecognition(active: ActiveRecognition) {
        stopRecognitionDependencies(active.operationId)
        withContext(NonCancellable) {
            active.job.cancelAndJoin()
        }
    }

    private suspend fun cancelPlayback(active: ActivePlayback) {
        stopPlaybackDependencies(active.operationId)
        withContext(NonCancellable) {
            active.job.cancelAndJoin()
        }
    }

    private suspend fun stopRecognitionDependencies(operationId: SpeechOperationId) {
        withContext(NonCancellable) {
            runCatching { capture.stop(operationId) }
            runCatching { inference.cancel(operationId) }
        }
    }

    private suspend fun stopPlaybackDependencies(operationId: SpeechOperationId) {
        withContext(NonCancellable) {
            runCatching { audioPlayback.stop(operationId) }
            runCatching { inference.cancel(operationId) }
        }
    }

    private suspend fun failRecognition(
        active: ActiveRecognition,
        speechFailure: SpeechFailure,
    ) {
        val detached = synchronized(stateMonitor) {
            if (activeRecognition === active) {
                activeRecognition = null
                mutableRecognition.value =
                    SpeechRecognitionState.Failed(
                        active.operationId,
                        active.modelId,
                        speechFailure,
                    )
                true
            } else {
                false
            }
        }
        if (detached) {
            cancelRecognition(active)
        }
    }

    private fun publishRecognitionFailure(
        active: ActiveRecognition,
        speechFailure: SpeechFailure,
    ) {
        synchronized(stateMonitor) {
            if (activeRecognition === active) {
                mutableRecognition.value = SpeechRecognitionState.Failed(
                    active.operationId,
                    active.modelId,
                    speechFailure,
                )
            }
        }
    }

    private fun publishRecognitionFailure(
        operationId: SpeechOperationId,
        modelId: SpeechModelId,
        speechFailure: SpeechFailure,
    ) {
        synchronized(stateMonitor) {
            if (!closed && activeRecognition == null) {
                mutableRecognition.value =
                    SpeechRecognitionState.Failed(operationId, modelId, speechFailure)
            }
        }
    }

    private fun publishPlaybackFailure(
        active: ActivePlayback,
        speechFailure: SpeechFailure,
    ) {
        synchronized(stateMonitor) {
            if (activePlayback === active) {
                mutablePlayback.value =
                    SpeechPlaybackState.Failed(active.operationId, active.modelId, speechFailure)
            }
        }
    }

    private fun publishPlaybackFailure(
        operationId: SpeechOperationId,
        modelId: SpeechModelId,
        speechFailure: SpeechFailure,
    ) {
        synchronized(stateMonitor) {
            if (!closed && activePlayback == null) {
                mutablePlayback.value =
                    SpeechPlaybackState.Failed(operationId, modelId, speechFailure)
            }
        }
    }

    private fun nextOperationId(): SpeechOperationId {
        val value = operationIds.incrementAndGet()
        check(value > 0L) { "Speech operation ids are exhausted" }
        return SpeechOperationId(value)
    }

    private fun checkOpen() = synchronized(stateMonitor) {
        checkOpenLocked()
    }

    private fun checkOpenLocked() {
        check(!closed) { "Offline speech service is closed" }
    }

    private data class ActiveRecognition(
        val operationId: SpeechOperationId,
        val modelId: SpeechModelId,
        val job: Job,
    )

    private data class ActivePlayback(
        val operationId: SpeechOperationId,
        val modelId: SpeechModelId,
        val job: Job,
    )

    private class SpeechPipelineException(
        val code: String,
        val guidance: String,
    ) : RuntimeException()

    private companion object {
        fun failure(
            code: String,
            guidance: String,
        ) = SpeechFailure(code, guidance)

        fun SpeechPipelineException.toFailure() = failure(code, guidance)
    }
}
