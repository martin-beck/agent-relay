package com.example.agentrelay.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.agentrelay.R

internal const val WEAR_INSTALL_OFFER_TEST_TAG = "wear-install-offer"

@Composable
internal fun WearInstallOfferCard(
    state: WearInstallOfferUiState,
    onInstall: () -> Unit,
    onDecline: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state == WearInstallOfferUiState.Hidden) return

    val alias = when (state) {
        is WearInstallOfferUiState.Offer -> state.deviceAlias
        is WearInstallOfferUiState.Installing -> state.deviceAlias
        is WearInstallOfferUiState.Recovery -> state.deviceAlias
        WearInstallOfferUiState.Hidden -> return
    }
    val title = when (state) {
        is WearInstallOfferUiState.Offer -> stringResource(R.string.wear_install_offer_title, alias)
        is WearInstallOfferUiState.Installing -> stringResource(R.string.wear_install_progress_title, alias)
        is WearInstallOfferUiState.Recovery -> stringResource(R.string.wear_install_recovery_title, alias)
        WearInstallOfferUiState.Hidden -> return
    }

    Card(
        modifier = modifier
            .testTag(WEAR_INSTALL_OFFER_TEST_TAG)
            .semantics {
                paneTitle = title
                if (state is WearInstallOfferUiState.Installing || state is WearInstallOfferUiState.Recovery) {
                    liveRegion = LiveRegionMode.Polite
                }
            },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            when (state) {
                is WearInstallOfferUiState.Offer -> WearInstallOfferActions(onInstall, onDecline)
                is WearInstallOfferUiState.Installing -> WearInstallProgress(onCancel)
                is WearInstallOfferUiState.Recovery -> WearInstallRecovery(state.reason, onRetry, onDecline)
                WearInstallOfferUiState.Hidden -> Unit
            }
        }
    }
}

@Composable
private fun WearInstallOfferActions(onInstall: () -> Unit, onDecline: () -> Unit) {
    Text(stringResource(R.string.wear_install_offer_explanation))
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onInstall) {
            Text(stringResource(R.string.wear_install_action))
        }
        OutlinedButton(onClick = onDecline) {
            Text(stringResource(R.string.wear_install_not_now))
        }
    }
}

@Composable
private fun WearInstallProgress(onCancel: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator()
        Text(
            text = stringResource(R.string.wear_install_progress_explanation),
            modifier = Modifier.weight(1f),
        )
    }
    OutlinedButton(onClick = onCancel) {
        Text(stringResource(R.string.wear_install_cancel))
    }
}

@Composable
private fun WearInstallRecovery(
    reason: WearInstallRecoveryReason,
    onRetry: () -> Unit,
    onDecline: () -> Unit,
) {
    val explanation = when (reason) {
        WearInstallRecoveryReason.AUTHORIZATION_REQUIRED ->
            stringResource(R.string.wear_install_recovery_authorization)
        WearInstallRecoveryReason.INSTALLATION_FAILED ->
            stringResource(R.string.wear_install_recovery_installation)
        WearInstallRecoveryReason.CONNECTION_FAILED ->
            stringResource(R.string.wear_install_recovery_connection)
    }
    Text(explanation)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onRetry) {
            Text(stringResource(R.string.wear_install_retry))
        }
        OutlinedButton(onClick = onDecline) {
            Text(stringResource(R.string.wear_install_not_now))
        }
    }
}
