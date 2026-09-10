/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package dev.agentrelay.connection.api

@JvmInline
value class DisclosureNodeId(val value: String) {
    init {
        require(value.matches(Regex("[a-z][a-z0-9.-]{1,63}"))) { "Disclosure id must be stable" }
    }

    override fun toString(): String = value
}

enum class DisclosureLevel { HOST, CONNECTION, PROVIDER, SESSION, TERMINAL }

enum class DisclosureStatus { AVAILABLE, CONNECTING, ACTIVE, FAILED, HIDDEN }

data class DisclosureNode(
    val id: DisclosureNodeId,
    val level: DisclosureLevel,
    val parentId: DisclosureNodeId?,
    val redactedLabel: String,
    val childCount: Int,
    val status: DisclosureStatus,
) {
    init {
        require(redactedLabel.isNotBlank() && redactedLabel.length <= 80) { "Disclosure label is invalid" }
        require(redactedLabel.none { it == '\n' || it == '\r' }) { "Disclosure label must be single-line" }
        require(childCount >= 0) { "Child count must not be negative" }
        require(level == DisclosureLevel.HOST || parentId != null) { "Non-root disclosure nodes need a parent" }
        require(level != DisclosureLevel.HOST || parentId == null) { "Hosts cannot have a parent" }
    }
}

data class DisclosureQuery(
    val expanded: Set<DisclosureNodeId> = emptySet(),
    val maxVisibleNodes: Int = 128,
) {
    init {
        require(maxVisibleNodes in 1..512) { "Visible node budget is out of bounds" }
    }
}

class ProgressiveDisclosure private constructor(private val nodes: List<DisclosureNode>) {
    fun project(query: DisclosureQuery = DisclosureQuery()): List<DisclosureNode> {
        val byParent = nodes.groupBy { it.parentId }
        val visible = mutableListOf<DisclosureNode>()
        fun visit(parentId: DisclosureNodeId?) {
            byParent[parentId].orEmpty()
                .sortedWith(compareBy<DisclosureNode> { it.level.ordinal }.thenBy { it.redactedLabel }.thenBy { it.id.value })
                .forEach { node ->
                    if (visible.size >= query.maxVisibleNodes) return
                    visible += node
                    if (node.id in query.expanded) visit(node.id)
                }
        }
        visit(null)
        return visible
    }

    companion object {
        fun of(nodes: List<DisclosureNode>): ProgressiveDisclosure {
            require(nodes.map(DisclosureNode::id).toSet().size == nodes.size) { "Disclosure ids must be unique" }
            val byId = nodes.associateBy(DisclosureNode::id)
            nodes.forEach { node ->
                node.parentId?.let { parent ->
                    val parentNode = byId[parent] ?: error("Disclosure parent is missing")
                    require(parentNode.level.ordinal < node.level.ordinal) { "Disclosure hierarchy must move downward" }
                }
            }
            return ProgressiveDisclosure(nodes)
        }
    }
}
