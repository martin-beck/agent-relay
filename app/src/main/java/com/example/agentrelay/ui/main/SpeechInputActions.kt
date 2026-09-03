package com.example.agentrelay.ui.main

import com.example.agentrelay.R
import dev.agentrelay.session.api.SessionDraft

/**
 * Groups speech actions so the main ViewModel remains focused on session orchestration.
 */
internal class SpeechInputActions(
    private val controller: SpeechInputController,
    private val resolveDraft: (String) -> SessionDraft?,
    private val updateDraft: (String, String, Int, Int) -> Unit,
    private val reportError: (UiMessage) -> Unit,
) {
    fun selectModel(modelId: String) = controller.selectModel(modelId)

    fun installModel() = controller.installSelectedModel()

    fun cancelModelInstall() = controller.cancelSelectedModelInstall()

    fun start(sessionKey: String) = controller.startListening(sessionKey)

    fun stop(sessionKey: String) = controller.stopListening(sessionKey)

    fun cancel(sessionKey: String) = controller.cancelListening(sessionKey)

    fun dismiss(sessionKey: String) = controller.dismissResultOrFailure(sessionKey)

    fun permissionDenied() {
        reportError(UiMessage.Localized(R.string.speech_error_microphone_permission))
    }

    fun useTranscript(sessionKey: String) {
        val speechState = controller.state.value
        val transcript = speechState.transcript
        if (speechState.targetSessionKey != sessionKey || transcript == null) {
            reportError(UiMessage.Localized(R.string.speech_error_transcript_unavailable))
            return
        }
        val currentDraft = resolveDraft(sessionKey)
        if (currentDraft == null) {
            reportError(UiMessage.Localized(R.string.speech_error_session_unavailable))
            return
        }
        val updatedText = currentDraft.text.replaceRange(
            currentDraft.selectionStart,
            currentDraft.selectionEnd,
            transcript,
        )
        if (updatedText.length > MAX_SESSION_DRAFT_CHARS) {
            reportError(
                UiMessage.Plural(
                    R.plurals.speech_error_transcript_too_long,
                    MAX_SESSION_DRAFT_CHARS,
                    listOf(MAX_SESSION_DRAFT_CHARS),
                ),
            )
            return
        }
        if (controller.consumeTranscript(sessionKey) != transcript) {
            reportError(
                UiMessage.Localized(R.string.speech_error_transcript_changed),
            )
            return
        }
        val cursor = currentDraft.selectionStart + transcript.length
        updateDraft(sessionKey, updatedText, cursor, cursor)
    }

    companion object {
        fun emptyDraft(clock: () -> Long) = SessionDraft(
            text = "",
            selectionStart = 0,
            selectionEnd = 0,
            updatedAtEpochMillis = clock().coerceAtLeast(0L),
        )
    }
}
