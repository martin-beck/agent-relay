package dev.agentrelay.companion.api

/** Bounded outcomes exposed by the phone-side speech input boundary. */
enum class WearSpeechInputFailure {
    PERMISSION_DENIED,
    DEVICE_DISCONNECTED,
    TIMED_OUT,
    CANCELLED,
    EMPTY_AUDIO,
    RECOGNITION_FAILED,
}

data class WearSpeechRecognitionResult(
    val transcript: String,
    val confidencePercent: Int?,
) {
    init {
        require(transcript.isNotBlank() && transcript.length <= 4_000) {
            "Recognition transcript is invalid"
        }
        require(confidencePercent == null || confidencePercent in 0..100) {
            "Recognition confidence is invalid"
        }
    }
}

/** Adapter boundary: implementations own microphone/model details and never return audio. */
fun interface WearSpeechRecognizer {
    fun recognize(
        capture: WearSpeechCapture,
        chunks: List<WearSpeechAudioChunk>,
    ): WearSpeechRecognitionResult?
}

sealed interface WearSpeechInputOutcome {
    data class Review(val value: WearSpeechTranscriptReview) : WearSpeechInputOutcome
    data class Failed(val reason: WearSpeechInputFailure) : WearSpeechInputOutcome
}

data class WearSpeechInputRequest(
    val capture: WearSpeechCapture,
    val chunks: List<WearSpeechAudioChunk>,
    val connected: Boolean,
    val nowEpochMillis: Long,
) {
    init {
        require(nowEpochMillis >= 0) { "Speech request time is invalid" }
        require(chunks.all { it.captureId == capture.captureId && it.generation == capture.generation }) {
            "Audio chunks do not belong to the capture"
        }
        require(chunks.zipWithNext().all { (a, b) -> a.sequence < b.sequence }) {
            "Audio chunks must be ordered"
        }
    }
}

/**
 * Generation-safe speech input coordinator. Cancellation is remembered by capture
 * identity until the next request, so late recognizer results cannot be presented.
 */
class WearSpeechInputBridge(
    private val recognizer: WearSpeechRecognizer,
) {
    private val cancelled = mutableSetOf<String>()

    fun cancel(capture: WearSpeechCapture): Boolean = cancelled.add(captureKey(capture))

    fun transcribe(request: WearSpeechInputRequest): WearSpeechInputOutcome {
        val capture = request.capture
        val key = captureKey(capture)
        return when {
            key in cancelled -> WearSpeechInputOutcome.Failed(WearSpeechInputFailure.CANCELLED)
            capture.permission != WearSpeechPermission.GRANTED ->
                WearSpeechInputOutcome.Failed(WearSpeechInputFailure.PERMISSION_DENIED)
            !request.connected -> WearSpeechInputOutcome.Failed(WearSpeechInputFailure.DEVICE_DISCONNECTED)
            capture.timedOutAt(request.nowEpochMillis) ->
                WearSpeechInputOutcome.Failed(WearSpeechInputFailure.TIMED_OUT)
            request.chunks.isEmpty() -> WearSpeechInputOutcome.Failed(WearSpeechInputFailure.EMPTY_AUDIO)
            else -> recognize(capture, request.chunks, key)
        }
    }

    private fun recognize(
        capture: WearSpeechCapture,
        chunks: List<WearSpeechAudioChunk>,
        key: String,
    ): WearSpeechInputOutcome =
        runCatching { recognizer.recognize(capture, chunks) }.getOrNull()?.let { result ->
            if (key in cancelled) {
                WearSpeechInputOutcome.Failed(WearSpeechInputFailure.CANCELLED)
            } else {
                WearSpeechInputOutcome.Review(
                    WearSpeechTranscriptReview(
                        captureId = capture.captureId,
                        generation = capture.generation,
                        transcript = result.transcript,
                        confidencePercent = result.confidencePercent,
                    ),
                )
            }
        } ?: WearSpeechInputOutcome.Failed(WearSpeechInputFailure.RECOGNITION_FAILED)

    private fun captureKey(capture: WearSpeechCapture): String =
        capture.captureId + ":" + capture.generation
}

enum class WearSpeechOutputFailure {
    SYNTHESIS_FAILED,
    CANCELLED,
}

sealed interface WearSpeechOutputOutcome {
    data class Spoken(val target: WearSpeechOutputTarget) : WearSpeechOutputOutcome
    data class Fallback(
        val reason: WearSpeechOutputRejection?,
        val notificationText: String?,
    ) : WearSpeechOutputOutcome
    data class Failed(val reason: WearSpeechOutputFailure, val notificationText: String?) :
        WearSpeechOutputOutcome
}

/** Adapter boundary: a platform TTS/Data Layer implementation stays outside the API module. */
fun interface WearSpeechSynthesizer {
    fun speak(target: WearSpeechOutputTarget, content: WearSpeechOutputContent): Boolean
}

/**
 * Routes redacted spoken feedback and returns notification-safe fallback text.
 * Private content never becomes an implicit notification fallback.
 */
class WearSpeechOutputBridge(
    private val router: WearSpeechOutputRouter = WearSpeechOutputRouter(),
    private val synthesizer: WearSpeechSynthesizer,
) {
    private val cancelled = mutableSetOf<String>()

    fun cancel(request: WearSpeechOutputRequest): Boolean = cancelled.add(requestKey(request))

    fun deliver(
        request: WearSpeechOutputRequest,
        capabilities: List<WearSpeechOutputCapability>,
        mode: WearSpeechOutputMode,
        currentGeneration: Long,
    ): WearSpeechOutputOutcome {
        val fallback = safeFallback(request.content)
        if (requestKey(request) in cancelled) {
            return WearSpeechOutputOutcome.Failed(WearSpeechOutputFailure.CANCELLED, fallback)
        }
        return when (
            val decision = router.chooseTarget(request, capabilities, mode, currentGeneration)
        ) {
            is WearSpeechOutputDecision.Rejected ->
                WearSpeechOutputOutcome.Fallback(decision.reason, fallback)
            is WearSpeechOutputDecision.Accepted -> {
                if (requestKey(request) in cancelled) {
                    WearSpeechOutputOutcome.Failed(WearSpeechOutputFailure.CANCELLED, fallback)
                } else if (runCatching { synthesizer.speak(decision.target, request.content) }.getOrDefault(false)) {
                    WearSpeechOutputOutcome.Spoken(decision.target)
                } else {
                    WearSpeechOutputOutcome.Failed(WearSpeechOutputFailure.SYNTHESIS_FAILED, fallback)
                }
            }
        }
    }

    private fun safeFallback(content: WearSpeechOutputContent): String? =
        content.takeIf { it.privacyClass == CompanionPrivacyClass.PUBLIC_SUMMARY }?.text

    private fun requestKey(request: WearSpeechOutputRequest): String =
        request.requestId + ":" + request.generation
}
