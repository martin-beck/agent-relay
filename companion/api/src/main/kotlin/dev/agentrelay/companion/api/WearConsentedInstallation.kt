package dev.agentrelay.companion.api

/** A short-lived, device-bound consent grant. It cannot be replayed for another device. */
data class WearInstallConsent(
    val deviceId: CompanionDeviceId,
    val policy: WearInstallPolicy,
    val accepted: Boolean,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
) {
    init {
        require(issuedAtEpochMillis >= 0) { "Consent issue time is invalid" }
        require(expiresAtEpochMillis > issuedAtEpochMillis) { "Consent expiry is invalid" }
    }

    fun isValidAt(nowEpochMillis: Long): Boolean = accepted && nowEpochMillis in issuedAtEpochMillis until expiresAtEpochMillis
}

data class WearInstallReceipt(
    val deviceId: CompanionDeviceId,
    val packageName: String,
    val versionCode: Long,
    val signingFingerprint: String,
)

enum class WearConsentedInstallOutcome {
    INSTALLED,
    CONSENT_REQUIRED,
    CONSENT_EXPIRED,
    WRONG_DEVICE,
    CANCELLED,
    REQUEST_REJECTED,
    VERIFICATION_FAILED,
    ROLLED_BACK,
    ROLLBACK_FAILED,
}

/** Device-specific adapter; implementations must never reinterpret the supplied device id. */
interface WearInstallTransport {
    fun install(deviceId: CompanionDeviceId, request: WearInstallationRequest): WearInstallReceipt
    fun rollback(deviceId: CompanionDeviceId): Boolean
}

class WearConsentedInstallationCoordinator(
    private val transport: WearInstallTransport,
) {
    fun install(
        consent: WearInstallConsent?,
        targetDeviceId: CompanionDeviceId,
        request: WearInstallationRequest,
        nowEpochMillis: Long,
        cancelled: Boolean = false,
    ): WearConsentedInstallOutcome {
        if (consent == null || !consent.accepted) return WearConsentedInstallOutcome.CONSENT_REQUIRED
        if (consent.deviceId != targetDeviceId) return WearConsentedInstallOutcome.WRONG_DEVICE
        if (!consent.isValidAt(nowEpochMillis)) return WearConsentedInstallOutcome.CONSENT_EXPIRED
        if (cancelled) return WearConsentedInstallOutcome.CANCELLED
        if (!request.isInstallable()) return WearConsentedInstallOutcome.REQUEST_REJECTED
        val receipt = runCatching { transport.install(targetDeviceId, request) }.getOrNull()
            ?: return rollback(targetDeviceId)
        val verified = receipt.deviceId == targetDeviceId &&
            receipt.packageName == request.artifact.packageName &&
            receipt.versionCode == request.artifact.versionCode &&
            receipt.signingFingerprint.equals(request.artifact.signingFingerprint, ignoreCase = true)
        if (verified) return WearConsentedInstallOutcome.INSTALLED
        return rollback(targetDeviceId)
    }

    private fun rollback(deviceId: CompanionDeviceId): WearConsentedInstallOutcome =
        if (runCatching { transport.rollback(deviceId) }.getOrDefault(false)) {
            WearConsentedInstallOutcome.ROLLED_BACK
        } else {
            WearConsentedInstallOutcome.ROLLBACK_FAILED
        }
}
