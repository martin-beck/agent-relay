package com.example.agentrelay.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.agentrelay.data.SessionHubRuntime
import dev.agentrelay.connection.api.ConnectionIdentityDecision
import dev.agentrelay.connection.api.ConnectionState
import dev.agentrelay.session.api.SessionLocator
import dev.agentrelay.session.runtime.SessionConnectionKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal fun interface SessionHubRuntimeFactory {
    suspend fun create(): SessionHubRuntime
}

internal class MainScreenViewModel(
    private val runtimeFactory: SessionHubRuntimeFactory,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow<MainScreenUiState>(MainScreenUiState.Loading)
    private val selectedSessionKey = MutableStateFlow<String?>(null)
    private val operationError = MutableStateFlow<String?>(null)
    private val busyConnectionKeys = MutableStateFlow<Set<String>>(emptySet())
    private var runtime: SessionHubRuntime? = null
    private var initializationJob: Job? = null

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
        markRead(active, locator)
    }

    fun clearSelection() {
        selectedSessionKey.value = null
    }

    fun clearOperationError() {
        operationError.value = null
    }

    private fun initialize() {
        initializationJob?.cancel()
        mutableUiState.value = MainScreenUiState.Loading
        initializationJob = viewModelScope.launch {
            try {
                val opened = runtimeFactory.create()
                runtime = opened
                launch {
                    combine(
                        opened.coordinatorSnapshot,
                        opened.sessionSnapshot,
                        selectedSessionKey,
                        operationError,
                        busyConnectionKeys,
                    ) { coordinator, sessions, selected, error, busy ->
                        MainScreenUiState.Ready(
                            SessionHubUiMapper.map(
                                coordinator = coordinator,
                                sessions = sessions,
                                connectionProviders = opened.connectionProviders,
                                selectedSessionKey = selected,
                                operationError = error,
                                busyConnectionKeys = busy,
                            ),
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

    private fun markRead(
        active: SessionHubRuntime,
        locator: SessionLocator,
    ) {
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
}

internal sealed interface MainScreenUiState {
    data object Loading : MainScreenUiState

    data class FatalError(val message: String) : MainScreenUiState

    data class Ready(val hub: SessionHubUiModel) : MainScreenUiState
}
