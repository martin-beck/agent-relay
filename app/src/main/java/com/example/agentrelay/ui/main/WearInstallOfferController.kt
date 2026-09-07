package com.example.agentrelay.ui.main

import dev.agentrelay.companion.api.CompanionDeviceId
import dev.agentrelay.companion.api.WearDeviceDiscoveryRecord
import dev.agentrelay.companion.api.WearInstallConsent
import dev.agentrelay.companion.api.WearInstallDecisionState
import dev.agentrelay.companion.api.WearInstallDecisionStore
import dev.agentrelay.companion.api.WearInstallPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface WearInstallOfferUiState {
    data object Hidden : WearInstallOfferUiState

    data class Offer(
        val deviceId: CompanionDeviceId,
        val deviceAlias: String,
    ) : WearInstallOfferUiState

    data class Installing(
        val deviceId: CompanionDeviceId,
        val deviceAlias: String,
    ) : WearInstallOfferUiState

    data class Recovery(
        val deviceId: CompanionDeviceId,
        val deviceAlias: String,
        val reason: WearInstallRecoveryReason,
    ) : WearInstallOfferUiState
}

internal enum class WearInstallRecoveryReason {
    AUTHORIZATION_REQUIRED,
    INSTALLATION_FAILED,
    CONNECTION_FAILED,
}

/**
 * Owns the user-visible part of one exact-device installation decision.
 *
 * Discovery adapters call [observe]. Installation adapters consume the short-lived consent from
 * [accept] or [retry], then report [complete] or [fail]. A decline is written through the shared
 * decision store, so rediscovery cannot recreate an offer for the same device and package policy.
 */
internal class WearInstallOfferController(
    private val decisionStore: WearInstallDecisionStore,
    private val policy: WearInstallPolicy,
    private val clock: () -> Long = System::currentTimeMillis,
    private val consentLifetimeMillis: Long = DEFAULT_CONSENT_LIFETIME_MILLIS,
) {
    private val mutableState = MutableStateFlow<WearInstallOfferUiState>(WearInstallOfferUiState.Hidden)
    private var observedDevice: WearDeviceDiscoveryRecord? = null

    val state: StateFlow<WearInstallOfferUiState> = mutableState.asStateFlow()

    init {
        require(consentLifetimeMillis > 0) { "Consent lifetime must be positive" }
    }

    @Synchronized
    fun observe(device: WearDeviceDiscoveryRecord) {
        val current = mutableState.value
        if (current != WearInstallOfferUiState.Hidden) {
            val activeDeviceId = current.activeDeviceId()
            if (activeDeviceId != device.deviceId || current !is WearInstallOfferUiState.Offer) {
                return
            }
        }
        observedDevice = device
        mutableState.value = when (decisionStore.observe(device, policy, clock()).state) {
            WearInstallDecisionState.PENDING_OFFER -> device.offerState()
            WearInstallDecisionState.INSTALLED,
            WearInstallDecisionState.DECLINED,
            WearInstallDecisionState.UNAVAILABLE,
            -> WearInstallOfferUiState.Hidden
        }
    }

    @Synchronized
    fun accept(): WearInstallConsent? {
        val current = mutableState.value
        if (current !is WearInstallOfferUiState.Offer) return null
        return beginInstallation(current.deviceId, current.deviceAlias)
    }

    @Synchronized
    fun decline(): Boolean {
        val current = mutableState.value
        if (current !is WearInstallOfferUiState.Offer && current !is WearInstallOfferUiState.Recovery) {
            return false
        }
        val deviceId = current.activeDeviceId()
        decisionStore.recordDeclined(deviceId, policy, clock())
        mutableState.value = WearInstallOfferUiState.Hidden
        return true
    }

    @Synchronized
    fun cancel(): Boolean {
        val current = mutableState.value as? WearInstallOfferUiState.Installing ?: return false
        mutableState.value = WearInstallOfferUiState.Offer(current.deviceId, current.deviceAlias)
        return true
    }

    @Synchronized
    fun fail(reason: WearInstallRecoveryReason): Boolean {
        val current = mutableState.value as? WearInstallOfferUiState.Installing ?: return false
        mutableState.value = WearInstallOfferUiState.Recovery(current.deviceId, current.deviceAlias, reason)
        return true
    }

    @Synchronized
    fun retry(): WearInstallConsent? {
        val current = mutableState.value as? WearInstallOfferUiState.Recovery ?: return null
        return beginInstallation(current.deviceId, current.deviceAlias)
    }

    @Synchronized
    fun complete(): Boolean {
        val current = mutableState.value as? WearInstallOfferUiState.Installing ?: return false
        decisionStore.recordInstalled(current.deviceId, policy, clock())
        mutableState.value = WearInstallOfferUiState.Hidden
        return true
    }

    private fun beginInstallation(deviceId: CompanionDeviceId, deviceAlias: String): WearInstallConsent {
        check(observedDevice?.deviceId == deviceId) { "Wear installation target is no longer observed" }
        val issuedAt = clock()
        mutableState.value = WearInstallOfferUiState.Installing(deviceId, deviceAlias)
        return WearInstallConsent(
            deviceId = deviceId,
            policy = policy,
            accepted = true,
            issuedAtEpochMillis = issuedAt,
            expiresAtEpochMillis = Math.addExact(issuedAt, consentLifetimeMillis),
        )
    }

    private fun WearDeviceDiscoveryRecord.offerState() =
        WearInstallOfferUiState.Offer(deviceId, alias)

    private fun WearInstallOfferUiState.activeDeviceId(): CompanionDeviceId =
        when (this) {
            is WearInstallOfferUiState.Offer -> deviceId
            is WearInstallOfferUiState.Installing -> deviceId
            is WearInstallOfferUiState.Recovery -> deviceId
            WearInstallOfferUiState.Hidden -> error("Wear install decision is not available")
        }

    private companion object {
        const val DEFAULT_CONSENT_LIFETIME_MILLIS = 2 * 60 * 1_000L
    }
}
