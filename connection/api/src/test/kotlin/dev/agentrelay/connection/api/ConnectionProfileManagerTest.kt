package dev.agentrelay.connection.api

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ConnectionProfileManagerTest {
    @Test
    fun fieldModelsEnforceProviderSchemaInvariants() {
        val choice = ConnectionProfileField(
            id = AUTHENTICATION,
            label = "Authentication",
            type = ConnectionProfileFieldType.SINGLE_CHOICE,
            value = "password",
            required = true,
            options = listOf(ConnectionProfileFieldOption("password", "Password")),
        )
        val secret = ConnectionProfileField(
            id = PASSWORD,
            label = "Password",
            type = ConnectionProfileFieldType.PASSWORD,
            supportingText = "Stored securely.",
            maxLength = 128,
            hasStoredSecret = true,
            visibleWhen = listOf(ConnectionProfileFieldCondition(AUTHENTICATION, "password")),
        )

        assertEquals("password", choice.options.single().value)
        assertTrue(secret.hasStoredSecret)
        assertEquals("authentication", AUTHENTICATION.toString())
        assertFailsWith<IllegalArgumentException> { ConnectionProfileFieldId("Invalid field") }
        assertFailsWith<IllegalArgumentException> { ConnectionProfileFieldOption("", "Password") }
        assertFailsWith<IllegalArgumentException> { ConnectionProfileFieldOption("password", " ") }
        assertFailsWith<IllegalArgumentException> {
            ConnectionProfileFieldCondition(AUTHENTICATION, "")
        }
        assertFailsWith<IllegalArgumentException> {
            field(label = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            field(maxLength = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            field(maxLength = 4 * 1024 * 1024 + 1)
        }
        assertFailsWith<IllegalArgumentException> {
            field(value = "too long", maxLength = 3)
        }
        assertFailsWith<IllegalArgumentException> {
            field(type = ConnectionProfileFieldType.SINGLE_CHOICE)
        }
        assertFailsWith<IllegalArgumentException> {
            field(options = listOf(ConnectionProfileFieldOption("value", "Value")))
        }
        assertFailsWith<IllegalArgumentException> {
            field(type = ConnectionProfileFieldType.READ_ONLY, required = true)
        }
        assertFailsWith<IllegalArgumentException> {
            field(hasStoredSecret = true)
        }
    }

    @Test
    fun editorEnforcesStableProviderOwnedForm() {
        val field = field()
        val profileId = ConnectionProfileId("profile")
        val editor = ConnectionProfileEditor(
            providerId = PROVIDER_ID,
            providerName = "Secure Shell",
            profileId = profileId,
            title = "Edit profile",
            fields = listOf(field),
            canDelete = true,
        )
        val result = ConnectionProfileSaveResult(
            profile = ConnectionProfileSummary(
                id = profileId,
                providerId = PROVIDER_ID,
                label = "Example",
                target = "example.test",
                authenticationLabel = "Password",
            ),
            notice = "Saved.",
        )

        assertEquals("Edit profile", editor.title)
        assertEquals("Saved.", result.notice)
        assertFailsWith<IllegalArgumentException> {
            editor.copy(providerName = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            editor.copy(title = "")
        }
        assertFailsWith<IllegalArgumentException> {
            editor.copy(fields = emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            editor.copy(fields = listOf(field, field))
        }
        assertFailsWith<IllegalArgumentException> {
            editor.copy(profileId = null, canDelete = true)
        }
        assertFailsWith<IllegalArgumentException> {
            editor.copy(
                fields = listOf(
                    field.copy(
                        visibleWhen = listOf(
                            ConnectionProfileFieldCondition(
                                ConnectionProfileFieldId("missing-field"),
                                "value",
                            ),
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun operationModelsRequireSavedProfilesUniqueIdsAndBoundedPrintableText() {
        val operation = ConnectionProfileOperation(
            id = ConnectionProfileOperationId("verify-key-login"),
            label = "Test key-only login",
            supportingText = "Verify passwordless authentication.",
        )
        val editor = ConnectionProfileEditor(
            providerId = PROVIDER_ID,
            providerName = "Secure Shell",
            profileId = ConnectionProfileId("profile"),
            title = "Edit profile",
            fields = listOf(field()),
            canDelete = true,
            operations = listOf(operation),
        )

        assertEquals(operation, editor.operations.single())
        assertFalse(operation.requiresConfirmation)
        assertFailsWith<IllegalArgumentException> {
            ConnectionProfileOperationId("Invalid operation")
        }
        assertFailsWith<IllegalArgumentException> {
            operation.copy(label = "x".repeat(129))
        }
        assertFailsWith<IllegalArgumentException> {
            operation.copy(supportingText = "Unsafe\ntext")
        }
        assertFailsWith<IllegalArgumentException> {
            operation.copy(confirmationTitle = "Confirm", confirmationMessage = null)
        }
        assertFailsWith<IllegalArgumentException> {
            editor.copy(profileId = null, canDelete = false)
        }
        assertFailsWith<IllegalArgumentException> {
            editor.copy(operations = listOf(operation, operation))
        }
        assertFailsWith<IllegalArgumentException> {
            ConnectionProfileOperationResult(" ")
        }
        assertFailsWith<IllegalArgumentException> {
            ConnectionProfileOperationException("unsafe\nmessage")
        }
        assertFailsWith<IllegalArgumentException> {
            ConnectionProfileDeleteException("x".repeat(1_025))
        }
    }

    @Test
    fun secretInputsCopyTemporaryBuffersAndCloseWithTheirUpdate() {
        val source = charArrayOf('s', 'a', 'f', 'e')
        val secret = ConnectionProfileFieldInput.Secret.copyOf(source)
        source.fill('x')

        assertEquals(4, secret.length)
        assertFalse(secret.isEmpty)
        assertEquals("ConnectionProfileFieldInput.Secret([REDACTED])", secret.toString())
        assertContentEquals(charArrayOf('s', 'a', 'f', 'e'), secret.useChars(CharArray::copyOf))
        secret.useChars { it.fill('y') }
        assertContentEquals(charArrayOf('s', 'a', 'f', 'e'), secret.useChars(CharArray::copyOf))

        val update = ConnectionProfileUpdate(
            providerId = PROVIDER_ID,
            profileId = null,
            fields = mapOf(
                PROFILE_LABEL to ConnectionProfileFieldInput.Text("Example"),
                PASSWORD to secret,
            ),
        )
        update.close()

        assertFailsWith<IllegalStateException> { secret.length }
        assertFailsWith<IllegalStateException> { secret.isEmpty }
        assertFailsWith<IllegalStateException> { secret.useChars { it.size } }
        assertFailsWith<IllegalArgumentException> {
            ConnectionProfileUpdate(PROVIDER_ID, null, emptyMap())
        }
    }

    @Test
    fun validationErrorsRequireActionableFieldMessages() {
        val error = ConnectionProfileValidationException(
            mapOf(PROFILE_LABEL to "Enter a profile name."),
        )

        assertEquals("Enter a profile name.", error.fieldErrors.getValue(PROFILE_LABEL))
        assertEquals("Connection profile input is invalid", error.message)
        assertFailsWith<IllegalArgumentException> {
            ConnectionProfileValidationException(emptyMap())
        }
        assertFailsWith<IllegalArgumentException> {
            ConnectionProfileValidationException(mapOf(PROFILE_LABEL to " "))
        }
    }
}

private fun field(
    label: String = "Profile name",
    type: ConnectionProfileFieldType = ConnectionProfileFieldType.TEXT,
    value: String = "",
    required: Boolean = false,
    maxLength: Int = 128,
    options: List<ConnectionProfileFieldOption> = emptyList(),
    hasStoredSecret: Boolean = false,
) = ConnectionProfileField(
    id = PROFILE_LABEL,
    label = label,
    type = type,
    value = value,
    required = required,
    maxLength = maxLength,
    options = options,
    hasStoredSecret = hasStoredSecret,
)

private val PROVIDER_ID = ConnectionProviderId("ssh.secure-shell")
private val PROFILE_LABEL = ConnectionProfileFieldId("profile-label")
private val AUTHENTICATION = ConnectionProfileFieldId("authentication")
private val PASSWORD = ConnectionProfileFieldId("password")
