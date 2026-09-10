/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.companion.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WearBindingModelsTest {
    private val device = CompanionDeviceIdentity(
        CompanionDeviceId("cd_v1_binding01"), CompanionDeviceType.WATCH, "Watch", "api-34", 1,
        setOf(CompanionCapability.NOTIFICATIONS), CompanionReachability.ONLINE, null, 10,
    )

    @Test
    fun connectDisconnectReconnectPreservesStableGeneration() {
        val registry = WearCompanionBindingRegistry(CompanionPhoneCoordinator())
        assertEquals(WearBindingTransition.BOUND, registry.connect(device, 10).transition)
        assertEquals(WearBindingTransition.DISCONNECTED, registry.disconnect(device.id, 20).transition)
        assertEquals(WearBindingTransition.RECONNECTED, registry.connect(device, 30).transition)
        assertEquals(1, registry.binding(device.id)?.generation)
    }

    @Test
    fun repeatedConnectionIsStillConnectedAndUnknownDisconnectRejected() {
        val registry = WearCompanionBindingRegistry(CompanionPhoneCoordinator())
        registry.connect(device, 10)
        assertEquals(WearBindingTransition.STILL_CONNECTED, registry.connect(device, 11).transition)
        assertFailsWith<IllegalStateException> { registry.disconnect(CompanionDeviceId("cd_v1_unknown01"), 12) }
    }
}
