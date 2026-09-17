/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import com.example.agentrelay.R
import dev.agentrelay.session.api.SessionDraft
import dev.agentrelay.speech.api.OfflineSpeechService
import dev.agentrelay.speech.api.SpeechFailure
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechInputControllerTest {
    @Test
    fun missingRuntimeIsTruthfullyUnavailable() = runTest {
        val errors = mutableListOf<UiMessage>()
        val controller = SpeechInputController(this, null, errors::add)

        assertEquals(SpeechInputPhase.UNAVAILABLE, controller.state.value.phase)
        assertFalse(controller.state.value.canStart)
        assertTrue(controller.state.value.models.isEmpty())
        assertEquals(
            UiMessage.Localized(R.string.speech_status_unavailable_build),
            controller.state.value.statusMessage,
        )

        controller.startListening("session-a")
        runCurrent()

        assertTrue(errors.isEmpty())
        controller.close()
    }

    @Test
    fun emptyCatalogReportsLocalizedNoModelState() = runTest {
        val controller = SpeechInputController(
            scope = this,
            service = FakeOfflineSpeechService(emptyList()),
            reportError = {},
        )

        assertEquals(SpeechInputPhase.UNAVAILABLE, controller.state.value.phase)
        assertEquals(
            UiMessage.Localized(R.string.speech_status_no_model),
            controller.state.value.statusMessage,
        )

        controller.close()
    }

    @Test
    fun modelLifecycleExposesBoundedActionableState() = runTest {
        val transcription = descriptor("voice.test", SpeechModelCapability.TRANSCRIPTION)
        val synthesis = descriptor("voice.synthesis", SpeechModelCapability.SYNTHESIS)
        val service = FakeOfflineSpeechService(
            listOf(
                SpeechModelState(transcription, SpeechModelAvailability.NotInstalled),
                SpeechModelState(synthesis, SpeechModelAvailability.Ready),
            ),
        )
        val errors = mutableListOf<UiMessage>()
        val controller = SpeechInputController(this, service, errors::add)

        assertEquals(SpeechInputPhase.MODEL_REQUIRED, controller.state.value.phase)
        assertEquals(listOf("voice.test"), controller.state.value.models.map { it.id })
        assertEquals(
            UiMessage.Localized(R.string.speech_status_install_model),
            controller.state.value.statusMessage,
        )
        assertTrue(controller.state.value.canInstall)

        controller.installSelectedModel()
        runCurrent()
        assertEquals(listOf(SpeechModelId("voice.test")), service.installCalls)

        service.modelStates.value = listOf(
            SpeechModelState(
                transcription,
                SpeechModelAvailability.Downloading(50L, 200L),
            ),
            SpeechModelState(synthesis, SpeechModelAvailability.Ready),
        )
        runCurrent()
        assertEquals(SpeechInputPhase.INSTALLING, controller.state.value.phase)
        assertEquals(25, controller.state.value.progressPercent)
        assertEquals(
            UiMessage.Localized(R.string.speech_status_downloading_model),
            controller.state.value.statusMessage,
        )
        assertTrue(controller.state.value.canCancelInstall)

        controller.cancelSelectedModelInstall()
        runCurrent()
        assertEquals(listOf(SpeechModelId("voice.test")), service.cancelInstallCalls)

        service.modelStates.value = listOf(
            SpeechModelState(transcription, SpeechModelAvailability.Ready),
        )
        runCurrent()
        assertEquals(SpeechInputPhase.READY, controller.state.value.phase)
        assertTrue(controller.state.value.canStart)
        assertEquals(
            UiMessage.Localized(R.string.speech_status_ready_private),
            controller.state.value.statusMessage,
        )
        assertTrue(errors.isEmpty())
        controller.close()
    }

    @Test
    fun controllerOperationFailuresExposeLocalizedMessages() = runTest {
        val model = descriptor("voice.test", SpeechModelCapability.TRANSCRIPTION)
        val service = FakeOfflineSpeechService(
            listOf(SpeechModelState(model, SpeechModelAvailability.NotInstalled)),
        )
        val errors = mutableListOf<UiMessage>()
        val controller = SpeechInputController(this, service, errors::add)

        service.installFailure = IllegalStateException("private install path")
        controller.installSelectedModel()
        runCurrent()
        service.cancelInstallFailure = IllegalStateException("private download path")
        controller.cancelSelectedModelInstall()
        runCurrent()
        controller.startListening("session-a")
        service.modelStates.value = listOf(SpeechModelState(model, SpeechModelAvailability.Ready))
        runCurrent()
        service.stopFailure = IllegalStateException("private stop state")
        controller.startListening("session-a")
        runCurrent()
        controller.stopListening("session-a")
        runCurrent()
        service.cancelFailure = IllegalStateException("private cancel state")
        controller.cancelListening("session-a")
        runCurrent()

        assertEquals(
            listOf(
                UiMessage.Localized(R.string.speech_error_model_install),
                UiMessage.Localized(R.string.speech_error_model_download_cancel),
                UiMessage.Localized(R.string.speech_error_model_required),
                UiMessage.Localized(R.string.speech_error_stop),
                UiMessage.Localized(R.string.speech_error_cancel),
            ),
            errors,
        )
        assertFalse(errors.joinToString().contains("private"))
        controller.close()
    }

    @Test
    fun staleStartCancellationFailureExposesLocalizedMessage() = runTest {
        val pendingStart = CompletableDeferred<SpeechOperationId>()
        val service = readyService().apply {
            deferredStart = pendingStart
            cancelFailure = IllegalStateException("private previous session")
        }
        val errors = mutableListOf<UiMessage>()
        val controller = SpeechInputController(this, service, errors::add)

        controller.startListening("session-a")
        runCurrent()
        controller.cancelForSessionChange("session-b")
        pendingStart.complete(SpeechOperationId(42L))
        runCurrent()

        assertEquals(
            listOf(UiMessage.Localized(R.string.speech_error_previous_session_cancel)),
            errors,
        )
        assertFalse(errors.single().toString().contains("private"))
        controller.close()
    }

    @Test
    fun recognitionIsGenerationBoundAndRequiresExplicitConsumption() = runTest {
        val service = readyService()
        val controller = SpeechInputController(this, service) {}

        controller.startListening("session-a")
        assertEquals(SpeechInputPhase.STARTING, controller.state.value.phase)
        assertEquals(
            UiMessage.Localized(R.string.speech_status_starting),
            controller.state.value.statusMessage,
        )
        runCurrent()

        val operationId = SpeechOperationId(1L)
        assertEquals(
            SpeechRecognitionState.Listening(operationId, SpeechModelId("voice.test"), 10L),
            service.recognition.value,
        )
        assertEquals(SpeechInputPhase.LISTENING, controller.state.value.phase)
        assertEquals(
            UiMessage.Localized(R.string.speech_status_listening),
            controller.state.value.statusMessage,
        )
        assertEquals("session-a", controller.state.value.targetSessionKey)

        service.recognitionState.value = SpeechRecognitionState.Result(
            SpeechOperationId(99L),
            SpeechModelId("voice.test"),
            "stale transcript",
        )
        runCurrent()
        assertEquals(SpeechInputPhase.STARTING, controller.state.value.phase)
        assertEquals(
            UiMessage.Localized(R.string.speech_status_waiting),
            controller.state.value.statusMessage,
        )
        assertNull(controller.consumeTranscript("session-a"))

        service.recognitionState.value = SpeechRecognitionState.Result(
            operationId,
            SpeechModelId("voice.test"),
            "reviewed transcript",
        )
        runCurrent()
        assertEquals(SpeechInputPhase.RESULT, controller.state.value.phase)
        assertEquals("reviewed transcript", controller.state.value.transcript)
        assertEquals(
            UiMessage.Localized(R.string.speech_status_review_transcript),
            controller.state.value.statusMessage,
        )
        assertNull(controller.consumeTranscript("session-b"))
        assertEquals("reviewed transcript", controller.consumeTranscript("session-a"))
        assertNull(controller.consumeTranscript("session-a"))
        assertEquals(SpeechInputPhase.READY, controller.state.value.phase)
        assertEquals(
            UiMessage.Localized(R.string.speech_status_ready_private),
            controller.state.value.statusMessage,
        )
        controller.close()
    }

    @Test
    fun recognitionFailureKeepsEngineGuidanceVerbatim() = runTest {
        val service = readyService()
        val controller = SpeechInputController(this, service) {}

        controller.startListening("session-a")
        runCurrent()
        service.recognitionState.value = SpeechRecognitionState.Failed(
            operationId = SpeechOperationId(1L),
            modelId = SpeechModelId("voice.test"),
            failure = SpeechFailure(
                code = "AUDIO_INPUT",
                actionableMessage = "Reconnect the microphone and retry.",
            ),
        )
        runCurrent()

        assertEquals(SpeechInputPhase.FAILED, controller.state.value.phase)
        assertEquals(
            UiMessage.Verbatim("Reconnect the microphone and retry."),
            controller.state.value.statusMessage,
        )
        controller.close()
    }

    @Test
    fun stopAndSessionChangeUseOnlyTheCurrentOperation() = runTest {
        val service = readyService()
        val controller = SpeechInputController(this, service) {}

        controller.startListening("session-a")
        runCurrent()
        controller.stopListening("session-a")
        runCurrent()

        assertEquals(listOf(SpeechOperationId(1L)), service.stopCalls)
        assertEquals(SpeechInputPhase.TRANSCRIBING, controller.state.value.phase)
        assertEquals(
            UiMessage.Localized(R.string.speech_status_transcribing),
            controller.state.value.statusMessage,
        )

        controller.cancelForSessionChange("session-b")
        runCurrent()

        assertEquals(listOf(SpeechOperationId(1L)), service.cancelCalls)
        assertEquals(SpeechInputPhase.READY, controller.state.value.phase)
        assertEquals(
            UiMessage.Localized(R.string.speech_status_ready_private),
            controller.state.value.statusMessage,
        )
        controller.close()
    }

    @Test
    fun lateNonCooperativeStartIsCancelledAfterSessionChange() = runTest {
        val operationId = SpeechOperationId(42L)
        val deferredStart = CompletableDeferred<SpeechOperationId>()
        val service = readyService().apply {
            this.deferredStart = deferredStart
        }
        val controller = SpeechInputController(this, service) {}

        controller.startListening("session-a")
        runCurrent()
        controller.cancelForSessionChange("session-b")
        runCurrent()
        deferredStart.complete(operationId)
        runCurrent()

        assertEquals(listOf(operationId), service.cancelCalls)
        assertEquals(SpeechInputPhase.READY, controller.state.value.phase)
        assertNull(controller.state.value.targetSessionKey)
        assertNull(controller.state.value.operationId)
        controller.close()
    }

    @Test
    fun pendingStartCanBeCancelledExplicitly() = runTest {
        val operationId = SpeechOperationId(43L)
        val deferredStart = CompletableDeferred<SpeechOperationId>()
        val service = readyService().apply {
            this.deferredStart = deferredStart
        }
        val controller = SpeechInputController(this, service) {}

        controller.startListening("session-a")
        runCurrent()
        controller.cancelListening("session-a")
        runCurrent()
        deferredStart.complete(operationId)
        runCurrent()

        assertEquals(listOf(operationId), service.cancelCalls)
        assertEquals(SpeechInputPhase.READY, controller.state.value.phase)
        assertNull(controller.state.value.targetSessionKey)
        assertNull(controller.state.value.operationId)
        controller.close()
    }

    @Test
    fun unexpectedFailuresAreRedactedBeforeUiReporting() = runTest {
        val service = readyService().apply {
            startFailure = IllegalStateException("private model path")
        }
        val errors = mutableListOf<UiMessage>()
        val controller = SpeechInputController(this, service, errors::add)

        controller.startListening("session-a")
        runCurrent()

        assertEquals(
            listOf(UiMessage.Localized(R.string.speech_error_start)),
            errors,
        )
        assertFalse(errors.single().toString().contains("private"))
        assertEquals(SpeechInputPhase.READY, controller.state.value.phase)
        controller.close()
    }

    @Test
    fun modelFailureUsesOnlyTheStableActionableGuidance() = runTest {
        val descriptor = descriptor("voice.test", SpeechModelCapability.TRANSCRIPTION)
        val service = FakeOfflineSpeechService(
            listOf(
                SpeechModelState(
                    descriptor,
                    SpeechModelAvailability.Failed(
                        SpeechFailure("MODEL_STORAGE_FULL", "Free app storage and retry."),
                    ),
                ),
            ),
        )
        val controller = SpeechInputController(this, service) {}

        assertEquals(SpeechInputPhase.FAILED, controller.state.value.phase)
        assertEquals(
            UiMessage.Verbatim("Free app storage and retry."),
            controller.state.value.statusMessage,
        )
        assertTrue(controller.state.value.canInstall)
        assertFalse(controller.state.value.canDismiss)
        controller.close()
    }

    @Test
    fun closeIsIdempotentAndClosesOwnedService() = runTest {
        val service = readyService()
        val controller = SpeechInputController(this, service) {}

        controller.close()
        controller.close()

        assertEquals(1, service.closeCalls)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechInputActionsTest {
    @Test
    fun fakeRecognizerKeepsTranscriptOutOfDraftUntilExplicitUse() = runTest {
        val service = readyService()
        val controller = SpeechInputController(this, service) {}
        val draft = SessionDraft("existing", 8, 8, 1L)
        var updatedDraft: SessionDraft? = null
        val actions = SpeechInputActions(
            controller = controller,
            resolveDraft = { draft },
            updateDraft = { _, text, start, end ->
                updatedDraft = SessionDraft(text, start, end, 2L)
            },
            reportError = { error("Unexpected speech error: $it") },
        )

        controller.startListening("session-a")
        runCurrent()
        service.recognitionState.value = SpeechRecognitionState.Result(
            SpeechOperationId(1L),
            SpeechModelId("voice.test"),
            " voice result",
        )
        runCurrent()

        assertNull(updatedDraft)
        actions.useTranscript("session-a")

        assertEquals(SessionDraft("existing voice result", 21, 21, 2L), updatedDraft)
        assertEquals(listOf(SpeechModelId("voice.test")), service.startCalls)
        controller.close()
    }

    @Test
    fun permissionAndUnavailableTargetsExposeLocalizedMessages() = runTest {
        val service = readyService()
        val controller = SpeechInputController(this, service) {}
        val errors = mutableListOf<UiMessage>()
        val actions = SpeechInputActions(
            controller = controller,
            resolveDraft = { null },
            updateDraft = { _, _, _, _ -> error("Unexpected draft update") },
            reportError = errors::add,
        )

        actions.permissionDenied()
        actions.useTranscript("session-a")
        controller.startListening("session-a")
        runCurrent()
        service.recognitionState.value = SpeechRecognitionState.Result(
            SpeechOperationId(1L),
            SpeechModelId("voice.test"),
            "reviewed transcript",
        )
        runCurrent()
        actions.useTranscript("session-a")

        assertEquals(
            listOf(
                UiMessage.Localized(R.string.speech_error_microphone_permission),
                UiMessage.Localized(R.string.speech_error_transcript_unavailable),
                UiMessage.Localized(R.string.speech_error_session_unavailable),
            ),
            errors,
        )
        controller.close()
    }

    @Test
    fun transcriptConsumptionRaceExposesLocalizedMessage() = runTest {
        val service = readyService()
        val controller = SpeechInputController(this, service) {}
        val errors = mutableListOf<UiMessage>()
        val draft = SessionDraft("", 0, 0, 1L)
        val actions = SpeechInputActions(
            controller = controller,
            resolveDraft = {
                controller.consumeTranscript("session-a")
                draft
            },
            updateDraft = { _, _, _, _ -> error("Unexpected draft update") },
            reportError = errors::add,
        )

        controller.startListening("session-a")
        runCurrent()
        service.recognitionState.value = SpeechRecognitionState.Result(
            SpeechOperationId(1L),
            SpeechModelId("voice.test"),
            "reviewed transcript",
        )
        runCurrent()
        actions.useTranscript("session-a")

        assertEquals(
            listOf(UiMessage.Localized(R.string.speech_error_transcript_changed)),
            errors,
        )
        assertEquals(SpeechInputPhase.READY, controller.state.value.phase)
        controller.close()
    }

    @Test
    fun reviewedTranscriptReplacesOnlyTheCurrentSelection() = runTest {
        val service = readyService()
        val controller = SpeechInputController(this, service) {}
        val originalDraft = SessionDraft(
            text = "hello world",
            selectionStart = 6,
            selectionEnd = 11,
            updatedAtEpochMillis = 1L,
        )
        var updatedDraft: SessionDraft? = null
        val errors = mutableListOf<UiMessage>()
        val actions = SpeechInputActions(
            controller = controller,
            resolveDraft = { sessionKey ->
                originalDraft.takeIf { sessionKey == "session-a" }
            },
            updateDraft = { _, text, selectionStart, selectionEnd ->
                updatedDraft = SessionDraft(text, selectionStart, selectionEnd, 2L)
            },
            reportError = errors::add,
        )

        controller.startListening("session-a")
        runCurrent()
        service.recognitionState.value = SpeechRecognitionState.Result(
            SpeechOperationId(1L),
            SpeechModelId("voice.test"),
            "agent",
        )
        runCurrent()
        actions.useTranscript("session-a")

        assertEquals(
            SessionDraft("hello agent", 11, 11, 2L),
            updatedDraft,
        )
        assertEquals(SpeechInputPhase.READY, controller.state.value.phase)
        assertTrue(errors.isEmpty())
        controller.close()
    }

    @Test
    fun oversizedTranscriptInsertionLeavesReviewedResultAvailable() = runTest {
        val service = readyService()
        val controller = SpeechInputController(this, service) {}
        val originalDraft = SessionDraft(
            text = "a".repeat(MAX_SESSION_DRAFT_CHARS),
            selectionStart = 0,
            selectionEnd = 0,
            updatedAtEpochMillis = 1L,
        )
        var updatedDraft: SessionDraft? = null
        val errors = mutableListOf<UiMessage>()
        val actions = SpeechInputActions(
            controller = controller,
            resolveDraft = { originalDraft },
            updateDraft = { _, text, selectionStart, selectionEnd ->
                updatedDraft = SessionDraft(text, selectionStart, selectionEnd, 2L)
            },
            reportError = errors::add,
        )

        controller.startListening("session-a")
        runCurrent()
        service.recognitionState.value = SpeechRecognitionState.Result(
            SpeechOperationId(1L),
            SpeechModelId("voice.test"),
            "x",
        )
        runCurrent()
        actions.useTranscript("session-a")

        assertNull(updatedDraft)
        assertEquals(SpeechInputPhase.RESULT, controller.state.value.phase)
        assertEquals(
            listOf(
                UiMessage.Plural(
                    R.plurals.speech_error_transcript_too_long,
                    MAX_SESSION_DRAFT_CHARS,
                    listOf(MAX_SESSION_DRAFT_CHARS),
                ),
            ),
            errors,
        )
        controller.close()
    }
}

private class FakeOfflineSpeechService(
    initialModels: List<SpeechModelState>,
) : OfflineSpeechService {
    val modelStates = MutableStateFlow(initialModels)
    val recognitionState =
        MutableStateFlow<SpeechRecognitionState>(SpeechRecognitionState.Idle)
    val playbackState = MutableStateFlow<SpeechPlaybackState>(SpeechPlaybackState.Idle)
    val installCalls = mutableListOf<SpeechModelId>()
    val cancelInstallCalls = mutableListOf<SpeechModelId>()
    val startCalls = mutableListOf<SpeechModelId>()
    val stopCalls = mutableListOf<SpeechOperationId>()
    val cancelCalls = mutableListOf<SpeechOperationId>()
    var installFailure: Throwable? = null
    var cancelInstallFailure: Throwable? = null
    var stopFailure: Throwable? = null
    var cancelFailure: Throwable? = null
    var startFailure: Throwable? = null
    var deferredStart: CompletableDeferred<SpeechOperationId>? = null
    var closeCalls = 0
    private var nextOperationId = 0L

    override val models: StateFlow<List<SpeechModelState>> = modelStates.asStateFlow()
    override val recognition: StateFlow<SpeechRecognitionState> = recognitionState.asStateFlow()
    override val playback: StateFlow<SpeechPlaybackState> = playbackState.asStateFlow()

    override suspend fun installModel(modelId: SpeechModelId) {
        installFailure?.let { throw it }
        installCalls += modelId
    }

    override suspend fun cancelModelInstall(modelId: SpeechModelId) {
        cancelInstallFailure?.let { throw it }
        cancelInstallCalls += modelId
    }

    override suspend fun removeModel(modelId: SpeechModelId) = Unit

    override suspend fun startListening(modelId: SpeechModelId): SpeechOperationId {
        startFailure?.let { throw it }
        startCalls += modelId
        val operationId = deferredStart?.let { pending ->
            withContext(NonCancellable) { pending.await() }
        } ?: SpeechOperationId(++nextOperationId)
        recognitionState.value = SpeechRecognitionState.Listening(
            operationId = operationId,
            modelId = modelId,
            startedAtEpochMillis = 10L,
        )
        return operationId
    }

    override suspend fun stopListening(operationId: SpeechOperationId) {
        stopFailure?.let { throw it }
        stopCalls += operationId
        val listening = recognitionState.value as? SpeechRecognitionState.Listening ?: return
        if (listening.operationId == operationId) {
            recognitionState.value =
                SpeechRecognitionState.Transcribing(operationId, listening.modelId)
        }
    }

    override suspend fun cancelListening(operationId: SpeechOperationId) {
        cancelFailure?.let { throw it }
        cancelCalls += operationId
        if (recognitionState.value.operationIdOrNullForTest() == operationId) {
            recognitionState.value = SpeechRecognitionState.Idle
        }
    }

    override suspend fun speak(
        modelId: SpeechModelId,
        text: String,
    ): SpeechOperationId = SpeechOperationId(++nextOperationId)

    override suspend fun stopSpeaking(operationId: SpeechOperationId) = Unit

    override fun close() {
        closeCalls += 1
    }
}

private fun readyService() = FakeOfflineSpeechService(
    listOf(
        SpeechModelState(
            descriptor("voice.test", SpeechModelCapability.TRANSCRIPTION),
            SpeechModelAvailability.Ready,
        ),
    ),
)

private fun descriptor(
    id: String,
    capability: SpeechModelCapability,
) = SpeechModelDescriptor(
    id = SpeechModelId(id),
    displayName = "Test voice",
    version = "1",
    languageTags = setOf("en"),
    capabilities = setOf(capability),
    license = SpeechModelLicense(
        name = "Apache License 2.0",
        spdxIdentifier = "Apache-2.0",
        url = "https://www.apache.org/licenses/LICENSE-2.0",
    ),
    modelPackage = SpeechModelPackage(
        downloadUrl = "https://example.com/model.tar.bz2",
        sha256 = "a".repeat(64),
        downloadSizeBytes = 100L,
        installedSizeBytes = 200L,
    ),
)

private fun SpeechRecognitionState.operationIdOrNullForTest(): SpeechOperationId? = when (this) {
    is SpeechRecognitionState.Listening -> operationId
    is SpeechRecognitionState.Transcribing -> operationId
    is SpeechRecognitionState.Result -> operationId
    is SpeechRecognitionState.Failed -> operationId
    SpeechRecognitionState.Idle -> null
}
