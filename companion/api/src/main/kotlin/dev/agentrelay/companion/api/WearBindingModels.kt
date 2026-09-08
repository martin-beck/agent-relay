/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.companion.api

enum class WearBindingState { CONNECTED, DISCONNECTED }

enum class WearBindingTransition { BOUND, RECONNECTED, STILL_CONNECTED, DISCONNECTED }

data class WearCompanionBinding(
    val device: CompanionDeviceIdentity,
    val state: WearBindingState,
    val generation: Long,
    val changedAtEpochMillis: Long,
) {
    init {
        require(generation > 0) { "Binding generation must be positive" }
        require(changedAtEpochMillis >= 0) { "Binding time is invalid" }
    }
}

data class WearBindingEvent(
    val deviceId: CompanionDeviceId,
    val transition: WearBindingTransition,
    val generation: Long,
)

/** Phone-owned binding registry; reconnects preserve identity and enrollment generation. */
class WearCompanionBindingRegistry(
    private val coordinator: CompanionPhoneCoordinator,
) {
    private val bindings = linkedMapOf<CompanionDeviceId, WearCompanionBinding>()

    fun connect(device: CompanionDeviceIdentity, observedAtEpochMillis: Long): WearBindingEvent {
        require(observedAtEpochMillis >= 0) { "Binding observation time is invalid" }
        val previous = bindings[device.id]
        val generation = previous?.generation ?: 1L
        if (previous == null) {
            coordinator.enroll(CompanionEnrollment(device, CompanionEnrollmentState.ENROLLED, generation, observedAtEpochMillis))
        } else {
            require(previous.device == device || previous.state == WearBindingState.DISCONNECTED) {
                "Connected device identity changed without explicit replacement"
            }
        }
        bindings[device.id] = WearCompanionBinding(device, WearBindingState.CONNECTED, generation, observedAtEpochMillis)
        return WearBindingEvent(
            device.id,
            when (previous?.state) {
                null -> WearBindingTransition.BOUND
                WearBindingState.DISCONNECTED -> WearBindingTransition.RECONNECTED
                WearBindingState.CONNECTED -> WearBindingTransition.STILL_CONNECTED
            },
            generation,
        )
    }

    fun disconnect(deviceId: CompanionDeviceId, observedAtEpochMillis: Long): WearBindingEvent {
        require(observedAtEpochMillis >= 0) { "Binding observation time is invalid" }
        val previous = bindings[deviceId] ?: error("Unknown Wear device")
        bindings[deviceId] = previous.copy(state = WearBindingState.DISCONNECTED, changedAtEpochMillis = observedAtEpochMillis)
        return WearBindingEvent(deviceId, WearBindingTransition.DISCONNECTED, previous.generation)
    }

    fun binding(deviceId: CompanionDeviceId): WearCompanionBinding? = bindings[deviceId]
}
