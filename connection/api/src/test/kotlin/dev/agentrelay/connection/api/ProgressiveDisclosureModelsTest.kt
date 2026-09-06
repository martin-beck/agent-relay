package dev.agentrelay.connection.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProgressiveDisclosureModelsTest {
    private val tree = ProgressiveDisclosure.of(
        listOf(
            DisclosureNode(
                DisclosureNodeId("host"),
                DisclosureLevel.HOST,
                null,
                "Work host",
                1,
                DisclosureStatus.AVAILABLE,
            ),
            DisclosureNode(
                DisclosureNodeId("connection"),
                DisclosureLevel.CONNECTION,
                DisclosureNodeId("host"),
                "Secure connection",
                1,
                DisclosureStatus.ACTIVE,
            ),
            DisclosureNode(
                DisclosureNodeId("provider"),
                DisclosureLevel.PROVIDER,
                DisclosureNodeId("connection"),
                "Agent provider",
                1,
                DisclosureStatus.AVAILABLE,
            ),
            DisclosureNode(
                DisclosureNodeId("session"),
                DisclosureLevel.SESSION,
                DisclosureNodeId("provider"),
                "Session 1",
                1,
                DisclosureStatus.ACTIVE,
            ),
            DisclosureNode(
                DisclosureNodeId("terminal"),
                DisclosureLevel.TERMINAL,
                DisclosureNodeId("session"),
                "Terminal",
                0,
                DisclosureStatus.ACTIVE,
            ),
        ),
    )

    @Test
    fun `projection starts at hosts and expands one authority-owned level at a time`() {
        assertEquals(listOf("host"), tree.project().map { it.id.value })
        assertEquals(
            listOf("host", "connection", "provider"),
            tree.project(DisclosureQuery(setOf(DisclosureNodeId("host"), DisclosureNodeId("connection")))).map { it.id.value },
        )
    }

    @Test
    fun `projection is bounded and deterministic`() {
        val query = DisclosureQuery(
            expanded = setOf(DisclosureNodeId("host"), DisclosureNodeId("connection"), DisclosureNodeId("provider")),
            maxVisibleNodes = 3,
        )
        assertEquals(listOf("host", "connection", "provider"), tree.project(query).map { it.id.value })
    }

    @Test
    fun `invalid hierarchy and labels fail closed`() {
        assertFailsWith<IllegalArgumentException> { DisclosureNodeId("/secret") }
        assertFailsWith<IllegalArgumentException> {
            DisclosureNode(DisclosureNodeId("host"), DisclosureLevel.HOST, null, "secret\npath", 0, DisclosureStatus.AVAILABLE)
        }
        assertFailsWith<IllegalArgumentException> {
            ProgressiveDisclosure.of(
                listOf(
                    DisclosureNode(
                        DisclosureNodeId("host"),
                        DisclosureLevel.HOST,
                        null,
                        "Host",
                        0,
                        DisclosureStatus.AVAILABLE,
                    ),
                    DisclosureNode(
                        DisclosureNodeId("connection"),
                        DisclosureLevel.CONNECTION,
                        DisclosureNodeId("host"),
                        "Connection",
                        0,
                        DisclosureStatus.ACTIVE,
                    ),
                    DisclosureNode(
                        DisclosureNodeId("nested"),
                        DisclosureLevel.HOST,
                        DisclosureNodeId("connection"),
                        "Nested host",
                        0,
                        DisclosureStatus.ACTIVE,
                    ),
                ),
            )
        }
    }
}
