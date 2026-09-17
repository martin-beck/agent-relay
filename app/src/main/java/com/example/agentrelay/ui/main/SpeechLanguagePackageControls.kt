/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.agentrelay.R
import dev.agentrelay.speech.api.SpeechModelAvailability
import dev.agentrelay.speech.api.SpeechModelState

internal data class SpeechLanguagePackageActions(
    val install: (String) -> Unit,
    val cancel: (String) -> Unit,
    val remove: (String) -> Unit,
    val setAllowMetered: (Boolean) -> Unit,
)

@Composable
internal fun SpeechLanguagePackageControls(
    models: List<SpeechModelState>,
    allowMeteredNetwork: Boolean,
    actions: SpeechLanguagePackageActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.speech_language_packages_title),
            modifier = Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = allowMeteredNetwork, onCheckedChange = actions.setAllowMetered)
            Text(stringResource(R.string.speech_language_packages_metered))
        }
        models.forEach { model ->
            SpeechLanguagePackageRow(model, actions)
        }
    }
}

@Composable
private fun SpeechLanguagePackageRow(
    model: SpeechModelState,
    actions: SpeechLanguagePackageActions,
) {
    val packageInfo = model.descriptor.modelPackage
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(model.descriptor.displayName, style = MaterialTheme.typography.titleSmall)
        Text(stringResource(R.string.speech_language_packages_details, model.descriptor.languageTags.sorted().joinToString(), packageInfo.downloadSizeBytes, packageInfo.installedSizeBytes))
        Text(stringResource(R.string.speech_language_packages_license, model.descriptor.license.name))
        when (val availability = model.availability) {
            SpeechModelAvailability.NotInstalled -> Button({ actions.install(model.descriptor.id.value) }) {
                Text(stringResource(R.string.speech_language_packages_install))
            }
            is SpeechModelAvailability.Downloading -> {
                LinearProgressIndicator(
                    progress = { availability.downloadedBytes.toFloat() / availability.totalBytes },
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(stringResource(R.string.speech_language_packages_progress, availability.downloadedBytes, availability.totalBytes))
                OutlinedButton({ actions.cancel(model.descriptor.id.value) }) {
                    Text(stringResource(R.string.speech_language_packages_cancel))
                }
            }
            SpeechModelAvailability.Ready -> OutlinedButton({ actions.remove(model.descriptor.id.value) }) {
                Text(stringResource(R.string.speech_language_packages_remove))
            }
            is SpeechModelAvailability.Failed -> {
                Text(availability.failure.actionableMessage, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                Button({ actions.install(model.descriptor.id.value) }) {
                    Text(stringResource(R.string.speech_language_packages_retry))
                }
            }
        }
    }
}
