package dev.agentrelay.companion.api

enum class WearSpeechOutputTarget { PHONE, WATCH }

enum class WearSpeechOutputMode { NORMAL, SILENT, BEDTIME, THEATER }

enum class WearSpeechOutputState { IDLE, PLAYING, INTERRUPTED, STOPPED, COMPLETED }

enum class WearSpeechOutputInterruption { AUDIO_FOCUS_LOST, USER_STOP, MODE_CHANGED, DISCONNECTED }

data class WearSpeechOutputContent(
    val text: String,
    val privacyClass: CompanionPrivacyClass,
    val explicitlySelected: Boolean = false,
) {
    init {
        require(text.isNotBlank() && text.length <= 500) { "Speech output text is invalid" }
        require(!text.contains('\n') && !text.contains('\r')) { "Speech output text must be one line" }
        require(privacyClass == CompanionPrivacyClass.PUBLIC_SUMMARY || explicitlySelected) {
            "Private speech output requires explicit selection"
        }
    }
}

data class WearSpeechOutputCapability(
    val target: WearSpeechOutputTarget,
    val supportsInterruption: Boolean,
    val batteryPercent: Int?,
    val reachable: Boolean,
) {
    init {
        require(batteryPercent == null || batteryPercent in 0..100) { "Battery percentage is invalid" }
    }
}

data class WearSpeechOutputRequest(
    val requestId: String,
    val generation: Long,
    val content: WearSpeechOutputContent,
    val preferredTarget: WearSpeechOutputTarget?,
    val createdAtEpochMillis: Long,
) {
    init {
        require(requestId.matches(Regex("speech_output_v1_[A-Za-z0-9_-]{8,64}"))) {
            "Speech output request id is invalid"
        }
        require(generation > 0) { "Speech output generation must be positive" }
        require(createdAtEpochMillis >= 0) { "Speech output creation time is invalid" }
    }
}

sealed interface WearSpeechOutputDecision {
    data class Accepted(val target: WearSpeechOutputTarget) : WearSpeechOutputDecision
    data class Rejected(val reason: WearSpeechOutputRejection) : WearSpeechOutputDecision
}

enum class WearSpeechOutputRejection {
    MODE_SUPPRESSED,
    NO_REACHABLE_TARGET,
    LOW_BATTERY,
    PRIVATE_CONTENT_NOT_SELECTED,
    STALE_GENERATION,
}

class WearSpeechOutputRouter {
    fun chooseTarget(
        request: WearSpeechOutputRequest,
        capabilities: List<WearSpeechOutputCapability>,
        mode: WearSpeechOutputMode,
        currentGeneration: Long,
    ): WearSpeechOutputDecision {
        if (request.generation != currentGeneration) {
            return WearSpeechOutputDecision.Rejected(WearSpeechOutputRejection.STALE_GENERATION)
        }
        if (mode != WearSpeechOutputMode.NORMAL) {
            return WearSpeechOutputDecision.Rejected(WearSpeechOutputRejection.MODE_SUPPRESSED)
        }
        if (request.content.privacyClass != CompanionPrivacyClass.PUBLIC_SUMMARY &&
            !request.content.explicitlySelected
        ) {
            return WearSpeechOutputDecision.Rejected(WearSpeechOutputRejection.PRIVATE_CONTENT_NOT_SELECTED)
        }
        val reachable = capabilities.filter { it.reachable }
        if (reachable.isEmpty()) {
            return WearSpeechOutputDecision.Rejected(WearSpeechOutputRejection.NO_REACHABLE_TARGET)
        }
        val preferred = request.preferredTarget?.let { preferredTarget ->
            reachable.firstOrNull {
                it.target == preferredTarget &&
                    it.batteryPercent != 0 &&
                    (
                        it.target != WearSpeechOutputTarget.WATCH ||
                            it.batteryPercent == null || it.batteryPercent >= 15
                        )
            }
        }
        if (preferred != null) return WearSpeechOutputDecision.Accepted(preferred.target)
        val safe = reachable.firstOrNull { it.target == WearSpeechOutputTarget.PHONE }?.takeIf {
            it.batteryPercent != 0
        } ?: reachable.firstOrNull { it.batteryPercent == null || it.batteryPercent >= 15 }
        return safe?.let { WearSpeechOutputDecision.Accepted(it.target) }
            ?: WearSpeechOutputDecision.Rejected(WearSpeechOutputRejection.LOW_BATTERY)
    }
}

class WearSpeechOutputSession {
    var state: WearSpeechOutputState = WearSpeechOutputState.IDLE
        private set
    private var generation: Long = 0

    fun start(request: WearSpeechOutputRequest, decision: WearSpeechOutputDecision): Boolean {
        if (decision !is WearSpeechOutputDecision.Accepted || request.generation <= generation) return false
        generation = request.generation
        state = WearSpeechOutputState.PLAYING
        return true
    }

    fun interrupt(requestGeneration: Long, reason: WearSpeechOutputInterruption): Boolean {
        if (requestGeneration != generation || state != WearSpeechOutputState.PLAYING) return false
        state = if (reason == WearSpeechOutputInterruption.USER_STOP) {
            WearSpeechOutputState.STOPPED
        } else {
            WearSpeechOutputState.INTERRUPTED
        }
        return true
    }

    fun complete(requestGeneration: Long): Boolean {
        if (requestGeneration != generation || state != WearSpeechOutputState.PLAYING) return false
        state = WearSpeechOutputState.COMPLETED
        return true
    }
}
