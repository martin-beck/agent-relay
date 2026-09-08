/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.agentrelay.R
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.session.api.SessionActionRisk
import dev.agentrelay.session.api.SessionActionState

internal typealias SessionActionResponder = (
    sessionKey: String,
    actionKey: String,
    decision: AgentApprovalDecision,
    answers: Map<String, List<String>>,
    additionalConfirmationGiven: Boolean,
) -> Unit

@Composable
internal fun SessionActionCard(
    action: SessionActionUiModel,
    onRespond: SessionActionResponder,
    modifier: Modifier = Modifier,
) {
    val answerState = remember(action.stableKey) { ActionAnswerState() }
    var pendingConfirmation by remember(action.stableKey) {
        mutableStateOf<PendingActionResponse?>(null)
    }
    val answers = answerState.answers(action.questions)
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("session-action-" + action.stableKey),
        colors = CardDefaults.cardColors(containerColor = action.containerColor()),
    ) {
        ActionCardContent(
            action = action,
            answerState = answerState,
            answers = answers,
            onDecision = { response ->
                if (response.decision.requiresConfirmation) {
                    pendingConfirmation = response
                } else {
                    onRespond(
                        action.sessionKey,
                        action.stableKey,
                        response.decision.decision,
                        response.answers,
                        false,
                    )
                }
            },
        )
    }
    pendingConfirmation?.let { pending ->
        SensitiveActionConfirmation(
            action = action,
            response = pending,
            onConfirm = {
                onRespond(
                    action.sessionKey,
                    action.stableKey,
                    pending.decision.decision,
                    pending.answers,
                    true,
                )
                pendingConfirmation = null
            },
            onDismiss = { pendingConfirmation = null },
        )
    }
}

@Composable
private fun ActionCardContent(
    action: SessionActionUiModel,
    answerState: ActionAnswerState,
    answers: Map<String, List<String>>,
    onDecision: (PendingActionResponse) -> Unit,
) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ActionHeader(action)
        ActionContext(action)
        action.command?.let { ActionCommand(it) }
        ActionRisks(action.risks)
        if (action.questions.isNotEmpty() && action.state == SessionActionState.PENDING) {
            HorizontalDivider()
            ActionQuestions(
                questions = action.questions,
                answerState = answerState,
                enabled = !action.isBusy,
            )
        }
        ActionControls(
            action = action,
            everyQuestionAnswered = action.questions.all {
                answers[it.stableKey].orEmpty().isNotEmpty()
            },
            answers = answers,
            onDecision = onDecision,
        )
    }
}

@Composable
private fun ActionHeader(action: SessionActionUiModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = action.type.localizedLabel(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = action.title.resolve(),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (action.isBusy) {
            CircularProgressIndicator(Modifier.size(24.dp))
        }
    }
}

@Composable
private fun ActionContext(action: SessionActionUiModel) {
    LabeledValue(
        stringResource(R.string.session_action_context_connection_provider),
        action.connectionProviderName,
    )
    LabeledValue(
        stringResource(R.string.session_action_context_connection),
        stringResource(
            R.string.connection_identity_context,
            action.connectionLabel,
            action.connectionTarget,
        ),
    )
    LabeledValue(
        stringResource(R.string.session_action_context_agent_provider),
        action.agentProviderLabel,
    )
    LabeledValue(
        stringResource(R.string.session_action_context_session),
        action.sessionTitle.resolve(),
    )
    action.scope?.let {
        LabeledValue(stringResource(R.string.session_action_context_scope), it)
    }
    action.description?.let {
        LabeledValue(stringResource(R.string.session_action_context_rationale), it)
    }
}

@Composable
private fun ActionCommand(command: String) {
    Text(
        text = stringResource(R.string.session_action_command),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
    )
    SelectionContainer {
        Text(
            text = command,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            maxLines = 12,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ActionRisks(risks: List<SessionActionRisk>) {
    if (risks.isEmpty()) {
        return
    }
    Column(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.session_action_additional_confirmation),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
        )
        risks.forEach { risk ->
            Text(
                stringResource(R.string.session_hub_risk, risk.localizedLabel()),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ActionQuestions(
    questions: List<SessionQuestionUiModel>,
    answerState: ActionAnswerState,
    enabled: Boolean,
) {
    val focusManager = LocalFocusManager.current
    questions.forEach { question ->
        QuestionInput(
            question = question,
            selected = answerState.selectedOptions[question.stableKey].orEmpty(),
            otherAnswer = answerState.otherAnswers[question.stableKey].orEmpty(),
            enabled = enabled,
            focusManager = focusManager,
            onSelectedChanged = { answerState.select(question, it) },
            onOtherChanged = { answerState.changeOther(question, it) },
        )
    }
}

@Composable
private fun ActionControls(
    action: SessionActionUiModel,
    everyQuestionAnswered: Boolean,
    answers: Map<String, List<String>>,
    onDecision: (PendingActionResponse) -> Unit,
) {
    when {
        action.state == SessionActionState.RESOLVED -> ResolvedActionStatus(action)
        action.isBusy || action.state == SessionActionState.DELIVERING -> DeliveringActionStatus()
        else -> DecisionButtons(action, everyQuestionAnswered, answers, onDecision)
    }
}

@Composable
private fun ResolvedActionStatus(action: SessionActionUiModel) {
    val decision = action.completedDecision?.localizedLabel()
    Text(
        text = when {
            decision != null && action.additionalConfirmationGiven -> stringResource(
                R.string.session_action_resolved_with_confirmation,
                decision,
            )
            decision != null -> stringResource(R.string.session_action_resolved_with, decision)
            action.additionalConfirmationGiven ->
                stringResource(R.string.session_action_resolved_confirmation)
            else -> stringResource(R.string.session_action_resolved)
        },
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun DeliveringActionStatus() {
    Text(
        text = stringResource(R.string.session_action_delivery_pending),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun DecisionButtons(
    action: SessionActionUiModel,
    everyQuestionAnswered: Boolean,
    answers: Map<String, List<String>>,
    onDecision: (PendingActionResponse) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        action.decisions.forEach { decision ->
            val decisionLabel = decision.decision.localizedLabel()
            val enabled = decision.decision != AgentApprovalDecision.SUBMIT ||
                everyQuestionAnswered
            val response = PendingActionResponse(
                decision = decision,
                answers = if (decision.decision == AgentApprovalDecision.SUBMIT) {
                    answers
                } else {
                    emptyMap()
                },
            )
            if (decision.isPositive) {
                Button(onClick = { onDecision(response) }, enabled = enabled) {
                    Text(decisionLabel)
                }
            } else {
                OutlinedButton(onClick = { onDecision(response) }, enabled = enabled) {
                    Text(decisionLabel)
                }
            }
        }
    }
}

@Composable
private fun SensitiveActionConfirmation(
    action: SessionActionUiModel,
    response: PendingActionResponse,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        modifier = Modifier.testTag("action-confirmation-dialog"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.session_action_confirm_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.session_action_confirm_guidance))
                Text(
                    stringResource(
                        R.string.session_hub_action_context,
                        action.connectionProviderName,
                        action.connectionLabel,
                        action.agentProviderLabel,
                    ),
                    fontWeight = FontWeight.SemiBold,
                )
                action.scope?.let {
                    Text(stringResource(R.string.session_action_scope_value, it))
                }
                action.risks.forEach {
                    Text(stringResource(R.string.session_hub_risk, it.localizedLabel()))
                }
                action.command?.let { ConfirmedCommand(it) }
                val decisionLabel = response.decision.decision.localizedLabel()
                Text(stringResource(R.string.session_action_decision_value, decisionLabel))
            }
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(
                    stringResource(
                        R.string.session_action_confirm_decision,
                        response.decision.decision.localizedLabel(),
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_go_back))
            }
        },
    )
}

@Composable
private fun ConfirmedCommand(command: String) {
    Text(
        text = stringResource(R.string.session_action_command),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
    )
    SelectionContainer {
        Text(
            text = command,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

@Composable
private fun QuestionInput(
    question: SessionQuestionUiModel,
    selected: Set<String>,
    otherAnswer: String,
    enabled: Boolean,
    focusManager: FocusManager,
    onSelectedChanged: (Set<String>) -> Unit,
    onOtherChanged: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        question.header?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(question.prompt.resolve(), style = MaterialTheme.typography.bodyMedium)
        if (question.options.isNotEmpty()) {
            FlowRow(
                modifier = if (question.allowsMultiple) {
                    Modifier
                } else {
                    Modifier.selectableGroup()
                },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                question.options.forEach { option ->
                    FilterChip(
                        modifier = Modifier.semantics {
                            role = if (question.allowsMultiple) {
                                Role.Checkbox
                            } else {
                                Role.RadioButton
                            }
                        },
                        selected = option.label in selected,
                        onClick = {
                            onSelectedChanged(
                                if (question.allowsMultiple) {
                                    selected.toggle(option.label)
                                } else {
                                    setOf(option.label)
                                },
                            )
                        },
                        enabled = enabled,
                        label = {
                            Column {
                                Text(option.label)
                                option.description?.let {
                                    Text(it, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        },
                    )
                }
            }
        }
        if (question.allowsOther) {
            OutlinedTextField(
                value = otherAnswer,
                onValueChange = onOtherChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("question-other-" + question.stableKey),
                enabled = enabled,
                label = { Text(stringResource(R.string.session_action_other_answer)) },
                singleLine = !question.allowsMultiple,
                maxLines = if (question.allowsMultiple) 4 else 1,
                keyboardOptions = KeyboardOptions(
                    imeAction = if (question.allowsMultiple) {
                        ImeAction.Default
                    } else {
                        ImeAction.Done
                    },
                ),
                keyboardActions = KeyboardActions(
                    onDone = { focusManager.clearFocus() },
                ),
            )
        }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

private class ActionAnswerState {
    val selectedOptions = mutableStateMapOf<String, Set<String>>()
    val otherAnswers = mutableStateMapOf<String, String>()

    fun answers(questions: List<SessionQuestionUiModel>): Map<String, List<String>> =
        questions.associate { question ->
            val selected = selectedOptions[question.stableKey].orEmpty().toList()
            val other = otherAnswers[question.stableKey]
                ?.trim()
                ?.takeIf(String::isNotEmpty)
            question.stableKey to if (other == null) selected else selected + other
        }

    fun select(question: SessionQuestionUiModel, selected: Set<String>) {
        selectedOptions[question.stableKey] = selected
        if (!question.allowsMultiple && selected.isNotEmpty()) {
            otherAnswers[question.stableKey] = ""
        }
    }

    fun changeOther(question: SessionQuestionUiModel, value: String) {
        otherAnswers[question.stableKey] = value.take(MAX_ANSWER_CHARS)
        if (!question.allowsMultiple && value.isNotBlank()) {
            selectedOptions[question.stableKey] = emptySet()
        }
    }
}

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

@Composable
private fun SessionActionUiModel.containerColor() = when {
    state == SessionActionState.RESOLVED -> MaterialTheme.colorScheme.surfaceContainerLow
    risks.isNotEmpty() -> MaterialTheme.colorScheme.errorContainer
    else -> MaterialTheme.colorScheme.tertiaryContainer
}

private data class PendingActionResponse(
    val decision: SessionDecisionUiModel,
    val answers: Map<String, List<String>>,
)

private const val MAX_ANSWER_CHARS = 4_096
