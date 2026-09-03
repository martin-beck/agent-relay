package com.example.agentrelay.ui.main

import com.example.agentrelay.R
import dev.agentrelay.speech.api.OfflineSpeechService
import dev.agentrelay.speech.api.SpeechModelAvailability
import dev.agentrelay.speech.api.SpeechModelCapability
import dev.agentrelay.speech.api.SpeechModelId
import dev.agentrelay.speech.api.SpeechModelState
import dev.agentrelay.speech.api.SpeechOperationId
import dev.agentrelay.speech.api.SpeechRecognitionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class SpeechInputPhase {
    UNAVAILABLE,
    MODEL_REQUIRED,
    INSTALLING,
    READY,
    STARTING,
    LISTENING,
    TRANSCRIBING,
    RESULT,
    FAILED,
}

internal data class SpeechModelOptionUiModel(
    val id: String,
    val name: String,
    val isReady: Boolean,
)

internal data class SpeechInputUiState(
    val phase: SpeechInputPhase,
    val models: List<SpeechModelOptionUiModel> = emptyList(),
    val selectedModelId: String? = null,
    val selectedModelName: String? = null,
    val targetSessionKey: String? = null,
    val operationId: Long? = null,
    val transcript: String? = null,
    val progressPercent: Int? = null,
    val statusMessage: String,
) {
    val canSelectModel: Boolean
        get() = phase in MODEL_SELECTION_PHASES && models.size > 1

    val canInstall: Boolean
        get() = phase == SpeechInputPhase.MODEL_REQUIRED ||
            phase == SpeechInputPhase.FAILED && operationId == null

    val canCancelInstall: Boolean
        get() = phase == SpeechInputPhase.INSTALLING

    val canStart: Boolean
        get() = phase == SpeechInputPhase.READY

    val canStop: Boolean
        get() = phase == SpeechInputPhase.LISTENING

    val canCancel: Boolean
        get() = phase in ACTIVE_PHASES

    val canUseResult: Boolean
        get() = phase == SpeechInputPhase.RESULT

    val canDismiss: Boolean
        get() = phase == SpeechInputPhase.RESULT ||
            phase == SpeechInputPhase.FAILED && operationId != null

    private companion object {
        val MODEL_SELECTION_PHASES = setOf(
            SpeechInputPhase.MODEL_REQUIRED,
            SpeechInputPhase.READY,
            SpeechInputPhase.FAILED,
        )
        val ACTIVE_PHASES = setOf(
            SpeechInputPhase.STARTING,
            SpeechInputPhase.LISTENING,
            SpeechInputPhase.TRANSCRIBING,
        )
    }
}

/**
 * Binds one generation-safe offline recognition operation to the session that requested it.
 *
 * The controller owns and closes the optional service. Permission prompting remains a
 * Compose/Activity responsibility. Model paths and exception text
 * never enter UI state, and transcript insertion requires a separate explicit action.
 */
internal class SpeechInputController(
    private val scope: CoroutineScope,
    private val service: OfflineSpeechService?,
    private val reportError: (UiMessage) -> Unit,
) : AutoCloseable {
    private val mutableState = MutableStateFlow(unavailableSpeechInputState())
    private var selectedModelId: SpeechModelId? = null
    private var startingSessionKey: String? = null
    private var activeTarget: ActiveSpeechTarget? = null
    private var dismissedOperationId: SpeechOperationId? = null
    private var startJob: Job? = null
    private var installJob: Job? = null
    private var startGeneration = 0L
    private var closed = false

    private val observerJob = service?.let { opened ->
        scope.launch {
            combine(opened.models, opened.recognition) { _, _ -> Unit }
                .collect { refresh() }
        }
    }

    val state: StateFlow<SpeechInputUiState> = mutableState.asStateFlow()

    init {
        refresh()
    }

    fun selectModel(modelId: String) {
        if (activeTarget != null || startingSessionKey != null) {
            return
        }
        val selected = transcriptionModels().firstOrNull { it.descriptor.id.value == modelId }
            ?: return
        selectedModelId = selected.descriptor.id
        refresh()
    }

    fun installSelectedModel() {
        val opened = service ?: return
        val selected = selectedModel() ?: return
        if (installJob?.isActive == true ||
            selected.availability is SpeechModelAvailability.Downloading
        ) {
            return
        }
        installJob = scope.launch {
            try {
                opened.installModel(selected.descriptor.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                reportError(UiMessage.Localized(R.string.speech_error_model_install))
            }
        }
    }

    fun cancelSelectedModelInstall() {
        val opened = service ?: return
        val selected = selectedModel() ?: return
        scope.launch {
            try {
                opened.cancelModelInstall(selected.descriptor.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                reportError(UiMessage.Localized(R.string.speech_error_model_download_cancel))
            }
        }
    }

    fun startListening(sessionKey: String) {
        val opened = service ?: return
        if (sessionKey.isBlank() || activeTarget != null || startingSessionKey != null) {
            return
        }
        val selected = selectedModel()
        if (selected?.availability != SpeechModelAvailability.Ready) {
            reportError(UiMessage.Localized(R.string.speech_error_model_required))
            return
        }
        val generation = ++startGeneration
        startingSessionKey = sessionKey
        dismissedOperationId = null
        refresh()
        startJob = scope.launch {
            try {
                val operationId = opened.startListening(selected.descriptor.id)
                if (generation != startGeneration || startingSessionKey != sessionKey) {
                    cancelStaleStart(opened, operationId)
                    return@launch
                }
                activeTarget = ActiveSpeechTarget(sessionKey, operationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (generation == startGeneration && startingSessionKey == sessionKey) {
                    reportError(UiMessage.Localized(R.string.speech_error_start))
                }
            } finally {
                if (generation == startGeneration && startingSessionKey == sessionKey) {
                    startingSessionKey = null
                }
                refresh()
            }
        }
    }

    fun stopListening(sessionKey: String) {
        val opened = service ?: return
        val target = activeTarget?.takeIf { it.sessionKey == sessionKey } ?: return
        scope.launch {
            try {
                opened.stopListening(target.operationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                reportError(UiMessage.Localized(R.string.speech_error_stop))
            }
        }
    }

    fun cancelListening(sessionKey: String) {
        if (startingSessionKey == sessionKey) {
            cancelPendingStart()
            return
        }
        activeTarget
            ?.takeIf { it.sessionKey == sessionKey }
            ?.let(::cancelTarget)
    }

    fun cancelForSessionChange(selectedSessionKey: String?) {
        val starting = startingSessionKey
        if (starting != null && starting != selectedSessionKey) {
            cancelPendingStart()
        }
        activeTarget
            ?.takeIf { it.sessionKey != selectedSessionKey }
            ?.let(::cancelTarget)
    }

    fun consumeTranscript(sessionKey: String): String? {
        val target = activeTarget?.takeIf { it.sessionKey == sessionKey } ?: return null
        val result = service?.recognition?.value as? SpeechRecognitionState.Result ?: return null
        if (result.operationId != target.operationId || result.operationId == dismissedOperationId) {
            return null
        }
        dismissedOperationId = result.operationId
        activeTarget = null
        refresh()
        return result.text
    }

    fun dismissResultOrFailure(sessionKey: String) {
        val target = activeTarget?.takeIf { it.sessionKey == sessionKey } ?: return
        val recognition = service?.recognition?.value
        if (recognition.operationIdOrNull() != target.operationId ||
            recognition !is SpeechRecognitionState.Result &&
            recognition !is SpeechRecognitionState.Failed
        ) {
            return
        }
        dismissedOperationId = target.operationId
        activeTarget = null
        refresh()
    }

    override fun close() {
        if (closed) {
            return
        }
        closed = true
        startGeneration += 1
        startingSessionKey = null
        observerJob?.cancel()
        installJob?.cancel()
        startJob?.cancel()
        runCatching { service?.close() }
    }

    private suspend fun cancelStaleStart(
        opened: OfflineSpeechService,
        operationId: SpeechOperationId,
    ) {
        withContext(NonCancellable) {
            try {
                opened.cancelListening(operationId)
            } catch (_: Throwable) {
                reportError(UiMessage.Localized(R.string.speech_error_previous_session_cancel))
            }
        }
    }

    private fun cancelPendingStart() {
        startGeneration += 1
        startingSessionKey = null
        startJob?.cancel()
        startJob = null
        refresh()
    }

    private fun cancelTarget(target: ActiveSpeechTarget) {
        val opened = service ?: return
        dismissedOperationId = target.operationId
        if (activeTarget == target) {
            activeTarget = null
        }
        refresh()
        scope.launch {
            try {
                opened.cancelListening(target.operationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                reportError(UiMessage.Localized(R.string.speech_error_cancel))
            }
        }
    }

    private fun refresh() {
        val opened = service
        if (opened == null) {
            mutableState.value = unavailableSpeechInputState()
            return
        }
        val models = transcriptionModels()
        mutableState.value = mapState(
            models = models,
            selected = chooseSelected(models),
            recognition = opened.recognition.value,
        )
    }

    private fun transcriptionModels(): List<SpeechModelState> =
        service?.models?.value.orEmpty().filter {
            SpeechModelCapability.TRANSCRIPTION in it.descriptor.capabilities
        }

    private fun selectedModel(): SpeechModelState? = chooseSelected(transcriptionModels())

    private fun chooseSelected(models: List<SpeechModelState>): SpeechModelState? {
        val selected = models.firstOrNull { it.descriptor.id == selectedModelId }
            ?: models.firstOrNull { it.availability == SpeechModelAvailability.Ready }
            ?: models.firstOrNull()
        selectedModelId = selected?.descriptor?.id
        return selected
    }

    private fun mapState(
        models: List<SpeechModelState>,
        selected: SpeechModelState?,
        recognition: SpeechRecognitionState,
    ): SpeechInputUiState {
        val options = models.map {
            SpeechModelOptionUiModel(
                id = it.descriptor.id.value,
                name = it.descriptor.displayName,
                isReady = it.availability == SpeechModelAvailability.Ready,
            )
        }
        val target = activeTarget
        if (target != null &&
            recognition.operationIdOrNull() == target.operationId &&
            target.operationId != dismissedOperationId
        ) {
            return activeState(options, selected, target, recognition)
        }
        if (target != null) {
            return baseState(
                phase = SpeechInputPhase.STARTING,
                options = options,
                selected = selected,
                targetSessionKey = target.sessionKey,
                operationId = target.operationId.value,
                statusMessage = "Waiting for the current voice input state...",
            )
        }
        return startingSessionKey?.let { sessionKey ->
            baseState(
                phase = SpeechInputPhase.STARTING,
                options = options,
                selected = selected,
                targetSessionKey = sessionKey,
                statusMessage = "Starting private on-device voice input...",
            )
        } ?: idleState(options, selected)
    }

    private fun activeState(
        options: List<SpeechModelOptionUiModel>,
        selected: SpeechModelState?,
        target: ActiveSpeechTarget,
        recognition: SpeechRecognitionState,
    ): SpeechInputUiState {
        val phase: SpeechInputPhase
        val transcript: String?
        val message: String
        when (recognition) {
            is SpeechRecognitionState.Listening -> {
                phase = SpeechInputPhase.LISTENING
                transcript = null
                message = "Listening on device. Stop when you finish speaking."
            }
            is SpeechRecognitionState.Transcribing -> {
                phase = SpeechInputPhase.TRANSCRIBING
                transcript = null
                message = "Finishing the private on-device transcript..."
            }
            is SpeechRecognitionState.Result -> {
                phase = SpeechInputPhase.RESULT
                transcript = recognition.text
                message = "Review the transcript before inserting it into the session draft."
            }
            is SpeechRecognitionState.Failed -> {
                phase = SpeechInputPhase.FAILED
                transcript = null
                message = recognition.failure.actionableMessage
            }
            SpeechRecognitionState.Idle -> {
                phase = SpeechInputPhase.STARTING
                transcript = null
                message = "Starting private on-device voice input..."
            }
        }
        return baseState(
            phase = phase,
            options = options,
            selected = selected,
            targetSessionKey = target.sessionKey,
            operationId = target.operationId.value,
            transcript = transcript,
            statusMessage = message,
        )
    }

    private fun idleState(
        options: List<SpeechModelOptionUiModel>,
        selected: SpeechModelState?,
    ): SpeechInputUiState {
        if (selected == null) {
            return SpeechInputUiState(
                phase = SpeechInputPhase.UNAVAILABLE,
                models = options,
                statusMessage = "No verified offline transcription model is available.",
            )
        }
        return when (val availability = selected.availability) {
            SpeechModelAvailability.NotInstalled -> baseState(
                SpeechInputPhase.MODEL_REQUIRED,
                options,
                selected,
                statusMessage = "Install the verified offline model before using voice input.",
            )
            is SpeechModelAvailability.Downloading -> baseState(
                SpeechInputPhase.INSTALLING,
                options,
                selected,
                progressPercent =
                (
                    availability.downloadedBytes.toDouble() /
                        availability.totalBytes.toDouble() * 100.0
                    ).toInt().coerceIn(0, 100),
                statusMessage = "Downloading the offline speech model...",
            )
            SpeechModelAvailability.Ready -> baseState(
                SpeechInputPhase.READY,
                options,
                selected,
                statusMessage = "Voice input stays on this device.",
            )
            is SpeechModelAvailability.Failed -> baseState(
                SpeechInputPhase.FAILED,
                options,
                selected,
                statusMessage = availability.failure.actionableMessage,
            )
        }
    }

    private fun baseState(
        phase: SpeechInputPhase,
        options: List<SpeechModelOptionUiModel>,
        selected: SpeechModelState?,
        targetSessionKey: String? = null,
        operationId: Long? = null,
        transcript: String? = null,
        progressPercent: Int? = null,
        statusMessage: String,
    ) = SpeechInputUiState(
        phase = phase,
        models = options,
        selectedModelId = selected?.descriptor?.id?.value,
        selectedModelName = selected?.descriptor?.displayName,
        targetSessionKey = targetSessionKey,
        operationId = operationId,
        transcript = transcript,
        progressPercent = progressPercent,
        statusMessage = statusMessage,
    )

    private data class ActiveSpeechTarget(
        val sessionKey: String,
        val operationId: SpeechOperationId,
    )
}

internal fun unavailableSpeechInputState() = SpeechInputUiState(
    phase = SpeechInputPhase.UNAVAILABLE,
    statusMessage = "Offline voice input is unavailable in this build.",
)

private fun SpeechRecognitionState?.operationIdOrNull(): SpeechOperationId? = when (this) {
    is SpeechRecognitionState.Listening -> operationId
    is SpeechRecognitionState.Transcribing -> operationId
    is SpeechRecognitionState.Result -> operationId
    is SpeechRecognitionState.Failed -> operationId
    SpeechRecognitionState.Idle, null -> null
}
