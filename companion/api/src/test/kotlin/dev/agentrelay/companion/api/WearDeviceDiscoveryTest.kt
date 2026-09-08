/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.companion.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WearDeviceDiscoveryTest {
    @Test
    fun discoversNoneAndThenMultipleDevicesInStableOrder() {
        var records = emptyList<WearDeviceDiscoveryRecord>()
        val inventory = WearDeviceInventory(WearDeviceDiscoverySource { records })

        assertEquals(emptyList(), inventory.discover(100).devices)
        records = listOf(device("cd_v1_watchtwo", "Second"), device("cd_v1_watchone", "First"))
        val snapshot = inventory.discover(200)

        assertEquals(listOf("cd_v1_watchone", "cd_v1_watchtwo"), snapshot.devices.map { it.deviceId.value })
        assertEquals(
            listOf(WearDeviceConnectionTransition.RECONNECTED, WearDeviceConnectionTransition.RECONNECTED),
            snapshot.events.map { it.transition },
        )
    }

    @Test
    fun reportsDisconnectAndReconnectWithoutChangingStableIdentity() {
        var records = listOf(device("cd_v1_watchone", "Watch"))
        val inventory = WearDeviceInventory(WearDeviceDiscoverySource { records })
        inventory.discover(100)
        records = emptyList()
        assertEquals(WearDeviceConnectionTransition.DISCONNECTED, inventory.discover(200).events.single().transition)
        records = listOf(device("cd_v1_watchone", "Watch"))
        val reconnect = inventory.discover(300)
        assertEquals(WearDeviceConnectionTransition.RECONNECTED, reconnect.events.single().transition)
        assertEquals("cd_v1_watchone", reconnect.devices.single().identity(300).id.value)
    }

    @Test
    fun classifiesUnsupportedProfilesAndPackageStateWithoutRawIdentifiers() {
        val record = device(
            "cd_v1_unsupported",
            "Unknown",
            apiLevel = 29,
            abi = "x86",
            packageState = WearCompanionPackageState.NOT_INSTALLED,
        )
        assertEquals(WearDeviceSupport.UNSUPPORTED_API, record.support)
        assertEquals(WearCompanionPackageState.NOT_INSTALLED, record.packageState)
        assertEquals("cd_v1_unsupported", record.identity(42).id.value)
    }

    @Test
    fun rejectsDuplicateIdentitiesAndInvalidIdentifiers() {
        val duplicate = device("cd_v1_duplicate", "One") to device("cd_v1_duplicate", "Two")
        val inventory = WearDeviceInventory(WearDeviceDiscoverySource { listOf(duplicate.first, duplicate.second) })
        assertFailsWith<IllegalArgumentException> { inventory.discover(1) }
        assertFailsWith<IllegalArgumentException> {
            device("raw-bluetooth-address", "Leaky")
        }
    }

    private fun device(
        id: String,
        alias: String,
        apiLevel: Int = 34,
        abi: String = "x86_64",
        packageState: WearCompanionPackageState = WearCompanionPackageState.INSTALLED,
    ) = WearDeviceDiscoveryRecord(
        CompanionDeviceId(id),
        alias,
        apiLevel,
        "wear-emulator",
        abi,
        setOf(CompanionCapability.NOTIFICATIONS),
        CompanionReachability.ONLINE,
        packageState,
    )
}
