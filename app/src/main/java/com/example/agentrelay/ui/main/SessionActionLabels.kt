/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.agentrelay.R
import dev.agentrelay.provider.api.AgentApprovalDecision
import dev.agentrelay.provider.api.AgentApprovalType
import dev.agentrelay.session.api.SessionActionRisk

@Composable
internal fun AgentApprovalType.localizedLabel(): String = stringResource(
    when (this) {
        AgentApprovalType.COMMAND -> R.string.session_action_type_command
        AgentApprovalType.FILE_CHANGE -> R.string.session_action_type_file_change
        AgentApprovalType.USER_INPUT -> R.string.session_action_type_question
        AgentApprovalType.PERMISSION -> R.string.session_action_type_permission
        AgentApprovalType.EXTERNAL_TOOL -> R.string.session_action_type_external_tool
    },
)

@Composable
internal fun AgentApprovalDecision.localizedLabel(): String = stringResource(
    when (this) {
        AgentApprovalDecision.APPROVE_ONCE -> R.string.session_action_decision_approve_once
        AgentApprovalDecision.APPROVE_FOR_SESSION ->
            R.string.session_action_decision_approve_for_session
        AgentApprovalDecision.SUBMIT -> R.string.session_action_decision_submit
        AgentApprovalDecision.DECLINE -> R.string.session_action_decision_decline
        AgentApprovalDecision.CANCEL -> R.string.action_cancel
    },
)

@Composable
internal fun SessionActionRisk.localizedLabel(): String = stringResource(
    when (this) {
        SessionActionRisk.DESTRUCTIVE_COMMAND ->
            R.string.session_action_risk_destructive_command
        SessionActionRisk.BROAD_FILESYSTEM_ACCESS ->
            R.string.session_action_risk_broad_filesystem
        SessionActionRisk.CREDENTIAL_ACCESS ->
            R.string.session_action_risk_credential_access
        SessionActionRisk.NETWORK_EXPANSION ->
            R.string.session_action_risk_network_expansion
        SessionActionRisk.EXTERNAL_TOOL ->
            R.string.session_action_risk_external_tool
    },
)
