/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.connection.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConnectivityDiagnosticsModelsTest {
    @Test
    fun discoveryAndFailoverEvidenceIsBoundedAndRedacted() {
        val store = ConnectivityDiagnosticsStore(maxHistoryEntries = 3, maxTopologyNodes = 2)
        store.observeNode(ConnectivityNode("client-1", ConnectivityNodeRole.CLIENT, DaemonTransportKind.DIRECT, 0))
        store.observeNode(ConnectivityNode("daemon-1", ConnectivityNodeRole.DAEMON, DaemonTransportKind.DIRECT, 1))
        store.setActiveNode("daemon-1")
        store.recordTransition(ConnectivityTransition.DISCOVERED, DaemonTransportKind.DIRECT, 100)
        store.recordTransition(ConnectivityTransition.DISCONNECTED, DaemonTransportKind.DIRECT, 200)
        store.recordFault(ConnectivityFault.TRANSPORT_LOST)
        store.recordTransition(ConnectivityTransition.FAILING_OVER, DaemonTransportKind.OPAQUE_RELAY, 201)
        store.recordTransition(ConnectivityTransition.RESUMED, DaemonTransportKind.OPAQUE_RELAY, 202, 1)
        val snapshot = store.snapshot(203)
        assertEquals(listOf(2L, 3L, 4L), snapshot.history.map(ConnectivityHistoryEntry::sequence))
        assertEquals(1, snapshot.droppedHistoryEntries)
        assertEquals(1, snapshot.faults.transportLosses)
        assertEquals("daemon-1", snapshot.topology.activeNodeId)
    }

    @Test
    fun seededFaultsProduceCountersWithoutProtectedContent() {
        val store = ConnectivityDiagnosticsStore()
        listOf(
            ConnectivityFault.DISCOVERY_TIMEOUT,
            ConnectivityFault.AUTHENTICATION_REJECTED,
            ConnectivityFault.DUPLICATE_COMMAND,
            ConnectivityFault.UNKNOWN_DELIVERY,
            ConnectivityFault.MALFORMED_FRAME,
        ).forEach(store::recordFault)
        val faults = store.snapshot(10).faults
        assertEquals(1, faults.discoveryTimeouts)
        assertEquals(1, faults.authenticationRejections)
        assertEquals(1, faults.duplicateCommands)
        assertEquals(1, faults.unknownDeliveries)
        assertEquals(1, faults.malformedFrames)
        assertTrue(store.snapshot(10).history.isEmpty())
    }

    @Test
    fun topologyRetentionAccountsForDroppedNodesAndRejectsUnknownActiveNode() {
        val store = ConnectivityDiagnosticsStore(maxTopologyNodes = 1)
        store.observeNode(ConnectivityNode("daemon-1", ConnectivityNodeRole.DAEMON, DaemonTransportKind.DIRECT, 0))
        store.observeNode(ConnectivityNode("relay-1", ConnectivityNodeRole.RELAY, DaemonTransportKind.OPAQUE_RELAY, 1))
        assertFailsWith<IllegalArgumentException> { store.setActiveNode("missing-1") }
        val snapshot = store.snapshot(20)
        assertEquals(1, snapshot.droppedTopologyNodes)
        assertEquals("relay-1", snapshot.topology.nodes.single().id)
    }
}
