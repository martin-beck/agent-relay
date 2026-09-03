package com.example.agentrelay.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.example.agentrelay.R

@Composable
internal fun ConnectionCard(
    connection: ConnectionUiModel,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onTrustIdentity: (Boolean) -> Unit,
    onRejectIdentity: () -> Unit,
    onEdit: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = connection.providerName,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = connection.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = connection.target,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ConnectionStatusChip(connection.status)
            }
            connection.statusDetail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (connection.status == ConnectionStatus.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                text = agentSummary(connection),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            connection.authenticationLabel?.let {
                Text(
                    text = stringResource(R.string.connection_authentication, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            connection.identityChallenge?.let { challenge ->
                IdentityChallengeCard(
                    challenge = challenge,
                    enabled = !connection.isBusy,
                    onTrust = { onTrustIdentity(challenge.isChangedIdentity) },
                    onReject = onRejectIdentity,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (connection.canConnect) {
                    FilledTonalButton(
                        onClick = onConnect,
                        enabled = !connection.isBusy,
                    ) {
                        Text(stringResource(R.string.connection_connect))
                    }
                }
                if (connection.canDisconnect) {
                    OutlinedButton(
                        onClick = onDisconnect,
                        enabled = !connection.isBusy,
                    ) {
                        Text(stringResource(R.string.connection_disconnect))
                    }
                }
                if (connection.canEdit) {
                    TextButton(
                        onClick = onEdit,
                        enabled = !connection.isBusy,
                    ) {
                        Text(stringResource(R.string.connection_edit_profile))
                    }
                }
                if (connection.isBusy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun IdentityChallengeCard(
    challenge: IdentityChallengeUiModel,
    enabled: Boolean,
    onTrust: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = if (challenge.isChangedIdentity) {
                    stringResource(R.string.connection_identity_changed)
                } else {
                    stringResource(R.string.connection_identity_verify_new)
                },
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = stringResource(R.string.connection_identity_context, challenge.endpoint, challenge.algorithm),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            SelectionContainer {
                Text(
                    text = challenge.fingerprint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            if (challenge.previousFingerprints.isNotEmpty()) {
                SelectionContainer {
                    Text(
                        text = stringResource(R.string.connection_identity_previously_trusted, challenge.previousFingerprints.joinToString()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onTrust,
                    enabled = enabled,
                    colors = if (challenge.isChangedIdentity) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    Text(
                        if (challenge.isChangedIdentity) {
                            stringResource(R.string.connection_identity_replace)
                        } else {
                            stringResource(R.string.connection_identity_trust)
                        },
                    )
                }
                TextButton(
                    onClick = onReject,
                    enabled = enabled,
                ) {
                    Text(stringResource(R.string.connection_identity_reject))
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusChip(status: ConnectionStatus) {
    val label = when (status) {
        ConnectionStatus.OFFLINE -> stringResource(R.string.connection_status_offline)
        ConnectionStatus.CONNECTING -> stringResource(R.string.connection_status_connecting)
        ConnectionStatus.ONLINE -> stringResource(R.string.connection_status_online)
        ConnectionStatus.RECONNECTING -> stringResource(R.string.connection_status_reconnecting)
        ConnectionStatus.IDENTITY_REVIEW -> stringResource(R.string.connection_status_identity_review)
        ConnectionStatus.FAILED -> stringResource(R.string.connection_status_failed)
    }
    val colors = when (status) {
        ConnectionStatus.ONLINE ->
            MaterialTheme.colorScheme.secondaryContainer to
                MaterialTheme.colorScheme.onSecondaryContainer
        ConnectionStatus.FAILED,
        ConnectionStatus.IDENTITY_REVIEW,
        -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = colors.first,
        contentColor = colors.second,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private fun agentSummary(connection: ConnectionUiModel): String = when {
    connection.agentCount == 0 -> "Agent providers are checked after connecting."
    connection.connectedAgentCount > 0 ->
        "${connection.connectedAgentCount} of ${connection.agentCount} agent providers ready"
    connection.unavailableAgentCount == connection.agentCount ->
        "No installed agent provider is ready on this connection."
    else -> "Checking ${connection.agentCount} agent providers"
}
