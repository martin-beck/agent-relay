/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import com.example.agentrelay.R
import dev.agentrelay.connection.api.ConnectionProfileFieldType
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.session.api.SessionActionState

internal fun freshHub() = testHub().copy(
    connections = emptyList(),
    sessions = emptyList(),
    selectedSession = null,
    selectedSessionKey = null,
    sessionLaunchers = emptyList(),
    attentionActions = emptyList(),
)

internal fun firstUseIdentityHub(): SessionHubUiModel {
    val challenge = checkNotNull(testHub().connections.last().identityChallenge)
    val connection = testHub().connections.last().copy(
        label = "Workshop host",
        target = "workshop.example.test:22",
        authenticationLabel = "Password",
        identityChallenge = challenge.copy(
            endpoint = "workshop.example.test:22",
            fingerprint = "SHA256:fixture-first-use-fingerprint",
            isChangedIdentity = false,
            previousFingerprints = emptyList(),
        ),
    )
    return freshHub().copy(connections = listOf(connection))
}

internal fun changedIdentityHub(): SessionHubUiModel {
    val connection = testHub().connections.last().copy(label = "Workshop host")
    return freshHub().copy(connections = listOf(connection))
}

internal fun onlineHub(): SessionHubUiModel {
    val connection = testHub().connections.last().copy(
        label = "Workshop host",
        target = "workshop.example.test:22",
        authenticationLabel = "Android Keystore key",
        status = ConnectionStatus.ONLINE,
        statusDetail = null,
        connectedAgentCount = 1,
        agentCount = 1,
        canConnect = false,
        canDisconnect = true,
        identityChallenge = null,
    )
    val launcher = actionHub().sessionLaunchers.single().copy(connectionLabel = "Workshop host")
    return freshHub().copy(
        connections = listOf(connection),
        sessionLaunchers = listOf(launcher),
    )
}

internal fun firstReadySessionHub(): SessionHubUiModel {
    val hub = testHub()
    val detail = checkNotNull(hub.selectedSession)
    val session = detail.session.copy(
        title = UiMessage.Verbatim("First workspace review"),
        preview = "Ready for your first instruction.",
        connectionLabel = "Workshop host",
        agentState = AgentSessionState.IDLE,
        unreadCount = 0,
        requiresActionCount = 0,
    )
    return hub.copy(
        connections = onlineHub().connections,
        sessions = listOf(session),
        selectedSession = detail.copy(
            session = session,
            activities = emptyList(),
            actions = emptyList(),
            composer = SessionComposerUiModel(
                canSubmit = true,
                statusMessage = null,
            ),
        ),
        attentionActions = emptyList(),
    )
}

internal fun deliveringActionHub(): SessionHubUiModel {
    val hub = actionHub()
    val action = hub.attentionActions.single().copy(
        state = SessionActionState.DELIVERING,
        completedDecision = AgentApprovalDecision.SUBMIT,
        additionalConfirmationGiven = true,
        isBusy = true,
    )
    return hub.copy(
        attentionActions = listOf(action),
        selectedSession = checkNotNull(hub.selectedSession).copy(actions = listOf(action)),
    )
}

internal fun reconnectingRecoveryHub(): SessionHubUiModel {
    val hub = actionHub()
    val connection = hub.connections.first().copy(
        label = "Workshop host",
        target = "workshop.example.test:22",
        status = ConnectionStatus.RECONNECTING,
        statusDetail = UiMessage.Verbatim("Network unavailable. Retrying 2 of 4 in 8 seconds."),
        connectedAgentCount = 0,
        agentCount = 1,
        unavailableAgentCount = 0,
        canConnect = false,
        canDisconnect = true,
        isBusy = true,
    )
    return hub.copy(connections = listOf(connection))
}

internal fun voiceModelRequiredState() = SpeechInputUiState(
    phase = SpeechInputPhase.MODEL_REQUIRED,
    models = listOf(
        SpeechModelOptionUiModel("compact", "English compact", false),
        SpeechModelOptionUiModel("accurate", "English accurate", true),
    ),
    selectedModelId = "compact",
    selectedModelName = "English compact",
    statusMessage = UiMessage.Localized(R.string.speech_status_install_model),
)

internal fun voicePermissionReadyState() = SpeechInputUiState(
    phase = SpeechInputPhase.READY,
    models = listOf(SpeechModelOptionUiModel("compact", "English compact", true)),
    selectedModelId = "compact",
    selectedModelName = "English compact",
    statusMessage = UiMessage.Localized(R.string.speech_status_ready_private),
)

internal fun voiceListeningState() = SpeechInputUiState(
    phase = SpeechInputPhase.LISTENING,
    selectedModelId = "compact",
    selectedModelName = "English compact",
    targetSessionKey = "session-key",
    operationId = 41,
    statusMessage = UiMessage.Localized(R.string.speech_status_listening),
)

internal fun voiceTranscriptReviewState() = SpeechInputUiState(
    phase = SpeechInputPhase.RESULT,
    selectedModelId = "compact",
    selectedModelName = "English compact",
    targetSessionKey = "session-key",
    operationId = 41,
    transcript = "Run the focused checks, then summarize any failures.",
    statusMessage = UiMessage.Localized(R.string.speech_status_review_transcript),
)

internal fun twoSessionHub(): SessionHubUiModel {
    val hub = testHub()
    val connection = hub.connections.first().copy(
        status = ConnectionStatus.ONLINE,
        connectedAgentCount = 2,
        agentCount = 2,
        canConnect = false,
        canDisconnect = true,
    )
    val first = hub.sessions.single()
    val second = first.copy(
        stableKey = "release-session-key",
        title = UiMessage.Verbatim("Prepare release notes"),
        preview = "Release notes are ready for a final check.",
        agentProviderLabel = "Claude Code",
        agentState = AgentSessionState.IDLE,
        unreadCount = 0,
        requiresActionCount = 0,
        isPinned = false,
    )
    return hub.copy(
        connections = listOf(connection),
        sessions = listOf(first, second),
    )
}

internal fun exportCompleteHub(): SessionHubUiModel {
    val hub = testHub()
    val detail = checkNotNull(hub.selectedSession)
    val artifact = detail.artifacts.single().copy(
        availabilityStatus = SessionArtifactAvailabilityStatus.READY,
        canSave = true,
        bytesWritten = 512,
        totalBytes = 512,
        isExporting = false,
        isExportComplete = true,
    )
    return hub.copy(selectedSession = detail.copy(artifacts = listOf(artifact)))
}

internal fun newSshEditor() = ConnectionProfileEditorUiState.Editing(
    providerId = "ssh.secure-shell",
    profileId = null,
    title = "Add Secure Shell profile",
    fields = listOf(
        textField("profile-label", "Profile name", "Workshop host", 128),
        textField("host", "Host", "workshop.example.test", 253),
        textField("port", "Port", "22", 5, ConnectionProfileFieldType.PORT),
        textField("username", "Username", "agent-user", 128),
        choiceField(
            "jump-host",
            "Jump host",
            "direct",
            listOf(ConnectionProfileFieldOptionUiModel("direct", "Direct connection", null)),
        ),
        choiceField(
            "authentication",
            "Authentication",
            "password",
            listOf(
                ConnectionProfileFieldOptionUiModel(
                    "password",
                    "Password",
                    "Encrypted in Android Keystore-backed app storage.",
                ),
                ConnectionProfileFieldOptionUiModel(
                    "imported-key",
                    "Imported private key",
                    "Paste an OpenSSH or PEM private key.",
                ),
            ),
        ),
        ConnectionProfileFieldUiModel(
            id = "password",
            label = "Password",
            type = ConnectionProfileFieldType.PASSWORD,
            value = "fixture-only",
            supportingText = "Synthetic documentation fixture.",
            required = true,
            maxLength = 16_384,
            options = emptyList(),
            visibleWhen = listOf(
                ConnectionProfileFieldConditionUiModel("authentication", "password"),
            ),
            hasStoredSecret = false,
        ),
    ),
    canDelete = false,
)

private fun textField(
    id: String,
    label: String,
    value: String,
    maxLength: Int,
    type: ConnectionProfileFieldType = ConnectionProfileFieldType.TEXT,
) = ConnectionProfileFieldUiModel(
    id = id,
    label = label,
    type = type,
    value = value,
    supportingText = null,
    required = true,
    maxLength = maxLength,
    options = emptyList(),
    visibleWhen = emptyList(),
    hasStoredSecret = false,
)

private fun choiceField(
    id: String,
    label: String,
    value: String,
    options: List<ConnectionProfileFieldOptionUiModel>,
) = ConnectionProfileFieldUiModel(
    id = id,
    label = label,
    type = ConnectionProfileFieldType.SINGLE_CHOICE,
    value = value,
    supportingText = null,
    required = true,
    maxLength = 256,
    options = options,
    visibleWhen = emptyList(),
    hasStoredSecret = false,
)
