package dev.agentrelay.provider.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExtensionModelsTest {
    private val manifest = ExtensionManifest(
        id = ExtensionId("calendar.connector"),
        displayName = "Calendar",
        apiVersion = ExtensionApiVersion(1, 2),
        schemaVersion = 3,
        capabilities = setOf(ExtensionCapability.CONNECTOR),
        permissions = setOf(ExtensionPermission.READ_FIELDS, ExtensionPermission.PROPOSE_ACTIONS),
        requestedFields = setOf(ExtensionField("calendar.events")),
        triggers = setOf(ExtensionField("calendar.refresh")),
        actions = setOf(ExtensionAction("calendar.propose")),
        budget = ExtensionResourceBudget(1024, 2048, 2, 1000),
    )

    @Test
    fun `compatible versions narrow permissions and accept idempotent invocation`() {
        assertTrue(manifest.compatibleWith(ExtensionApiVersion(1, 4), 3))
        assertEquals(
            setOf(ExtensionPermission.READ_FIELDS),
            manifest.narrowedPermissions(setOf(ExtensionPermission.READ_FIELDS)),
        )
        val ledger = ExtensionInvocationLedger()
        val invocation = invocation()
        val first = ledger.evaluate(manifest, invocation, ExtensionApiVersion(1, 4), 3, invocation.requestedPermissions, 10, 7)
        val duplicate = ledger.evaluate(manifest, invocation, ExtensionApiVersion(1, 4), 3, invocation.requestedPermissions, 10, 7)
        assertEquals(ExtensionInvocationDecision.Accepted(setOf(ExtensionPermission.READ_FIELDS)), first)
        assertEquals(first, duplicate)
    }

    @Test
    fun `invalid manifest bounds and identifiers fail closed`() {
        assertFailsWith<IllegalArgumentException> { ExtensionId("/secret") }
        assertFailsWith<IllegalArgumentException> { ExtensionResourceBudget(0, 1, 1, 1) }
        assertFailsWith<IllegalArgumentException> {
            manifest.copy(requestedFields = emptySet(), permissions = setOf(ExtensionPermission.READ_FIELDS))
        }
    }

    @Test
    fun `undeclared access version revocation budget cancellation and stale revision reject`() {
        val ledger = ExtensionInvocationLedger()
        val base = invocation()
        assertEquals(ExtensionRejection.STALE_REVISION, rejected(ledger, base, revision = 8))
        assertEquals(ExtensionRejection.REVOKED, rejected(ledger, base, revoked = true))
        assertEquals(ExtensionRejection.CANCELLED, rejected(ledger, base, cancelled = true))
        assertEquals(
            ExtensionRejection.UNDECLARED_FIELD,
            rejected(ledger, base.copy(input = mapOf(ExtensionField("private.secret") to "x"))),
        )
        assertEquals(
            ExtensionRejection.INCOMPATIBLE_VERSION,
            rejected(ledger, base, hostApi = ExtensionApiVersion(2, 0)),
        )
        val first = ledger.evaluate(manifest, base, ExtensionApiVersion(1, 2), 3, base.requestedPermissions, 10, 7)
        assertTrue(first is ExtensionInvocationDecision.Accepted)
        val second = base.copy(idempotencyKey = "second-key", revision = 7)
        assertTrue(
            ledger.evaluate(manifest, second, ExtensionApiVersion(1, 2), 3, second.requestedPermissions, 10, 7)
                is ExtensionInvocationDecision.Accepted,
        )
        assertEquals(ExtensionRejection.INVOCATION_BUDGET_EXCEEDED, rejected(ledger, base.copy(idempotencyKey = "third-key")))
    }

    private fun invocation(
        input: Map<ExtensionField, String> = mapOf(ExtensionField("calendar.events") to "opaque"),
        revision: Long = 7,
    ) = ExtensionInvocation(
        extensionId = manifest.id,
        idempotencyKey = "request-001",
        trigger = ExtensionField("calendar.refresh"),
        input = input,
        requestedPermissions = setOf(ExtensionPermission.READ_FIELDS),
        revision = revision,
    )

    private fun rejected(
        ledger: ExtensionInvocationLedger,
        invocation: ExtensionInvocation,
        hostApi: ExtensionApiVersion = ExtensionApiVersion(1, 2),
        revoked: Boolean = false,
        cancelled: Boolean = false,
        revision: Long = invocation.revision,
    ) = (
        ledger.evaluate(manifest, invocation, hostApi, 3, invocation.requestedPermissions, 10, revision, revoked, cancelled)
            as ExtensionInvocationDecision.Rejected
        ).reason
}
