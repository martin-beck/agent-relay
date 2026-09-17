/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.agentrelay.R
import com.example.agentrelay.theme.AgentRelayTheme
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentApprovalType
import dev.agentrelay.provider.api.AgentFileChangeKind
import dev.agentrelay.provider.api.AgentSessionState
import dev.agentrelay.session.api.SessionActionRisk
import dev.agentrelay.session.api.SessionActionState
import dev.agentrelay.session.api.SessionActivityType

@Preview(
    name = "Loading - compact",
    group = "Main screen states",
    showBackground = true,
    widthDp = 360,
    heightDp = 780,
)
@Composable
private fun MainScreenLoadingPreview() {
    PreviewMainScreen(MainScreenUiState.Loading)
}

@Preview(
    name = "Fatal error - dark and large text",
    group = "Main screen states",
    showBackground = true,
    widthDp = 360,
    heightDp = 780,
    fontScale = 1.5f,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun MainScreenFatalErrorPreview() {
    PreviewMainScreen(
        state = MainScreenUiState.FatalError(
            UiMessage.Verbatim(
                "The encrypted session store could not be opened. Retry after the device is unlocked.",
            ),
        ),
        darkTheme = true,
    )
}

@Preview(
    name = "Empty - compact",
    group = "Main screen states",
    showBackground = true,
    widthDp = 360,
    heightDp = 780,
)
@Composable
private fun MainScreenEmptyPreview() {
    PreviewMainScreen(MainScreenUiState.Ready(previewEmptyHub()))
}

@Preview(
    name = "Content - compact long German",
    group = "Adaptive session hub",
    showBackground = true,
    widthDp = 360,
    heightDp = 800,
    fontScale = 1.3f,
    locale = "de-rDE",
)
@Composable
private fun MainScreenCompactLongContentPreview() {
    PreviewMainScreen(MainScreenUiState.Ready(previewHub(longContent = true)))
}

@Preview(
    name = "Content - medium",
    group = "Adaptive session hub",
    showBackground = true,
    widthDp = 700,
    heightDp = 900,
)
@Composable
private fun MainScreenMediumPreview() {
    PreviewMainScreen(MainScreenUiState.Ready(previewHub()))
}

@Preview(
    name = "Content - expanded",
    group = "Adaptive session hub",
    showBackground = true,
    widthDp = 1_000,
    heightDp = 720,
)
@Composable
private fun MainScreenExpandedPreview() {
    PreviewMainScreen(MainScreenUiState.Ready(previewHub()))
}

@Preview(
    name = "Approval - expanded dark",
    group = "Adaptive session hub",
    showBackground = true,
    widthDp = 1_000,
    heightDp = 820,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun MainScreenApprovalPreview() {
    PreviewMainScreen(
        state = MainScreenUiState.Ready(previewHub(approvalRequired = true)),
        darkTheme = true,
    )
}

@Composable
private fun PreviewMainScreen(
    state: MainScreenUiState,
    darkTheme: Boolean = false,
) {
    AgentRelayTheme(
        darkTheme = darkTheme,
        dynamicColor = false,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            MainScreenContent(
                state = state,
                actions = previewActions(),
            )
        }
    }
}

internal fun previewActions() = SessionHubActions(
    retry = {},
    refresh = {},
    connect = {},
    disconnect = {},
    trustIdentity = { _, _ -> },
    rejectIdentity = {},
    selectSession = {},
    openSession = {},
    dismissError = {},
)

internal fun previewEmptyHub() = SessionHubUiModel(
    availableConnectionProviders = listOf("Local", "Secure Shell"),
    connections = emptyList(),
    sessions = emptyList(),
    issues = emptyList(),
    selectedSession = null,
    selectedSessionKey = null,
    operationError = null,
    isRefreshingProfiles = false,
    manageableConnectionProviders = listOf(
        ConnectionProviderUiModel("ssh.secure-shell", "Secure Shell"),
    ),
)

internal fun previewHub(
    approvalRequired: Boolean = false,
    longContent: Boolean = false,
): SessionHubUiModel {
    val session = previewSession(
        approvalRequired = approvalRequired,
        longContent = longContent,
    )
    val action = previewApproval(session)
    return SessionHubUiModel(
        availableConnectionProviders = listOf("Local", "Secure Shell"),
        connections = previewConnections(),
        sessions = listOf(session),
        issues = listOf(
            CoordinatorIssueUiModel(
                id = "preview-issue",
                message =
                UiMessage.Verbatim("One provider needs attention before it can reconnect."),
                recoverable = true,
            ),
        ),
        selectedSession = previewDetail(
            session = session,
            action = action,
            approvalRequired = approvalRequired,
        ),
        selectedSessionKey = session.stableKey,
        operationError = null,
        isRefreshingProfiles = false,
        manageableConnectionProviders = listOf(
            ConnectionProviderUiModel("ssh.secure-shell", "Secure Shell"),
        ),
        sessionLaunchers = listOf(
            SessionLauncherUiModel(
                stableKey = "preview-launcher",
                connectionLabel = "Development workspace",
                connectionProviderName = "Secure Shell",
                agentProviderLabel = "Codex",
                suggestedWorkingDirectory = "/workspace/agent-relay",
            ),
        ),
        attentionActions = if (approvalRequired) listOf(action) else emptyList(),
    )
}

internal fun previewSessionRowsHub(): SessionHubUiModel {
    val hub = previewHub()
    val available = checkNotNull(hub.sessions.single())
    val unavailable = available.copy(
        stableKey = "preview-session-unavailable",
        title = UiMessage.Verbatim("Unavailable preview"),
        preview = "",
        lastActivityAtEpochMillis = null,
    )
    val stale = available.copy(
        stableKey = "preview-session-stale",
        title = UiMessage.Verbatim("Stale preview"),
        preview = "Older result retained for context.",
        lastActivityAtEpochMillis = 0L,
    )
    return hub.copy(
        sessions = listOf(available, unavailable, stale),
        selectedSession = null,
        selectedSessionKey = null,
    )
}

private fun previewSession(
    approvalRequired: Boolean,
    longContent: Boolean,
) = SessionUiModel(
    stableKey = "preview-session",
    title = UiMessage.Verbatim(
        if (longContent) {
            "Android-Sitzungsübersicht auf kleinen Bildschirmen barrierefrei prüfen"
        } else {
            "Refine the Android session hub"
        },
    ),
    preview = if (longContent) {
        "Die providerneutrale Laufzeit wartet auf eine sichere und eindeutig erklärte Entscheidung."
    } else {
        "The provider-neutral runtime is ready for the next instruction."
    },
    connectionLabel = if (longContent) {
        "Entwicklungsumgebung mit ausführlicher Bezeichnung"
    } else {
        "Development workspace"
    },
    connectionProviderName = "Secure Shell",
    agentProviderLabel = "Codex",
    projectPath = "/workspace/agent-relay",
    agentState = if (approvalRequired) {
        AgentSessionState.WAITING_FOR_APPROVAL
    } else {
        AgentSessionState.IDLE
    },
    unreadCount = 2,
    requiresActionCount = if (approvalRequired) 1 else 0,
    lastActivityAtEpochMillis = 1_788_200_000_000,
    isPinned = true,
)

private fun previewConnections() = listOf(
    ConnectionUiModel(
        stableKey = "preview-local",
        providerName = "Local",
        label = "This device",
        target = "App-private workspace",
        authenticationLabel = null,
        status = ConnectionStatus.OFFLINE,
        statusDetail = UiMessage.Verbatim("Start the local service to discover agents."),
        connectedAgentCount = 0,
        agentCount = 0,
        unavailableAgentCount = 0,
        canConnect = true,
        canDisconnect = false,
        isBusy = false,
        identityChallenge = null,
    ),
    ConnectionUiModel(
        stableKey = "preview-ssh",
        providerName = "Secure Shell",
        label = "Development workspace",
        target = "Configured endpoint",
        authenticationLabel = "Managed app key",
        status = ConnectionStatus.IDENTITY_REVIEW,
        statusDetail = UiMessage.Verbatim("The saved host identity has changed."),
        connectedAgentCount = 0,
        agentCount = 1,
        unavailableAgentCount = 1,
        canConnect = false,
        canDisconnect = true,
        isBusy = false,
        identityChallenge = IdentityChallengeUiModel(
            endpoint = "Configured endpoint",
            algorithm = "Ed25519",
            fingerprint = "SHA256:preview-new-fingerprint",
            isChangedIdentity = true,
            previousFingerprints = listOf("SHA256:preview-old-fingerprint"),
        ),
        canEdit = true,
    ),
)

private fun previewDetail(
    session: SessionUiModel,
    action: SessionActionUiModel,
    approvalRequired: Boolean,
) = SessionDetailUiModel(
    session = session,
    activities = listOf(
        SessionActivityUiModel(
            id = "preview-activity",
            type = if (approvalRequired) {
                SessionActivityType.APPROVAL_REQUIRED
            } else {
                SessionActivityType.RECONNECTED
            },
            summary = UiMessage.Verbatim(
                if (approvalRequired) {
                    "A command needs explicit approval."
                } else {
                    "The session is ready."
                },
            ),
            occurredAtEpochMillis = 1_788_200_000_000,
            requiresAction = approvalRequired,
            isRead = false,
        ),
    ),
    transcript = listOf(
        TranscriptEntryUiModel(
            id = "preview-transcript",
            roleLabel = UiMessage.Verbatim("Agent"),
            kind = TimelineEntryKind.AGENT_COMMENTARY,
            text = "The deterministic preview fixture contains no live connection data.",
            wasTruncated = false,
            createdAtEpochMillis = 1_788_200_000_000,
        ),
    ),
    composer = SessionComposerUiModel(
        draftText = if (approvalRequired) "" else "Continue with the focused checks",
        selectionStart = if (approvalRequired) 0 else 31,
        selectionEnd = if (approvalRequired) 0 else 31,
        canSubmit = !approvalRequired,
        canInterrupt = true,
        statusMessage = if (approvalRequired) {
            UiMessage.Localized(R.string.session_composer_status_pending_action)
        } else {
            null
        },
    ),
    actions = if (approvalRequired) listOf(action) else emptyList(),
    artifacts = listOf(
        SessionArtifactUiModel(
            stableKey = "preview-artifact",
            sessionKey = session.stableKey,
            displayPath = "reports/ui-check.txt",
            changeKind = AgentFileChangeKind.MODIFIED,
            availabilityStatus = SessionArtifactAvailabilityStatus.READY,
            suggestedFileName = "ui-check.txt",
            isDownloadable = true,
            canSave = true,
            bytesWritten = 0,
            totalBytes = null,
            isExporting = false,
            isExportComplete = false,
        ),
    ),
    canRefreshArtifacts = true,
)

private fun previewApproval(session: SessionUiModel) = SessionActionUiModel(
    stableKey = "preview-approval",
    sessionKey = session.stableKey,
    title = UiMessage.Verbatim("Run the focused validation suite?"),
    type = AgentApprovalType.COMMAND,
    description = "Review the exact command and working directory before allowing it.",
    command = "./gradlew test lintDebug",
    scope = "/workspace/agent-relay",
    connectionLabel = session.connectionLabel,
    connectionProviderName = session.connectionProviderName,
    connectionTarget = "Configured endpoint",
    agentProviderLabel = session.agentProviderLabel,
    sessionTitle = session.title,
    questions = emptyList(),
    decisions = listOf(
        SessionDecisionUiModel(
            decision = AgentApprovalDecision.APPROVE_ONCE,
            requiresConfirmation = true,
            isPositive = true,
        ),
        SessionDecisionUiModel(
            decision = AgentApprovalDecision.DECLINE,
            requiresConfirmation = false,
            isPositive = false,
        ),
    ),
    risks = listOf(SessionActionRisk.DESTRUCTIVE_COMMAND),
    state = SessionActionState.PENDING,
    completedDecision = null,
    additionalConfirmationGiven = false,
    isBusy = false,
)
