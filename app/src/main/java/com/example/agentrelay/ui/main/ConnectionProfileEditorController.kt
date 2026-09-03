package com.example.agentrelay.ui.main

import com.example.agentrelay.R
import com.example.agentrelay.data.SessionHubRuntime
import dev.agentrelay.connection.api.ConnectionProfileDeleteException
import dev.agentrelay.connection.api.ConnectionProfileFieldId
import dev.agentrelay.connection.api.ConnectionProfileFieldInput
import dev.agentrelay.connection.api.ConnectionProfileFieldType
import dev.agentrelay.connection.api.ConnectionProfileId
import dev.agentrelay.connection.api.ConnectionProfileOperationException
import dev.agentrelay.connection.api.ConnectionProfileOperationId
import dev.agentrelay.connection.api.ConnectionProfileUpdate
import dev.agentrelay.connection.api.ConnectionProfileValidationException
import dev.agentrelay.connection.api.ConnectionProviderId
import dev.agentrelay.session.runtime.SessionConnectionKey
import java.util.Arrays
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class ConnectionProfileEditorController(
    private val scope: CoroutineScope,
    private val runtime: () -> SessionHubRuntime?,
    private val reportError: (UiMessage) -> Unit,
) {
    private val mutableState = MutableStateFlow<ConnectionProfileEditorUiState?>(null)
    private var job: Job? = null
    private var generation = 0L

    val state: StateFlow<ConnectionProfileEditorUiState?> = mutableState.asStateFlow()

    fun add(providerId: String) {
        val active = runtime() ?: return
        val provider = active.connectionProviders.firstOrNull { it.id.value == providerId }
        if (provider == null) {
            reportError(UiMessage.Localized(R.string.profile_error_provider_unavailable))
            return
        }
        open(active, provider.id, null)
    }

    fun edit(connectionKey: String) {
        val active = runtime() ?: return
        val key = active.connectionKey(connectionKey)
        if (key == null) {
            reportError(UiMessage.Localized(R.string.profile_error_profile_unavailable))
            return
        }
        open(active, key.providerId, key.profileId)
    }

    fun updateField(fieldId: String, value: String) {
        val editor = mutableState.value as? ConnectionProfileEditorUiState.Editing ?: return
        if (!editor.isBusy) {
            mutableState.value = editor.updateField(fieldId, value)
        }
    }

    fun dismiss() {
        generation += 1
        job?.cancel()
        job = null
        mutableState.value = null
    }

    fun save() {
        val active = runtime() ?: return
        val editor = mutableState.value as? ConnectionProfileEditorUiState.Editing ?: return
        if (editor.isBusy || editor.confirmDelete || editor.confirmOperationId != null) return
        val busy = editor.copy(
            isBusy = true,
            fieldErrors = emptyMap(),
            error = null,
            notice = null,
        )
        mutableState.value = busy
        replaceJob { currentGeneration ->
            val update = editor.toProfileUpdate()
            val saved = try {
                update.use { active.saveProfile(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: ConnectionProfileValidationException) {
                replaceIfCurrent(
                    currentGeneration,
                    busy,
                    editor.copy(
                        fieldErrors = invalid.fieldErrors.mapKeys { it.key.value },
                        error = UiMessage.Localized(R.string.profile_error_validation),
                    ),
                )
                return@replaceJob
            } catch (_: Throwable) {
                replaceIfCurrent(
                    currentGeneration,
                    busy,
                    editor.copy(
                        error = UiMessage.Localized(R.string.profile_error_save),
                    ),
                )
                return@replaceJob
            }
            val reloaded = try {
                active.profileEditor(
                    providerId = saved.profile.providerId,
                    profileId = saved.profile.id,
                ).toUiState(saved.notice?.let(UiMessage::Verbatim))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (replaceIfCurrent(currentGeneration, busy, null)) {
                    reportError(
                        UiMessage.Localized(R.string.profile_error_save_refresh),
                    )
                }
                return@replaceJob
            }
            replaceIfCurrent(currentGeneration, busy, reloaded)
        }
    }

    fun requestOperation(operationId: String) {
        val editor = mutableState.value as? ConnectionProfileEditorUiState.Editing ?: return
        val operation = editor.operations.firstOrNull { it.id == operationId } ?: return
        if (
            editor.isBusy ||
            editor.hasUnsavedChanges ||
            editor.confirmDelete ||
            editor.confirmOperationId != null
        ) {
            return
        }
        if (operation.requiresConfirmation) {
            mutableState.value = editor.copy(confirmOperationId = operation.id)
        } else {
            performOperation(operation)
        }
    }

    fun cancelOperation() {
        val editor = mutableState.value as? ConnectionProfileEditorUiState.Editing ?: return
        if (!editor.isBusy) {
            mutableState.value = editor.copy(confirmOperationId = null)
        }
    }

    fun confirmOperation() {
        val editor = mutableState.value as? ConnectionProfileEditorUiState.Editing ?: return
        val operation = editor.confirmedOperation() ?: return
        if (!editor.isBusy && !editor.hasUnsavedChanges) {
            performOperation(operation)
        }
    }

    private fun performOperation(operation: ConnectionProfileOperationUiModel) {
        val active = runtime() ?: return
        val editor = mutableState.value as? ConnectionProfileEditorUiState.Editing ?: return
        val profileId = editor.profileId?.let(::ConnectionProfileId) ?: return
        if (editor.isBusy || editor.hasUnsavedChanges) return
        val busy = editor.copy(
            isBusy = true,
            activeOperationId = operation.id,
            confirmOperationId = null,
            fieldErrors = emptyMap(),
            error = null,
            notice = null,
        )
        mutableState.value = busy
        replaceJob { currentGeneration ->
            val result = try {
                active.performProfileOperation(
                    providerId = ConnectionProviderId(editor.providerId),
                    profileId = profileId,
                    operationId = ConnectionProfileOperationId(operation.id),
                )
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                replaceIfCurrent(
                    currentGeneration,
                    busy,
                    editor.copy(
                        confirmOperationId = null,
                        error = UiMessage.Localized(R.string.profile_error_operation_timeout),
                    ),
                )
                return@replaceJob
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ConnectionProfileOperationException) {
                replaceIfCurrent(
                    currentGeneration,
                    busy,
                    editor.copy(
                        confirmOperationId = null,
                        error = UiMessage.Verbatim(failure.actionableMessage),
                    ),
                )
                return@replaceJob
            } catch (_: Throwable) {
                replaceIfCurrent(
                    currentGeneration,
                    busy,
                    editor.copy(
                        confirmOperationId = null,
                        error = UiMessage.Localized(R.string.profile_error_operation),
                    ),
                )
                return@replaceJob
            }
            val reloaded = try {
                active.profileEditor(
                    providerId = ConnectionProviderId(editor.providerId),
                    profileId = profileId,
                ).toUiState(UiMessage.Verbatim(result.notice))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (replaceIfCurrent(currentGeneration, busy, null)) {
                    reportError(
                        UiMessage.Localized(R.string.profile_error_operation_refresh),
                    )
                }
                return@replaceJob
            }
            replaceIfCurrent(currentGeneration, busy, reloaded)
        }
    }

    fun requestDeletion() {
        val editor = mutableState.value as? ConnectionProfileEditorUiState.Editing ?: return
        if (
            !editor.isBusy &&
            editor.canDelete &&
            editor.confirmOperationId == null
        ) {
            mutableState.value = editor.copy(confirmDelete = true)
        }
    }

    fun cancelDeletion() {
        val editor = mutableState.value as? ConnectionProfileEditorUiState.Editing ?: return
        mutableState.value = editor.copy(confirmDelete = false)
    }

    fun delete() {
        val active = runtime() ?: return
        val editor = mutableState.value as? ConnectionProfileEditorUiState.Editing ?: return
        val profileId = editor.profileId?.let(::ConnectionProfileId) ?: return
        if (!editor.confirmDelete || editor.isBusy) return
        val busy = editor.copy(isBusy = true, confirmDelete = false, error = null)
        mutableState.value = busy
        replaceJob { currentGeneration ->
            try {
                active.deleteProfile(ConnectionProviderId(editor.providerId), profileId)
                replaceIfCurrent(currentGeneration, busy, null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ConnectionProfileDeleteException) {
                replaceIfCurrent(
                    currentGeneration,
                    busy,
                    editor.copy(
                        confirmDelete = false,
                        error = UiMessage.Verbatim(failure.actionableMessage),
                    ),
                )
            } catch (_: Throwable) {
                replaceIfCurrent(
                    currentGeneration,
                    busy,
                    editor.copy(
                        confirmDelete = false,
                        error = UiMessage.Localized(R.string.profile_error_delete),
                    ),
                )
            }
        }
    }

    private fun open(
        active: SessionHubRuntime,
        providerId: ConnectionProviderId,
        profileId: ConnectionProfileId?,
    ) {
        mutableState.value = ConnectionProfileEditorUiState.Loading
        replaceJob { currentGeneration ->
            try {
                val editor = active.profileEditor(providerId, profileId).toUiState()
                replaceIfCurrent(
                    currentGeneration,
                    ConnectionProfileEditorUiState.Loading,
                    editor,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (
                    replaceIfCurrent(
                        currentGeneration,
                        ConnectionProfileEditorUiState.Loading,
                        null,
                    )
                ) {
                    reportError(UiMessage.Localized(R.string.profile_error_open))
                }
            }
        }
    }

    private fun replaceJob(block: suspend (Long) -> Unit) {
        job?.cancel()
        val currentGeneration = ++generation
        job = scope.launch { block(currentGeneration) }
    }

    private fun replaceIfCurrent(
        currentGeneration: Long,
        expected: ConnectionProfileEditorUiState,
        replacement: ConnectionProfileEditorUiState?,
    ): Boolean =
        if (generation == currentGeneration && mutableState.value == expected) {
            mutableState.value = replacement
            true
        } else {
            false
        }

    private fun SessionHubRuntime.connectionKey(stableKey: String): SessionConnectionKey? =
        coordinatorSnapshot.value.profiles
            .asSequence()
            .map { SessionConnectionKey(it.providerId, it.id) }
            .firstOrNull { it.stableUiKey == stableKey }

    private fun ConnectionProfileEditorUiState.Editing.toProfileUpdate(): ConnectionProfileUpdate =
        ConnectionProfileUpdate(
            providerId = ConnectionProviderId(providerId),
            profileId = profileId?.let(::ConnectionProfileId),
            fields = fields
                .asSequence()
                .filter { it.type != ConnectionProfileFieldType.READ_ONLY }
                .associate { field ->
                    ConnectionProfileFieldId(field.id) to field.toInput()
                },
        )

    private fun ConnectionProfileFieldUiModel.toInput(): ConnectionProfileFieldInput =
        if (isSecret) {
            val characters = value.toCharArray()
            try {
                ConnectionProfileFieldInput.Secret.copyOf(characters)
            } finally {
                Arrays.fill(characters, '\u0000')
            }
        } else {
            ConnectionProfileFieldInput.Text(value)
        }
}
