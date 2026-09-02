package com.example.agentrelay.ui.main

import dev.agentrelay.session.api.SessionDraft

/**
 * Groups speech actions so the main ViewModel remains focused on session orchestration.
 */
internal class SpeechInputActions(
    private val controller: SpeechInputController,
    private val resolveDraft: (String) -> SessionDraft?,
    private val updateDraft: (String, String, Int, Int) -> Unit,
    private val reportError: (String) -> Unit,
) {
    fun selectModel(modelId: String) = controller.selectModel(modelId)

    fun installModel() = controller.installSelectedModel()

    fun cancelModelInstall() = controller.cancelSelectedModelInstall()

    fun start(sessionKey: String) = controller.startListening(sessionKey)

    fun stop(sessionKey: String) = controller.stopListening(sessionKey)

    fun cancel(sessionKey: String) = controller.cancelListening(sessionKey)

    fun dismiss(sessionKey: String) = controller.dismissResultOrFailure(sessionKey)

    fun permissionDenied() {
        reportError("Microphone access is required only while recording offline voice input.")
    }

    fun useTranscript(sessionKey: String) {
        val speechState = controller.state.value
        val transcript = speechState.transcript
        if (speechState.targetSessionKey != sessionKey || transcript == null) {
            reportError("That voice transcript is no longer available.")
            return
        }
        val currentDraft = resolveDraft(sessionKey)
        if (currentDraft == null) {
            reportError("That session is no longer available.")
            return
        }
        val updatedText = currentDraft.text.replaceRange(
            currentDraft.selectionStart,
            currentDraft.selectionEnd,
            transcript,
        )
        if (updatedText.length > MAX_SESSION_DRAFT_CHARS) {
            reportError(
                "The voice transcript would exceed the $MAX_SESSION_DRAFT_CHARS character draft limit.",
            )
            return
        }
        if (controller.consumeTranscript(sessionKey) != transcript) {
            reportError("That voice transcript changed before it could be inserted.")
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
