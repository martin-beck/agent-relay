package dev.agentrelay.companion.api

enum class WearControlAction { ACKNOWLEDGE, DEFER, OPEN_ON_PHONE, REQUEST_APPROVAL }

data class WearControlRequest(
    val deviceId: CompanionDeviceId,
    val enrollmentGeneration: Long,
    val requestId: String,
    val cardId: String,
    val cardRevision: Long,
    val action: WearControlAction,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val authorizationTag: String,
) {
    init {
        require(enrollmentGeneration > 0) { "Control generation must be positive" }
        require(requestId.matches(REQUEST_ID_PATTERN)) { "Control request id is invalid" }
        require(cardId.matches(CARD_ID_PATTERN)) { "Control card id is invalid" }
        require(cardRevision > 0) { "Control card revision must be positive" }
        require(issuedAtEpochMillis >= 0) { "Control issue time is invalid" }
        require(expiresAtEpochMillis > issuedAtEpochMillis) { "Control expiry is invalid" }
        requireBoundedControl(authorizationTag, "Control authorization tag", 512)
    }

    fun isFreshAt(nowEpochMillis: Long): Boolean =
        nowEpochMillis in issuedAtEpochMillis until expiresAtEpochMillis
}

enum class WearControlOutcome {
    EXECUTED,
    PHONE_CONFIRMATION_REQUIRED,
    REJECT_UNAUTHORIZED,
    REJECT_REPLAY,
    REJECT_EXPIRED,
    REJECT_REVOKED,
    PHONE_UNAVAILABLE,
}

data class WearControlExecution(
    val action: WearControlAction,
    val cardId: String,
    val cardRevision: Long,
)

fun interface WearControlAuthorizer {
    fun authorize(request: WearControlRequest): Boolean
}

fun interface WearControlExecutor {
    fun execute(request: WearControlRequest): WearControlExecution
}

/** Phone-owned control gate; a watch can request actions but never owns privileged execution. */
class WearControlProcessor(
    private val coordinator: CompanionPhoneCoordinator,
    private val authorizer: WearControlAuthorizer,
    private val executor: WearControlExecutor,
) {
    private val consumedRequestIds = linkedSetOf<String>()

    fun process(request: WearControlRequest, nowEpochMillis: Long, phoneAvailable: Boolean): WearControlOutcome {
        if (!coordinator.acceptsControl(request.deviceId, request.enrollmentGeneration)) {
            return WearControlOutcome.REJECT_REVOKED
        }
        if (!request.isFreshAt(nowEpochMillis)) return WearControlOutcome.REJECT_EXPIRED
        if (!authorizer.authorize(request)) return WearControlOutcome.REJECT_UNAUTHORIZED
        if (!phoneAvailable) return WearControlOutcome.PHONE_UNAVAILABLE
        if (!consumedRequestIds.add(request.requestId)) return WearControlOutcome.REJECT_REPLAY
        if (request.action == WearControlAction.REQUEST_APPROVAL) {
            return WearControlOutcome.PHONE_CONFIRMATION_REQUIRED
        }
        executor.execute(request)
        return WearControlOutcome.EXECUTED
    }
}

private val REQUEST_ID_PATTERN = Regex("control_v1_[A-Za-z0-9_-]{8,64}")
private val CARD_ID_PATTERN = Regex("card_v1_[A-Za-z0-9_-]{8,64}")

private fun requireBoundedControl(value: String, field: String, max: Int) {
    require(value.isNotBlank() && value.length <= max && !value.contains('\u0000')) {
        "$field is invalid or too long"
    }
}
