package com.example.agentrelay.ui.main

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
        val errors = mutableListOf<String>()
        val controller = SpeechInputController(this, null, errors::add)

        assertEquals(SpeechInputPhase.UNAVAILABLE, controller.state.value.phase)
        assertFalse(controller.state.value.canStart)
        assertTrue(controller.state.value.models.isEmpty())

        controller.startListening("session-a")
        runCurrent()

        assertTrue(errors.isEmpty())
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
        val errors = mutableListOf<String>()
        val controller = SpeechInputController(this, service, errors::add)

        assertEquals(SpeechInputPhase.MODEL_REQUIRED, controller.state.value.phase)
        assertEquals(listOf("voice.test"), controller.state.value.models.map { it.id })
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
        assertTrue(errors.isEmpty())
        controller.close()
    }

    @Test
    fun recognitionIsGenerationBoundAndRequiresExplicitConsumption() = runTest {
        val service = readyService()
        val controller = SpeechInputController(this, service) {}

        controller.startListening("session-a")
        runCurrent()

        val operationId = SpeechOperationId(1L)
        assertEquals(
            SpeechRecognitionState.Listening(operationId, SpeechModelId("voice.test"), 10L),
            service.recognition.value,
        )
        assertEquals(SpeechInputPhase.LISTENING, controller.state.value.phase)
        assertEquals("session-a", controller.state.value.targetSessionKey)

        service.recognitionState.value = SpeechRecognitionState.Result(
            SpeechOperationId(99L),
            SpeechModelId("voice.test"),
            "stale transcript",
        )
        runCurrent()
        assertEquals(SpeechInputPhase.STARTING, controller.state.value.phase)
        assertNull(controller.consumeTranscript("session-a"))

        service.recognitionState.value = SpeechRecognitionState.Result(
            operationId,
            SpeechModelId("voice.test"),
            "reviewed transcript",
        )
        runCurrent()
        assertEquals(SpeechInputPhase.RESULT, controller.state.value.phase)
        assertEquals("reviewed transcript", controller.state.value.transcript)
        assertNull(controller.consumeTranscript("session-b"))
        assertEquals("reviewed transcript", controller.consumeTranscript("session-a"))
        assertNull(controller.consumeTranscript("session-a"))
        assertEquals(SpeechInputPhase.READY, controller.state.value.phase)
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

        controller.cancelForSessionChange("session-b")
        runCurrent()

        assertEquals(listOf(SpeechOperationId(1L)), service.cancelCalls)
        assertEquals(SpeechInputPhase.READY, controller.state.value.phase)
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
        val errors = mutableListOf<String>()
        val controller = SpeechInputController(this, service, errors::add)

        controller.startListening("session-a")
        runCurrent()

        assertEquals(
            listOf("Voice input could not be started. Check microphone access and try again."),
            errors,
        )
        assertFalse(errors.single().contains("private"))
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
        assertEquals("Free app storage and retry.", controller.state.value.statusMessage)
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
        val errors = mutableListOf<String>()
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
        val errors = mutableListOf<String>()
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
                "The voice transcript would exceed the " +
                    "$MAX_SESSION_DRAFT_CHARS character draft limit.",
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
    var startFailure: Throwable? = null
    var deferredStart: CompletableDeferred<SpeechOperationId>? = null
    var closeCalls = 0
    private var nextOperationId = 0L

    override val models: StateFlow<List<SpeechModelState>> = modelStates.asStateFlow()
    override val recognition: StateFlow<SpeechRecognitionState> = recognitionState.asStateFlow()
    override val playback: StateFlow<SpeechPlaybackState> = playbackState.asStateFlow()

    override suspend fun installModel(modelId: SpeechModelId) {
        installCalls += modelId
    }

    override suspend fun cancelModelInstall(modelId: SpeechModelId) {
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
        stopCalls += operationId
        val listening = recognitionState.value as? SpeechRecognitionState.Listening ?: return
        if (listening.operationId == operationId) {
            recognitionState.value =
                SpeechRecognitionState.Transcribing(operationId, listening.modelId)
        }
    }

    override suspend fun cancelListening(operationId: SpeechOperationId) {
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
