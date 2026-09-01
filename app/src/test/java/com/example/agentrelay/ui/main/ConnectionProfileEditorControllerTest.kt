package com.example.agentrelay.ui.main

import com.example.agentrelay.data.SessionHubRuntime
import dev.agentrelay.connection.api.ConnectionCapability
import dev.agentrelay.connection.api.ConnectionChallengeId
import dev.agentrelay.connection.api.ConnectionIdentityDecision
import dev.agentrelay.connection.api.ConnectionProfileEditor
import dev.agentrelay.connection.api.ConnectionProfileFieldCondition
import dev.agentrelay.connection.api.ConnectionProfileField
import dev.agentrelay.connection.api.ConnectionProfileFieldId
import dev.agentrelay.connection.api.ConnectionProfileFieldInput
import dev.agentrelay.connection.api.ConnectionProfileFieldOption
import dev.agentrelay.connection.api.ConnectionProfileFieldType
import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProfileSaveResult
import dev.agentrelay.connection.api.ConnectionProfileSummary
import dev.agentrelay.connection.api.ConnectionProfileUpdate
import dev.agentrelay.connection.api.ConnectionProfileValidationException
import dev.agentrelay.connection.api.ConnectionProviderDescriptor
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.session.api.SessionDraft
import dev.agentrelay.session.api.SessionHubSnapshot
import dev.agentrelay.session.api.SessionLocator
import dev.agentrelay.session.runtime.SessionConnectionKey
import dev.agentrelay.session.runtime.SessionCoordinatorSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionProfileEditorControllerTest {
    @Test
    fun addEditAndSaveFlowUsesProviderOwnedFieldsAndReloadsSanitizedEditor() = runTest {
        val runtime = FakeProfileRuntime()
        val reportedErrors = mutableListOf<String>()
        val controller = ConnectionProfileEditorController(
            scope = this,
            runtime = { runtime },
            reportError = reportedErrors::add,
        )

        controller.add(PROVIDER_ID.value)
        advanceUntilIdle()
        val opened = assertInstance<ConnectionProfileEditorUiState.Editing>(controller.state.value)
        assertNull(opened.profileId)
        controller.updateField(AUTHENTICATION.value, "unsupported")
        assertEquals(
            listOf("profile-label", "authentication"),
            assertInstance<ConnectionProfileEditorUiState.Editing>(controller.state.value).visibleFields().map { it.id },
        )
        controller.updateField(AUTHENTICATION.value, "password")
        assertEquals(listOf("profile-label", "authentication", "password"), opened.visibleFields().map { it.id })

        controller.updateField(LABEL.value, "Staging server")
        controller.updateField(PASSWORD.value, "temporary password")
        assertFalse(controller.state.value.toString().contains("temporary password"))
        controller.save()
        advanceUntilIdle()

        assertEquals("Staging server", runtime.savedText[LABEL])
        assertEquals("temporary password", runtime.savedPassword)
        val saved = assertInstance<ConnectionProfileEditorUiState.Editing>(controller.state.value)
        assertEquals(PROFILE_ID.value, saved.profileId)
        assertEquals("Profile saved securely.", saved.notice)
        assertEquals("", saved.fields.single { it.id == PASSWORD.value }.value)
        assertTrue(saved.fields.single { it.id == PASSWORD.value }.hasStoredSecret)
        assertTrue(reportedErrors.isEmpty())
    }

    @Test
    fun validationAndUnexpectedFailuresAreActionableWithoutLeakingDetails() = runTest {
        val runtime = FakeProfileRuntime()
        val controller = ConnectionProfileEditorController(
            scope = this,
            runtime = { runtime },
            reportError = {},
        )
        controller.add(PROVIDER_ID.value)
        advanceUntilIdle()

        runtime.saveFailure = ConnectionProfileValidationException(
            mapOf(PASSWORD to "Enter a password."),
        )
        controller.save()
        advanceUntilIdle()

        val invalid = assertInstance<ConnectionProfileEditorUiState.Editing>(controller.state.value)
        assertEquals("Enter a password.", invalid.fieldErrors[PASSWORD.value])
        assertEquals("Correct the highlighted profile fields.", invalid.error)

        runtime.saveFailure = IllegalStateException("private-host.example secret failed")
        controller.save()
        advanceUntilIdle()

        val failed = assertInstance<ConnectionProfileEditorUiState.Editing>(controller.state.value)
        assertEquals("The connection profile could not be saved securely.", failed.error)
        assertFalse(failed.toString().contains("private-host"))
        assertFalse(failed.toString().contains("secret failed"))
    }

    @Test
    fun successfulSaveWithReloadFailureReportsPartialSuccess() = runTest {
        val runtime = FakeProfileRuntime()
        val errors = mutableListOf<String>()
        val controller = ConnectionProfileEditorController(
            scope = this,
            runtime = { runtime },
            reportError = errors::add,
        )
        controller.add(PROVIDER_ID.value)
        advanceUntilIdle()
        runtime.existingEditorFailure = IllegalStateException("private reload detail")
        controller.updateField(LABEL.value, "Saved profile")

        controller.save()
        advanceUntilIdle()

        assertEquals("Saved profile", runtime.savedText[LABEL])
        assertNull(controller.state.value)
        assertEquals(
            "The profile was saved, but its editor could not be refreshed.",
            errors.single(),
        )
        assertFalse(errors.single().contains("private reload detail"))
    }

    @Test
    fun openFailureAndDismissalUseGenericMessagesAndClearEditorState() = runTest {
        val runtime = FakeProfileRuntime()
        val errors = mutableListOf<String>()
        val controller = ConnectionProfileEditorController(
            scope = this,
            runtime = { runtime },
            reportError = errors::add,
        )
        runtime.editorFailure = IllegalStateException("private editor detail")

        controller.add(PROVIDER_ID.value)
        advanceUntilIdle()

        assertNull(controller.state.value)
        assertEquals("The connection profile editor could not be opened.", errors.single())
        assertFalse(errors.single().contains("private editor detail"))

        runtime.editorFailure = null
        controller.add(PROVIDER_ID.value)
        advanceUntilIdle()
        assertTrue(controller.state.value is ConnectionProfileEditorUiState.Editing)

        controller.dismiss()

        assertNull(controller.state.value)
    }

    @Test
    fun deleteFailureRestoresEditorWithGenericError() = runTest {
        val runtime = FakeProfileRuntime(existingProfile = true)
        val controller = ConnectionProfileEditorController(
            scope = this,
            runtime = { runtime },
            reportError = {},
        )
        val key = SessionConnectionKey(PROVIDER_ID, PROFILE_ID).stableUiKey
        runtime.deleteFailure = IllegalStateException("private delete detail")
        controller.edit(key)
        advanceUntilIdle()
        controller.requestDeletion()

        controller.delete()
        advanceUntilIdle()

        val failed = assertInstance<ConnectionProfileEditorUiState.Editing>(controller.state.value)
        assertEquals("The connection profile could not be deleted securely.", failed.error)
        assertFalse(failed.confirmDelete)
        assertFalse(failed.isBusy)
        assertFalse(failed.toString().contains("private delete detail"))
        assertTrue(runtime.deleted.isEmpty())
    }

    @Test
    fun deleteRequiresConfirmationAndUnknownTargetsReportGenericErrors() = runTest {
        val runtime = FakeProfileRuntime(existingProfile = true)
        val errors = mutableListOf<String>()
        val controller = ConnectionProfileEditorController(
            scope = this,
            runtime = { runtime },
            reportError = errors::add,
        )
        val key = SessionConnectionKey(PROVIDER_ID, PROFILE_ID).stableUiKey

        controller.edit(key)
        advanceUntilIdle()
        controller.requestDeletion()
        assertTrue(
            assertInstance<ConnectionProfileEditorUiState.Editing>(controller.state.value).confirmDelete,
        )
        controller.cancelDeletion()
        assertFalse(
            assertInstance<ConnectionProfileEditorUiState.Editing>(controller.state.value).confirmDelete,
        )
        controller.requestDeletion()
        controller.delete()
        advanceUntilIdle()

        assertEquals(listOf(PROVIDER_ID to PROFILE_ID), runtime.deleted)
        assertNull(controller.state.value)

        controller.edit("missing")
        assertEquals("That connection profile is no longer available.", errors.last())
        controller.add("missing.provider")
        assertEquals("That connection provider is no longer available.", errors.last())
    }
}

private inline fun <reified T> assertInstance(value: Any?): T {
    assertTrue("Expected ${T::class.java.simpleName}, but was ${value?.javaClass?.simpleName}", value is T)
    return value as T
}

private class FakeProfileRuntime(
    existingProfile: Boolean = false,
) : SessionHubRuntime {
    override val connectionProviders = listOf(
        ConnectionProviderDescriptor(
            id = PROVIDER_ID,
            displayName = "Secure Shell",
            providerVersion = "1",
            capabilities = setOf(ConnectionCapability.PROFILE_MANAGEMENT),
        ),
    )
    override val coordinatorSnapshot: StateFlow<SessionCoordinatorSnapshot> = MutableStateFlow(
        SessionCoordinatorSnapshot(
            profiles = if (existingProfile) listOf(profileSummary()) else emptyList(),
        ),
    )
    override val sessionSnapshot: StateFlow<SessionHubSnapshot> =
        MutableStateFlow(SessionHubSnapshot())
    var saveFailure: Throwable? = null
    var savedText: Map<ConnectionProfileFieldId, String> = emptyMap()
    var savedPassword: String? = null
    var existingEditorFailure: Throwable? = null
    val deleted = mutableListOf<Pair<ConnectionProviderId, ConnectionProfileId>>()
    var editorFailure: Throwable? = null
    var deleteFailure: Throwable? = null

    override suspend fun refreshProfiles() = Unit

    override suspend fun profileEditor(
        providerId: ConnectionProviderId,
        profileId: ConnectionProfileId?,
    ): ConnectionProfileEditor {
        check(providerId == PROVIDER_ID)
        editorFailure?.let { throw it }
        if (profileId != null) {
            existingEditorFailure?.let { throw it }
        }
        return editor(profileId)
    }

    override suspend fun saveProfile(
        update: ConnectionProfileUpdate,
    ): ConnectionProfileSaveResult {
        saveFailure?.let { throw it }
        savedText = update.fields.mapNotNull { (id, input) ->
            (input as? ConnectionProfileFieldInput.Text)?.let { id to it.value }
        }.toMap()
        savedPassword = (update.fields[PASSWORD] as? ConnectionProfileFieldInput.Secret)
            ?.useChars(CharArray::concatToString)
        return ConnectionProfileSaveResult(
            profile = profileSummary(),
            notice = "Profile saved securely.",
        )
    }

    override suspend fun deleteProfile(
        providerId: ConnectionProviderId,
        profileId: ConnectionProfileId,
    ) {
        deleteFailure?.let { throw it }
        deleted += providerId to profileId
    }

    override suspend fun connect(key: SessionConnectionKey) = Unit

    override suspend fun disconnect(key: SessionConnectionKey) = Unit

    override suspend fun resolveIdentityChallenge(
        key: SessionConnectionKey,
        challengeId: ConnectionChallengeId,
        decision: ConnectionIdentityDecision,
    ): Boolean = false

    override suspend fun markSessionRead(locator: SessionLocator) = Unit

    override suspend fun updateDraft(locator: SessionLocator, draft: SessionDraft) = Unit

    override suspend fun resumeSession(locator: SessionLocator) = Unit

    override suspend fun sendInput(locator: SessionLocator, text: String) = Unit

    override suspend fun steerActiveTurn(locator: SessionLocator, text: String) = Unit

    override suspend fun interrupt(locator: SessionLocator) = Unit
}

private fun editor(profileId: ConnectionProfileId?) = ConnectionProfileEditor(
    providerId = PROVIDER_ID,
    providerName = "Secure Shell",
    profileId = profileId,
    title = if (profileId == null) "Add Secure Shell profile" else "Edit Secure Shell profile",
    fields = listOf(
        ConnectionProfileField(
            id = LABEL,
            label = "Profile name",
            type = ConnectionProfileFieldType.TEXT,
            value = if (profileId == null) "" else "Existing server",
            required = true,
            maxLength = 128,
        ),
        ConnectionProfileField(
            id = AUTHENTICATION,
            label = "Authentication",
            type = ConnectionProfileFieldType.SINGLE_CHOICE,
            value = "password",
            required = true,
            options = listOf(ConnectionProfileFieldOption("password", "Password")),
        ),
        ConnectionProfileField(
            id = PASSWORD,
            label = "Password",
            type = ConnectionProfileFieldType.PASSWORD,
            required = profileId == null,
            maxLength = 16_384,
            visibleWhen = listOf(
                ConnectionProfileFieldCondition(AUTHENTICATION, "password"),
            ),
            hasStoredSecret = profileId != null,
        ),
    ),
    canDelete = profileId != null,
)

private fun profileSummary() = ConnectionProfileSummary(
    id = PROFILE_ID,
    providerId = PROVIDER_ID,
    label = "Existing server",
    target = "example.test",
    authenticationLabel = "Password",
)

private val PROVIDER_ID = ConnectionProviderId("ssh.secure-shell")
private val PROFILE_ID = ConnectionProfileId("profile")
private val LABEL = ConnectionProfileFieldId("profile-label")
private val AUTHENTICATION = ConnectionProfileFieldId("authentication")
private val PASSWORD = ConnectionProfileFieldId("password")
