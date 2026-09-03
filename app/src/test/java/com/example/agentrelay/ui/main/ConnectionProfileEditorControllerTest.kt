package com.example.agentrelay.ui.main

import com.example.agentrelay.R
import com.example.agentrelay.data.SessionHubRuntime
import dev.agentrelay.connection.api.ConnectionCapability
import dev.agentrelay.connection.api.ConnectionChallengeId
import dev.agentrelay.connection.api.ConnectionIdentityDecision
import dev.agentrelay.connection.api.ConnectionProfileDeleteException
import dev.agentrelay.connection.api.ConnectionProfileEditor
import dev.agentrelay.connection.api.ConnectionProfileFieldCondition
import dev.agentrelay.connection.api.ConnectionProfileField
import dev.agentrelay.connection.api.ConnectionProfileFieldId
import dev.agentrelay.connection.api.ConnectionProfileFieldInput
import dev.agentrelay.connection.api.ConnectionProfileFieldOption
import dev.agentrelay.connection.api.ConnectionProfileFieldType
import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProfileOperation
import dev.agentrelay.connection.api.ConnectionProfileOperationException
import dev.agentrelay.connection.api.ConnectionProfileOperationId
import dev.agentrelay.connection.api.ConnectionProfileOperationResult
import dev.agentrelay.connection.api.ConnectionProfileSaveResult
import dev.agentrelay.connection.api.ConnectionProfileSummary
import dev.agentrelay.connection.api.ConnectionProfileUpdate
import dev.agentrelay.connection.api.ConnectionProfileValidationException
import dev.agentrelay.connection.api.ConnectionProviderDescriptor
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.StartSessionOptions
import dev.agentrelay.session.api.SessionDraft
import dev.agentrelay.session.api.SessionHubSnapshot
import dev.agentrelay.session.api.SessionLocator
import dev.agentrelay.session.runtime.AgentEndpointKey
import dev.agentrelay.session.runtime.SessionConnectionKey
import dev.agentrelay.session.runtime.SessionCoordinatorSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
        val reportedErrors = mutableListOf<UiMessage>()
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
        assertEquals(UiMessage.Verbatim("Profile saved securely."), saved.notice)
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
        assertEquals(UiMessage.Localized(R.string.profile_error_validation), invalid.error)

        runtime.saveFailure = IllegalStateException("private-host.example secret failed")
        controller.save()
        advanceUntilIdle()

        val failed = assertInstance<ConnectionProfileEditorUiState.Editing>(controller.state.value)
        assertEquals(UiMessage.Localized(R.string.profile_error_save), failed.error)
        assertFalse(failed.toString().contains("private-host"))
        assertFalse(failed.toString().contains("secret failed"))
    }

    @Test
    fun successfulSaveWithReloadFailureReportsPartialSuccess() = runTest {
        val runtime = FakeProfileRuntime()
        val errors = mutableListOf<UiMessage>()
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
            UiMessage.Localized(R.string.profile_error_save_refresh),
            errors.single(),
        )
        assertFalse(errors.single().toString().contains("private reload detail"))
    }

    @Test
    fun openFailureAndDismissalUseGenericMessagesAndClearEditorState() = runTest {
        val runtime = FakeProfileRuntime()
        val errors = mutableListOf<UiMessage>()
        val controller = ConnectionProfileEditorController(
            scope = this,
            runtime = { runtime },
            reportError = errors::add,
        )
        runtime.editorFailure = IllegalStateException("private editor detail")

        controller.add(PROVIDER_ID.value)
        advanceUntilIdle()

        assertNull(controller.state.value)
        assertEquals(UiMessage.Localized(R.string.profile_error_open), errors.single())
        assertFalse(errors.single().toString().contains("private editor detail"))

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
        assertEquals(UiMessage.Localized(R.string.profile_error_delete), failed.error)
        assertFalse(failed.confirmDelete)
        assertFalse(failed.isBusy)
        assertFalse(failed.toString().contains("private delete detail"))
        assertTrue(runtime.deleted.isEmpty())
    }

    @Test
    fun deleteRequiresConfirmationAndUnknownTargetsReportGenericErrors() = runTest {
        val runtime = FakeProfileRuntime(existingProfile = true)
        val errors = mutableListOf<UiMessage>()
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
        assertEquals(UiMessage.Localized(R.string.profile_error_profile_unavailable), errors.last())
        controller.add("missing.provider")
        assertEquals(UiMessage.Localized(R.string.profile_error_provider_unavailable), errors.last())
    }

    @Test
    fun profileOperationsConfirmSensitiveWorkAndRunDirectChecksImmediately() = runTest {
        val runtime = FakeProfileRuntime(existingProfile = true)
        val controller = ConnectionProfileEditorController(
            scope = this,
            runtime = { runtime },
            reportError = {},
        )
        val key = SessionConnectionKey(PROVIDER_ID, PROFILE_ID).stableUiKey
        controller.edit(key)
        advanceUntilIdle()

        controller.requestOperation(INSTALL_OPERATION.value)

        val confirming = assertInstance<ConnectionProfileEditorUiState.Editing>(controller.state.value)
        assertEquals(INSTALL_OPERATION.value, confirming.confirmOperationId)
        assertTrue(runtime.performedOperations.isEmpty())
        controller.cancelOperation()
        assertNull(
            assertInstance<ConnectionProfileEditorUiState.Editing>(controller.state.value)
                .confirmOperationId,
        )

        controller.requestOperation(INSTALL_OPERATION.value)
        controller.confirmOperation()
        val installing = assertInstance<ConnectionProfileEditorUiState.Editing>(controller.state.value)
        assertTrue(installing.isBusy)
        assertEquals(INSTALL_OPERATION.value, installing.activeOperationId)
        assertNull(installing.confirmOperationId)
        advanceUntilIdle()

        assertEquals(
            listOf(Triple(PROVIDER_ID, PROFILE_ID, INSTALL_OPERATION)),
            runtime.performedOperations,
        )
        assertEquals(
            UiMessage.Verbatim("Profile operation completed."),
            assertInstance<ConnectionProfileEditorUiState.Editing>(controller.state.value).notice,
        )

        controller.requestOperation(VERIFY_OPERATION.value)
        val verifying = assertInstance<ConnectionProfileEditorUiState.Editing>(controller.state.value)
        assertTrue(verifying.isBusy)
        assertEquals(VERIFY_OPERATION.value, verifying.activeOperationId)
        advanceUntilIdle()

        assertEquals(
            listOf(
                Triple(PROVIDER_ID, PROFILE_ID, INSTALL_OPERATION),
                Triple(PROVIDER_ID, PROFILE_ID, VERIFY_OPERATION),
            ),
            runtime.performedOperations,
        )
    }

    @Test
    fun successfulOperationWithReloadFailureReportsPartialSuccess() = runTest {
        val runtime = FakeProfileRuntime(existingProfile = true)
        val errors = mutableListOf<UiMessage>()
        val controller = ConnectionProfileEditorController(
            scope = this,
            runtime = { runtime },
            reportError = errors::add,
        )
        val key = SessionConnectionKey(PROVIDER_ID, PROFILE_ID).stableUiKey
        controller.edit(key)
        advanceUntilIdle()
        runtime.existingEditorFailure = IllegalStateException("private operation reload detail")

        controller.requestOperation(VERIFY_OPERATION.value)
        advanceUntilIdle()

        assertEquals(
            listOf(Triple(PROVIDER_ID, PROFILE_ID, VERIFY_OPERATION)),
            runtime.performedOperations,
        )
        assertNull(controller.state.value)
        assertEquals(
            listOf(UiMessage.Localized(R.string.profile_error_operation_refresh)),
            errors,
        )
        assertFalse(errors.single().toString().contains("private operation reload detail"))
    }

    @Test
    fun unsavedProfileChangesBlockEveryProfileOperation() = runTest {
        val runtime = FakeProfileRuntime(existingProfile = true)
        val controller = ConnectionProfileEditorController(
            scope = this,
            runtime = { runtime },
            reportError = {},
        )
        val key = SessionConnectionKey(PROVIDER_ID, PROFILE_ID).stableUiKey
        controller.edit(key)
        advanceUntilIdle()
        controller.updateField(LABEL.value, "Changed profile")

        controller.requestOperation(INSTALL_OPERATION.value)
        controller.requestOperation(VERIFY_OPERATION.value)
        advanceUntilIdle()

        val editor = assertInstance<ConnectionProfileEditorUiState.Editing>(controller.state.value)
        assertTrue(editor.hasUnsavedChanges)
        assertNull(editor.confirmOperationId)
        assertNull(editor.activeOperationId)
        assertTrue(runtime.performedOperations.isEmpty())
    }

    @Test
    fun profileOperationFailuresExposeOnlyActionableOrGenericMessages() = runTest {
        val runtime = FakeProfileRuntime(existingProfile = true)
        val controller = ConnectionProfileEditorController(
            scope = this,
            runtime = { runtime },
            reportError = {},
        )
        val key = SessionConnectionKey(PROVIDER_ID, PROFILE_ID).stableUiKey
        controller.edit(key)
        advanceUntilIdle()
        runtime.operationFailure = ConnectionProfileOperationException(
            "Review the SSH host identity and retry.",
            IllegalStateException("private-host.example"),
        )

        controller.requestOperation(VERIFY_OPERATION.value)
        advanceUntilIdle()

        val actionable = assertInstance<ConnectionProfileEditorUiState.Editing>(controller.state.value)
        assertEquals(UiMessage.Verbatim("Review the SSH host identity and retry."), actionable.error)
        assertFalse(actionable.toString().contains("private-host"))

        runtime.operationFailure = IllegalStateException("secret operation detail")
        controller.requestOperation(VERIFY_OPERATION.value)
        advanceUntilIdle()

        val generic = assertInstance<ConnectionProfileEditorUiState.Editing>(controller.state.value)
        assertEquals(
            UiMessage.Localized(R.string.profile_error_operation),
            generic.error,
        )
        assertFalse(generic.toString().contains("secret operation detail"))
    }

    @Test
    fun internalOperationTimeoutRestoresTheEditorAndAllowsRetry() = runTest {
        val runtime = FakeProfileRuntime(existingProfile = true)
        val controller = ConnectionProfileEditorController(
            scope = this,
            runtime = { runtime },
            reportError = {},
        )
        val key = SessionConnectionKey(PROVIDER_ID, PROFILE_ID).stableUiKey
        controller.edit(key)
        advanceUntilIdle()
        runtime.operationBlock = {
            withTimeout(1) { awaitCancellation() }
        }

        controller.requestOperation(VERIFY_OPERATION.value)
        advanceUntilIdle()

        val timedOut = assertInstance<ConnectionProfileEditorUiState.Editing>(controller.state.value)
        assertFalse(timedOut.isBusy)
        assertNull(timedOut.activeOperationId)
        assertEquals(
            "The connection profile operation timed out. Check the connection and retry.",
            timedOut.error,
        )

        runtime.operationBlock = null
        controller.requestOperation(VERIFY_OPERATION.value)
        advanceUntilIdle()

        val retried = assertInstance<ConnectionProfileEditorUiState.Editing>(controller.state.value)
        assertFalse(retried.isBusy)
        assertEquals("Profile operation completed.", retried.notice)
    }

    @Test
    fun actionableDeleteFailureExplainsJumpHostDependency() = runTest {
        val runtime = FakeProfileRuntime(existingProfile = true)
        val controller = ConnectionProfileEditorController(
            scope = this,
            runtime = { runtime },
            reportError = {},
        )
        runtime.deleteFailure = ConnectionProfileDeleteException(
            "Remove this profile as a jump host before deleting it.",
        )
        val key = SessionConnectionKey(PROVIDER_ID, PROFILE_ID).stableUiKey
        controller.edit(key)
        advanceUntilIdle()
        controller.requestDeletion()

        controller.delete()
        advanceUntilIdle()

        val failed = assertInstance<ConnectionProfileEditorUiState.Editing>(controller.state.value)
        assertEquals(
            UiMessage.Verbatim("Remove this profile as a jump host before deleting it."),
            failed.error,
        )
        assertFalse(failed.isBusy)
        assertTrue(runtime.deleted.isEmpty())
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
    var operationFailure: Throwable? = null
    var operationBlock: (suspend () -> Unit)? = null
    val performedOperations = mutableListOf<Triple<ConnectionProviderId, ConnectionProfileId, ConnectionProfileOperationId>>()

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

    override suspend fun performProfileOperation(
        providerId: ConnectionProviderId,
        profileId: ConnectionProfileId,
        operationId: ConnectionProfileOperationId,
    ): ConnectionProfileOperationResult {
        operationBlock?.invoke()
        operationFailure?.let { throw it }
        performedOperations += Triple(providerId, profileId, operationId)
        return ConnectionProfileOperationResult("Profile operation completed.")
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

    override suspend fun refreshArtifacts(
        locator: SessionLocator,
    ): List<dev.agentrelay.session.api.SessionArtifact> = emptyList()

    override suspend fun prepareArtifactDownload(
        locator: SessionLocator,
        artifactId: String,
    ): dev.agentrelay.session.runtime.PreparedArtifactDownload =
        error("Artifact downloads are not configured for profile editor tests")

    override suspend fun startSession(
        endpoint: AgentEndpointKey,
        options: StartSessionOptions,
    ): SessionLocator = error("Session creation is not configured for profile editor tests")

    override suspend fun respondToAction(
        locator: SessionLocator,
        requestId: String,
        decision: AgentApprovalDecision,
        answers: Map<String, List<String>>,
        additionalConfirmationGiven: Boolean,
    ) = Unit
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
    operations = if (profileId == null) {
        emptyList()
    } else {
        listOf(
            ConnectionProfileOperation(
                id = INSTALL_OPERATION,
                label = "Install public key",
                supportingText = "Install the app-managed public key.",
                confirmationTitle = "Install public key?",
                confirmationMessage = "Add this public key to the remote account.",
            ),
            ConnectionProfileOperation(
                id = VERIFY_OPERATION,
                label = "Test key-only login",
                supportingText = "Test passwordless key authentication.",
            ),
        )
    },
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
private val INSTALL_OPERATION = ConnectionProfileOperationId("install-public-key")
private val VERIFY_OPERATION = ConnectionProfileOperationId("verify-key-login")
