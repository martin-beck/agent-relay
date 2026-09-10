/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.connection.api

/** Redacted topology facts; node IDs are opaque labels, never addresses or paths. */
data class ConnectivityNode(
    val id: String,
    val role: ConnectivityNodeRole,
    val transport: DaemonTransportKind,
    val depth: Int,
) {
    init {
        require(id.matches(OPAQUE_ID_PATTERN)) { "Connectivity node id is invalid" }
        require(depth in 0..MAX_TOPOLOGY_DEPTH)
    }
}

enum class ConnectivityNodeRole { CLIENT, DAEMON, RELAY, GATEWAY }

data class ConnectivityTopology(val nodes: List<ConnectivityNode>, val activeNodeId: String?) {
    init {
        require(nodes.size <= MAX_TOPOLOGY_NODES) { "Connectivity topology is too large" }
        require(nodes.map(ConnectivityNode::id).toSet().size == nodes.size) {
            "Connectivity topology contains duplicate nodes"
        }
        require(activeNodeId == null || nodes.any { it.id == activeNodeId })
    }
}

enum class ConnectivityTransition { DISCOVERED, CONNECTED, DISCONNECTED, FAILING_OVER, RESUMED, SUSPENDED }

enum class ConnectivityFault {
    DISCOVERY_TIMEOUT,
    AUTHENTICATION_REJECTED,
    TRANSPORT_LOST,
    DUPLICATE_COMMAND,
    UNKNOWN_DELIVERY,
    MALFORMED_FRAME,
}

data class ConnectivityHistoryEntry(
    val sequence: Long,
    val transition: ConnectivityTransition,
    val transport: DaemonTransportKind,
    val atMillis: Long,
    val durationMillis: Long?,
) {
    init {
        require(sequence >= 1)
        require(atMillis >= 0)
        require(durationMillis == null || durationMillis >= 0)
    }
}

data class ConnectivityFaultCounters(
    val discoveryTimeouts: Long = 0,
    val authenticationRejections: Long = 0,
    val transportLosses: Long = 0,
    val duplicateCommands: Long = 0,
    val unknownDeliveries: Long = 0,
    val malformedFrames: Long = 0,
) {
    init {
        require(
            listOf(
                discoveryTimeouts,
                authenticationRejections,
                transportLosses,
                duplicateCommands,
                unknownDeliveries,
                malformedFrames,
            ).all { it >= 0 },
        )
    }

    fun increment(fault: ConnectivityFault): ConnectivityFaultCounters = when (fault) {
        ConnectivityFault.DISCOVERY_TIMEOUT -> copy(discoveryTimeouts = discoveryTimeouts + 1)
        ConnectivityFault.AUTHENTICATION_REJECTED -> copy(authenticationRejections = authenticationRejections + 1)
        ConnectivityFault.TRANSPORT_LOST -> copy(transportLosses = transportLosses + 1)
        ConnectivityFault.DUPLICATE_COMMAND -> copy(duplicateCommands = duplicateCommands + 1)
        ConnectivityFault.UNKNOWN_DELIVERY -> copy(unknownDeliveries = unknownDeliveries + 1)
        ConnectivityFault.MALFORMED_FRAME -> copy(malformedFrames = malformedFrames + 1)
    }
}

data class ConnectivityDiagnosticsSnapshot(
    val topology: ConnectivityTopology,
    val history: List<ConnectivityHistoryEntry>,
    val faults: ConnectivityFaultCounters,
    val droppedHistoryEntries: Long,
    val droppedTopologyNodes: Long,
    val generatedAtMillis: Long,
) {
    init {
        require(history.size <= MAX_HISTORY_ENTRIES)
        require(droppedHistoryEntries >= 0)
        require(droppedTopologyNodes >= 0)
        require(generatedAtMillis >= 0)
    }
}

/** Bounded diagnostics projection; it cannot acknowledge commands or author workflow truth. */
class ConnectivityDiagnosticsStore(
    private val maxHistoryEntries: Int = MAX_HISTORY_ENTRIES,
    private val maxTopologyNodes: Int = MAX_TOPOLOGY_NODES,
) {
    private val nodes = linkedMapOf<String, ConnectivityNode>()
    private val history = ArrayDeque<ConnectivityHistoryEntry>()
    private var sequence = 0L
    private var droppedHistory = 0L
    private var droppedNodes = 0L
    private var faults = ConnectivityFaultCounters()
    private var activeNodeId: String? = null

    init {
        require(maxHistoryEntries in 1..MAX_HISTORY_ENTRIES)
        require(maxTopologyNodes in 1..MAX_TOPOLOGY_NODES)
    }

    @Synchronized
    fun observeNode(node: ConnectivityNode) {
        if (node.id !in nodes && nodes.size >= maxTopologyNodes) {
            nodes.remove(nodes.keys.first())
            droppedNodes++
        }
        nodes[node.id] = node
    }

    @Synchronized
    fun setActiveNode(nodeId: String?) {
        require(nodeId == null || nodeId in nodes) { "Active node is not observed" }
        activeNodeId = nodeId
    }

    @Synchronized
    fun recordTransition(
        transition: ConnectivityTransition,
        transport: DaemonTransportKind,
        atMillis: Long,
        durationMillis: Long? = null,
    ) {
        require(atMillis >= 0)
        sequence++
        if (history.size == maxHistoryEntries) {
            history.removeFirst()
            droppedHistory++
        }
        history.addLast(ConnectivityHistoryEntry(sequence, transition, transport, atMillis, durationMillis))
    }

    @Synchronized
    fun recordFault(fault: ConnectivityFault) {
        faults = faults.increment(fault)
    }

    @Synchronized
    fun snapshot(generatedAtMillis: Long): ConnectivityDiagnosticsSnapshot {
        require(generatedAtMillis >= 0)
        return ConnectivityDiagnosticsSnapshot(
            ConnectivityTopology(nodes.values.toList(), activeNodeId),
            history.toList(),
            faults,
            droppedHistory,
            droppedNodes,
            generatedAtMillis,
        )
    }
}

private const val MAX_HISTORY_ENTRIES = 64
private const val MAX_TOPOLOGY_NODES = 32
private const val MAX_TOPOLOGY_DEPTH = 8
private val OPAQUE_ID_PATTERN = Regex("[a-z][a-z0-9-]{2,63}")
