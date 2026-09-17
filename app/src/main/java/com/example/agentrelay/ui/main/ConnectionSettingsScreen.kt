/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.agentrelay.R
import com.example.agentrelay.background.BackgroundTransportState
import com.example.agentrelay.notifications.SessionNotificationPermissionState

@Composable
internal fun ConnectionSettingsScreen(
    notificationPermissionState: SessionNotificationPermissionState,
    onRequestNotificationPermission: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    backgroundTransportState: BackgroundTransportState,
    onStartBackgroundTransport: () -> Unit,
    onStopBackgroundTransport: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.session_hub_connections_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        if (notificationPermissionState != SessionNotificationPermissionState.HIDDEN) {
            SessionNotificationPermissionCard(
                state = notificationPermissionState,
                onRequestPermission = onRequestNotificationPermission,
                onOpenSettings = onOpenNotificationSettings,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        BackgroundTransportCard(
            state = backgroundTransportState,
            notificationsAvailable =
            notificationPermissionState == SessionNotificationPermissionState.HIDDEN,
            onStart = onStartBackgroundTransport,
            onStop = onStopBackgroundTransport,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onBack) {
            Text(stringResource(R.string.action_go_back))
        }
    }
}
