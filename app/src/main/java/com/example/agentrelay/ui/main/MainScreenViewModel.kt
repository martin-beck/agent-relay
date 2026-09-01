package com.example.agentrelay.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.agentrelay.data.SessionHubRuntime
import dev.agentrelay.connection.api.ConnectionIdentityDecision
import dev.agentrelay.connection.api.ConnectionState
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.provider.api.StartSessionOptions
import dev.agentrelay.session.api.SessionDraft
import dev.agentrelay.session.api.SessionLocator
import dev.agentrelay.session.runtime.SessionActionAuditFailureException
import dev.agentrelay.session.runtime.SessionActionDeliveryUncertainException
import dev.agentrelay.session.runtime.SessionConnectionKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun interface SessionHubRuntimeFactory {
    suspend fun create(): SessionHubRuntime
}

internal class MainScreenViewModel(
    private val clock: () -> Long = System::currentTimeMillis,
    private val runtimeFactory: SessionHubRuntimeFactory,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow<MainScreenUiState>(MainScreenUiState.Loading)
    private val selectedSessionKey = MutableStateFlow<String?>(null)
    private val operationError = MutableStateFlow<String?>(null)
    private val busyConnectionKeys = MutableStateFlow<Set<String>>(emptySet())
    private val sessionInteractions = MutableStateFlow(SessionInteractionState())
    private val sessionCreator = MutableStateFlow<SessionCreatorUiState?>(null)
    private val draftSaveJobs = mutableMapOf<String, Job>()
    private var runtime: SessionHubRuntime? = null
    private var initializationJob: Job? = null
    private val profileEditor = ConnectionProfileEditorController(
        scope = viewModelScope,
        runtime = { runtime },
        reportError = { operationError.value = it },
    )

    internal val artifactInteractions = ArtifactInteractionController(
        scope = viewModelScope,
        runtime = { runtime },
        reportError = { operationError.value = it },
    )
    val uiState: StateFlow<MainScreenUiState> = mutableUiState.asStateFlow()

    init {
        initialize()
    }

    fun retryInitialization() {
        if (runtime == null) {
            initialize()
        } else {
            refreshProfiles()
        }
    }

    fun refreshProfiles() {
        perform(
            failureMessage = "Connection profiles could not be refreshed.",
        ) {
            it.refreshProfiles()
        }
    }
    fun addProfile(providerId: String) = profileEditor.add(providerId)

    fun editProfile(connectionKey: String) = profileEditor.edit(connectionKey)

    fun updateProfileField(fieldId: String, value: String) =
        profileEditor.updateField(fieldId, value)

    fun dismissProfileEditor() = profileEditor.dismiss()
    fun saveProfile() = profileEditor.save()
    fun requestProfileDeletion() = profileEditor.requestDeletion()
    fun cancelProfileDeletion() = profileEditor.cancelDeletion()
    fun deleteProfile() = profileEditor.delete()

    fun connect(connectionKey: String) {
        performConnection(
            connectionKey = connectionKey,
            failureMessage = "The connection could not be opened.",
        ) { active, key ->
            active.connect(key)
        }
    }

    fun disconnect(connectionKey: String) {
        performConnection(
            connectionKey = connectionKey,
            failureMessage = "The connection could not be closed cleanly.",
        ) { active, key ->
            active.disconnect(key)
        }
    }

    fun trustIdentity(
        connectionKey: String,
        replaceChangedIdentity: Boolean,
    ) {
        performConnection(
            connectionKey = connectionKey,
            failureMessage = "The server identity decision could not be applied.",
        ) { active, key ->
            val challenge = (
                active.coordinatorSnapshot.value.connectionStates[key] as?
                    ConnectionState.AwaitingIdentityTrust
                )?.challenge ?: error("The identity challenge is no longer active")
            val decision = if (replaceChangedIdentity) {
                ConnectionIdentityDecision.REPLACE_CHANGED
            } else {
                ConnectionIdentityDecision.TRUST_FIRST_USE
            }
            check(active.resolveIdentityChallenge(key, challenge.id, decision)) {
                "The identity challenge changed before it was resolved"
            }
        }
    }

    fun rejectIdentity(connectionKey: String) {
        performConnection(
            connectionKey = connectionKey,
            failureMessage = "The server identity decision could not be applied.",
        ) { active, key ->
            val challenge = (
                active.coordinatorSnapshot.value.connectionStates[key] as?
                    ConnectionState.AwaitingIdentityTrust
                )?.challenge ?: error("The identity challenge is no longer active")
            check(
                active.resolveIdentityChallenge(
                    key = key,
                    challengeId = challenge.id,
                    decision = ConnectionIdentityDecision.REJECT,
                ),
            ) {
                "The identity challenge changed before it was resolved"
            }
        }
    }

    fun selectSession(sessionKey: String) {
        val active = runtime
        val locator = active?.sessionSnapshot?.value
            ?.sessions
            ?.firstOrNull { it.locator.stableUiKey == sessionKey }
            ?.locator
        if (locator == null) {
            operationError.value = "That session is no longer available."
            return
        }
        selectedSessionKey.value = sessionKey
        viewModelScope.launch {
            try {
                active.markSessionRead(locator)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                operationError.value = "The session read state could not be saved."
            }
        }
    }

    fun clearSelection() {
        selectedSessionKey.value = null
    }

    fun clearOperationError() {
        operationError.value = null
    }

    fun updateSessionDraft(
        sessionKey: String,
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
    ) {
        val active = runtime ?: return
        val locator = active.findSessionLocator(sessionKey)
        if (locator == null) {
            operationError.value = "That session is no longer available."
            return
        }
        if (text.length > MAX_SESSION_DRAFT_CHARS) {
            operationError.value = "Session drafts are limited to $MAX_SESSION_DRAFT_CHARS characters."
            return
        }
        val boundedStart = selectionStart.coerceIn(0, text.length)
        val draft = SessionDraft(
            text = text,
            selectionStart = boundedStart,
            selectionEnd = selectionEnd.coerceIn(boundedStart, text.length),
            updatedAtEpochMillis = clock().coerceAtLeast(0L),
        )
        operationError.value = null
        sessionInteractions.update { current ->
            current.copy(draftOverrides = current.draftOverrides + (sessionKey to draft))
        }
        draftSaveJobs.remove(sessionKey)?.cancel()
        val saveJob = viewModelScope.launch {
            delay(DRAFT_SAVE_DELAY_MILLIS)
            try {
                active.updateDraft(locator, draft)
                sessionInteractions.update { current ->
                    if (current.draftOverrides[sessionKey] == draft) {
                        current.copy(draftOverrides = current.draftOverrides - sessionKey)
                    } else {
                        current
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                operationError.value = "The session draft could not be saved securely."
            }
        }
        draftSaveJobs[sessionKey] = saveJob
        saveJob.invokeOnCompletion {
            if (draftSaveJobs[sessionKey] === saveJob) {
                draftSaveJobs.remove(sessionKey)
            }
        }
    }

    fun submitSessionDraft(sessionKey: String) {
        val active = runtime ?: return
        val record = active.sessionSnapshot.value.sessions
            .firstOrNull { it.locator.stableUiKey == sessionKey }
        if (record == null) {
            operationError.value = "That session is no longer available."
            return
        }
        val draft = sessionInteractions.value.draftOverrides[sessionKey]
            ?: active.sessionSnapshot.value.drafts[record.locator]
        if (draft == null || draft.text.isBlank()) {
            operationError.value = "Enter a message before sending."
            return
        }
        val state = record.observation.agentState
        draftSaveJobs.remove(sessionKey)?.cancel()
        performSession(
            sessionKey = sessionKey,
            failureMessage = if (state == AgentSessionState.RUNNING) {
                "The active turn could not be steered."
            } else {
                "The session input could not be sent."
            },
        ) { opened, locator ->
            opened.updateDraft(locator, draft)
            when (state) {
                AgentSessionState.RUNNING -> opened.steerActiveTurn(locator, draft.text)
                AgentSessionState.IDLE -> opened.sendInput(locator, draft.text)
                else -> error("The session is not ready to accept input")
            }
            val currentDraft = sessionInteractions.value.draftOverrides[sessionKey]
                ?: opened.sessionSnapshot.value.drafts[locator]
            if (currentDraft == draft) {
                val emptyDraft = SessionDraft("", 0, 0, clock().coerceAtLeast(0L))
                try {
                    opened.updateDraft(locator, emptyDraft)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    operationError.value =
                        "The message was delivered, but its saved draft could not be cleared securely."
                    return@performSession
                }
                sessionInteractions.update { current ->
                    current.copy(draftOverrides = current.draftOverrides - sessionKey)
                }
            }
        }
    }

    fun resumeSession(sessionKey: String) = performSession(
        sessionKey = sessionKey,
        failureMessage = "The saved session could not be resumed.",
    ) { active, locator -> active.resumeSession(locator) }

    fun interruptSession(sessionKey: String) = performSession(
        sessionKey = sessionKey,
        failureMessage = "The active turn could not be interrupted.",
    ) { active, locator -> active.interrupt(locator) }

    fun openSessionCreator(launcherKey: String) {
        val launcher = (uiState.value as? MainScreenUiState.Ready)
            ?.hub
            ?.sessionLaunchers
            ?.firstOrNull { it.stableKey == launcherKey }
        if (launcher == null) {
            operationError.value = "That agent endpoint is no longer ready."
            return
        }
        operationError.value = null
        sessionCreator.value = SessionCreatorUiState(
            launcherKey = launcher.stableKey,
            connectionLabel = launcher.connectionLabel,
            connectionProviderName = launcher.connectionProviderName,
            agentProviderLabel = launcher.agentProviderLabel,
            workingDirectory = launcher.suggestedWorkingDirectory.orEmpty(),
        )
    }

    fun updateSessionCreatorWorkingDirectory(value: String) {
        if (value.length > MAX_WORKING_DIRECTORY_CHARS) {
            operationError.value = "Working directories are limited to $MAX_WORKING_DIRECTORY_CHARS characters."
            return
        }
        sessionCreator.update { current ->
            current?.takeUnless(SessionCreatorUiState::isBusy)?.copy(workingDirectory = value) ?: current
        }
    }

    fun updateSessionCreatorModel(value: String) {
        if (value.length > MAX_MODEL_CHARS) {
            operationError.value = "Model names are limited to $MAX_MODEL_CHARS characters."
            return
        }
        sessionCreator.update { current ->
            current?.takeUnless(SessionCreatorUiState::isBusy)?.copy(model = value) ?: current
        }
    }

    fun dismissSessionCreator() {
        if (sessionCreator.value?.isBusy != true) {
            sessionCreator.value = null
        }
    }

    fun startSession() {
        val active = runtime ?: return
        val creator = sessionCreator.value ?: return
        if (creator.isBusy) {
            return
        }
        val endpoint = active.coordinatorSnapshot.value.agentEndpoints.keys
            .firstOrNull { it.stableUiKey == creator.launcherKey }
        if (endpoint == null) {
            sessionCreator.value = null
            operationError.value = "That agent endpoint is no longer ready."
            return
        }
        operationError.value = null
        sessionCreator.value = creator.copy(isBusy = true)
        viewModelScope.launch {
            try {
                val locator = active.startSession(
                    endpoint = endpoint,
                    options = StartSessionOptions(
                        workingDirectory = creator.workingDirectory.trim().takeIf(String::isNotEmpty),
                        model = creator.model.trim().takeIf(String::isNotEmpty),
                    ),
                )
                selectedSessionKey.value = locator.stableUiKey
                sessionCreator.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                sessionCreator.update { it?.copy(isBusy = false) }
                operationError.value = "The new agent session could not be started."
            }
        }
    }

    fun respondToAction(
        sessionKey: String,
        actionKey: String,
        decision: AgentApprovalDecision,
        answers: Map<String, List<String>>,
        additionalConfirmationGiven: Boolean,
    ) {
        val active = runtime ?: return
        val request = active.sessionSnapshot.value.actionRequests.firstOrNull {
            it.id == actionKey && it.locator.stableUiKey == sessionKey
        }
        if (request == null) {
            operationError.value = "That approval or question is no longer available."
            return
        }
        if (actionKey in sessionInteractions.value.busyActionKeys) {
            return
        }
        operationError.value = null
        sessionInteractions.update { current ->
            current.copy(busyActionKeys = current.busyActionKeys + actionKey)
        }
        viewModelScope.launch {
            try {
                active.respondToAction(
                    locator = request.locator,
                    requestId = request.id,
                    decision = decision,
                    answers = answers,
                    additionalConfirmationGiven = additionalConfirmationGiven,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: SessionActionDeliveryUncertainException) {
                operationError.value = failure.message
            } catch (failure: SessionActionAuditFailureException) {
                operationError.value = failure.message
            } catch (_: Throwable) {
                operationError.value = "The approval or question response could not be applied."
            } finally {
                sessionInteractions.update { current ->
                    current.copy(busyActionKeys = current.busyActionKeys - actionKey)
                }
            }
        }
    }

    private fun initialize() {
        initializationJob?.cancel()
        mutableUiState.value = MainScreenUiState.Loading
        initializationJob = viewModelScope.launch {
            try {
                val opened = runtimeFactory.create()
                runtime = opened
                val uiOperations = combine(
                    busyConnectionKeys,
                    sessionInteractions,
                    artifactInteractions.state,
                ) { busyConnections, interactions, artifacts ->
                    UiOperations(busyConnections, interactions, artifacts)
                }
                launch {
                    combine(
                        opened.coordinatorSnapshot,
                        opened.sessionSnapshot,
                        selectedSessionKey,
                        operationError,
                        uiOperations,
                    ) { coordinator, sessions, selected, error, operations ->
                        SessionHubUiMapper.map(
                            coordinator = coordinator,
                            sessions = sessions,
                            connectionProviders = opened.connectionProviders,
                            selectedSessionKey = selected,
                            operationError = error,
                            busyConnectionKeys = operations.busyConnectionKeys,
                            busySessionKeys = operations.interactions.busySessionKeys,
                            busyActionKeys = operations.interactions.busyActionKeys,
                            draftOverrides = operations.interactions.draftOverrides,
                            artifactTransferStates = operations.artifacts.transfers,
                            refreshingArtifactSessionKeys = operations.artifacts.refreshingSessionKeys,
                        )
                    }.combine(profileEditor.state) { hub, editor ->
                        hub to editor
                    }.combine(sessionCreator) { (hub, editor), creator ->
                        MainScreenUiState.Ready(
                            hub = hub,
                            profileEditor = editor,
                            sessionCreator = creator,
                        )
                    }.collect { mutableUiState.value = it }
                }
                try {
                    opened.refreshProfiles()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    operationError.value = "Connection profiles could not be refreshed."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                runtime = null
                mutableUiState.value = MainScreenUiState.FatalError(
                    "Secure session state could not be opened. Retry after unlocking the device.",
                )
            }
        }
    }

    private fun perform(
        failureMessage: String,
        operation: suspend (SessionHubRuntime) -> Unit,
    ) {
        val active = runtime ?: return
        operationError.value = null
        viewModelScope.launch {
            try {
                operation(active)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                operationError.value = failureMessage
            }
        }
    }

    private fun performConnection(
        connectionKey: String,
        failureMessage: String,
        operation: suspend (SessionHubRuntime, SessionConnectionKey) -> Unit,
    ) {
        val active = runtime ?: return
        val key = active.coordinatorSnapshot.value.profiles
            .asSequence()
            .map { SessionConnectionKey(it.providerId, it.id) }
            .firstOrNull { it.stableUiKey == connectionKey }
        if (key == null) {
            operationError.value = "That connection profile is no longer available."
            return
        }
        operationError.value = null
        busyConnectionKeys.value += connectionKey
        viewModelScope.launch {
            try {
                operation(active, key)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                operationError.value = failureMessage
            } finally {
                busyConnectionKeys.value -= connectionKey
            }
        }
    }

    private fun performSession(
        sessionKey: String,
        failureMessage: String,
        operation: suspend (SessionHubRuntime, SessionLocator) -> Unit,
    ) {
        val active = runtime ?: return
        val locator = active.findSessionLocator(sessionKey)
        if (locator == null) {
            operationError.value = "That session is no longer available."
            return
        }
        if (sessionKey in sessionInteractions.value.busySessionKeys) {
            return
        }
        operationError.value = null
        sessionInteractions.update { current ->
            current.copy(busySessionKeys = current.busySessionKeys + sessionKey)
        }
        viewModelScope.launch {
            try {
                operation(active, locator)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                operationError.value = failureMessage
            } finally {
                sessionInteractions.update { current ->
                    current.copy(busySessionKeys = current.busySessionKeys - sessionKey)
                }
            }
        }
    }

    private companion object {
        const val DRAFT_SAVE_DELAY_MILLIS = 300L
        const val MAX_WORKING_DIRECTORY_CHARS = 4_096
        const val MAX_MODEL_CHARS = 256
    }
}

private fun SessionHubRuntime.findSessionLocator(sessionKey: String): SessionLocator? =
    sessionSnapshot.value.sessions
        .firstOrNull { it.locator.stableUiKey == sessionKey }
        ?.locator

private data class SessionInteractionState(
    val busySessionKeys: Set<String> = emptySet(),
    val busyActionKeys: Set<String> = emptySet(),
    val draftOverrides: Map<String, SessionDraft> = emptyMap(),
)

private data class UiOperations(
    val busyConnectionKeys: Set<String>,
    val interactions: SessionInteractionState,
    val artifacts: ArtifactInteractionState,
)

internal sealed interface MainScreenUiState {
    data object Loading : MainScreenUiState

    data class FatalError(val message: String) : MainScreenUiState

    data class Ready(
        val hub: SessionHubUiModel,
        val profileEditor: ConnectionProfileEditorUiState? = null,
        val sessionCreator: SessionCreatorUiState? = null,
    ) : MainScreenUiState
}

internal data class SessionCreatorUiState(
    val launcherKey: String,
    val connectionLabel: String,
    val connectionProviderName: String,
    val agentProviderLabel: String,
    val workingDirectory: String = "",
    val model: String = "",
    val isBusy: Boolean = false,
)
