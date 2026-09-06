package dev.agentrelay.companion.api

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExtensionProjectionModelsTest {
    @Test
    fun safeActionIsVisibleAndExecutableBeforeExpiry() {
        val projection = projection(ExtensionProjectionKind.SAFE_ACTION)

        assertTrue(projection.visibleAt(100))
        assertTrue(projection.canExecuteOnWatch(100))
        assertFalse(projection.visibleAt(200))
    }

    @Test
    fun consequentialKindsRequirePhoneConfirmation() {
        val projection = projection(ExtensionProjectionKind.WORKFLOW_PROPOSAL, confirmation = true)

        assertTrue(projection.visibleAt(100))
        assertFalse(projection.canExecuteOnWatch(100))
        assertFailsWith<IllegalArgumentException> {
            projection(ExtensionProjectionKind.WORKFLOW_PROPOSAL)
        }
    }

    @Test
    fun rejectsProtectedContentAndRevokedProjection() {
        assertFailsWith<IllegalArgumentException> { projection(body = "private token") }
        assertFalse(projection(revoked = true).visibleAt(100))
    }

    private fun projection(
        kind: ExtensionProjectionKind = ExtensionProjectionKind.ALERT,
        confirmation: Boolean = false,
        body: String = "redacted extension update",
        revoked: Boolean = false,
    ) = ExtensionProjection(
        extensionId = "ext_v1_notifications",
        schemaVersion = 1,
        revision = 1,
        kind = kind,
        privacyClass = CompanionPrivacyClass.PUBLIC_SUMMARY,
        title = "Update",
        redactedBody = body,
        phoneConfirmationRequired = confirmation,
        expiresAtEpochMillis = 200,
        revoked = revoked,
    )
}
