package dev.agentrelay.session.api

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Test

class SessionActivitySummaryTest {
    @Test
    fun generatedKindsEnforceTheirOpaqueArgumentShape() {
        val argumentKinds = listOf(
            SessionActivitySummaryKind.NAMED_TOOL_FAILED,
            SessionActivitySummaryKind.CONNECTION_RECONNECTED,
        )

        argumentKinds.forEach { kind ->
            assertFailsWith<IllegalArgumentException> {
                SessionActivitySummary.Generated(kind)
            }
            assertEquals(
                "Opaque label",
                SessionActivitySummary.Generated(kind, "Opaque label").argument,
            )
        }

        (SessionActivitySummaryKind.entries - argumentKinds.toSet()).forEach { kind ->
            assertFailsWith<IllegalArgumentException> {
                SessionActivitySummary.Generated(kind, "unexpected")
            }
            assertEquals(kind, SessionActivitySummary.Generated(kind).kind)
        }
    }

    @Test
    fun activitySummaryContentIsBoundedAndNonBlank() {
        assertFailsWith<IllegalArgumentException> {
            SessionActivitySummary.Verbatim(" ")
        }
        assertFailsWith<IllegalArgumentException> {
            SessionActivitySummary.Verbatim("x".repeat(16_385))
        }
        assertFailsWith<IllegalArgumentException> {
            SessionActivitySummary.Generated(
                SessionActivitySummaryKind.CONNECTION_RECONNECTED,
                "x".repeat(257),
            )
        }
    }
}
