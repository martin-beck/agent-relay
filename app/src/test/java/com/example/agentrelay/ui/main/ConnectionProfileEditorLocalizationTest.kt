/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.agentrelay.connection.api.ConnectionProfileFieldType
import dev.agentrelay.ssh.api.SshConnectionProfileSchema
import dev.agentrelay.ssh.api.SshConnectionProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConnectionProfileEditorLocalizationTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun sshCatalogLocalizesSchemaCopyAndPreservesOpaqueValues() {
        val source = sshEditor()
        lateinit var localized: ConnectionProfileEditorUiState.Editing

        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides localizedContext("de")) {
                localized = source.localizedConnectionProfile()
            }
        }
        composeTestRule.waitForIdle()

        assertEquals("Secure-Shell-Profil hinzufügen", localized.title)
        assertEquals(
            "Profilname",
            localized.fields.single {
                it.id == SshConnectionProfileSchema.PROFILE_LABEL.value
            }.label,
        )
        assertEquals(
            "Host 42",
            localized.fields.single {
                it.id == SshConnectionProfileSchema.PROFILE_LABEL.value
            }.value,
        )
        val jumpHost = localized.fields.single {
            it.id == SshConnectionProfileSchema.JUMP_HOST.value
        }
        assertEquals("Sprunghost", jumpHost.label)
        assertEquals("Direkte Verbindung", jumpHost.options.first().label)
        assertEquals("Gateway 42", jumpHost.options.last().label)
        assertEquals(
            "Wird nach dem Speichern dieses Profils erstellt.",
            localized.fields.single {
                it.id == SshConnectionProfileSchema.PUBLIC_KEY.value
            }.value,
        )
        assertEquals(
            "Öffentlichen Schlüssel installieren",
            localized.operations.single().label,
        )
        assertEquals(
            "Öffentlichen Schlüssel auf diesem Remote-Konto installieren?",
            localized.operations.single().confirmationTitle,
        )
        assertEquals(
            "Agent Relay stellt mit den gespeicherten Anmeldedaten eine Verbindung her und fügt " +
                "den öffentlichen Schlüssel dieses Profils zu ~/.ssh/authorized_keys hinzu. " +
                "Der private Schlüssel verlässt niemals den Android Keystore.",
            localized.operations.single().confirmationMessage,
        )
    }

    @Test
    fun existingPublicKeysAndUnknownProvidersRemainProviderOwned() {
        val source = sshEditor().copy(
            fields = sshEditor().fields.map { field ->
                if (field.id == SshConnectionProfileSchema.PUBLIC_KEY.value) {
                    field.copy(value = "ssh-ed25519 PUBLIC_KEY_42")
                } else {
                    field
                }
            },
        )
        lateinit var localized: ConnectionProfileEditorUiState.Editing
        lateinit var unknownProvider: ConnectionProfileEditorUiState.Editing

        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides localizedContext("fr")) {
                localized = source.localizedConnectionProfile()
                unknownProvider = source.copy(
                    providerId = "future.local",
                    title = "Provider title",
                ).localizedConnectionProfile()
            }
        }
        composeTestRule.waitForIdle()

        assertEquals(
            "ssh-ed25519 PUBLIC_KEY_42",
            localized.fields.single {
                it.id == SshConnectionProfileSchema.PUBLIC_KEY.value
            }.value,
        )
        assertEquals("Provider title", unknownProvider.title)
        assertSame(
            unknownProvider.fields,
            source.fields,
        )
    }

    private fun sshEditor() = ConnectionProfileEditorUiState.Editing(
        providerId = SshConnectionProvider.ID.value,
        profileId = null,
        title = "Provider add title",
        fields = listOf(
            field(
                id = SshConnectionProfileSchema.PROFILE_LABEL.value,
                label = "Provider profile label",
                value = "Host 42",
                supportingText = "Provider profile support",
            ),
            field(
                id = SshConnectionProfileSchema.JUMP_HOST.value,
                label = "Provider jump-host label",
                type = ConnectionProfileFieldType.SINGLE_CHOICE,
                value = SshConnectionProfileSchema.DIRECT_CONNECTION,
                supportingText = null,
                options = listOf(
                    ConnectionProfileFieldOptionUiModel(
                        value = SshConnectionProfileSchema.DIRECT_CONNECTION,
                        label = "Provider direct label",
                        supportingText = null,
                    ),
                    ConnectionProfileFieldOptionUiModel(
                        value = "jump-42",
                        label = "Gateway 42",
                        supportingText = "gateway.example.test:22",
                    ),
                ),
            ),
            field(
                id = SshConnectionProfileSchema.PUBLIC_KEY.value,
                label = "Provider public-key label",
                type = ConnectionProfileFieldType.READ_ONLY,
                value = "",
                supportingText = "Provider public-key support",
            ),
        ),
        operations = listOf(
            ConnectionProfileOperationUiModel(
                id = SshConnectionProfileSchema.INSTALL_PUBLIC_KEY.value,
                label = "Provider operation label",
                supportingText = "Provider operation support",
                confirmationTitle = "Provider confirmation title",
                confirmationMessage = "Provider confirmation message",
            ),
        ),
        canDelete = false,
    )

    private fun field(
        id: String,
        label: String,
        type: ConnectionProfileFieldType = ConnectionProfileFieldType.TEXT,
        value: String,
        supportingText: String?,
        options: List<ConnectionProfileFieldOptionUiModel> = emptyList(),
    ) = ConnectionProfileFieldUiModel(
        id = id,
        label = label,
        type = type,
        value = value,
        supportingText = supportingText,
        required = false,
        maxLength = 512,
        options = options,
        visibleWhen = emptyList(),
        hasStoredSecret = false,
    )

    private fun localizedContext(languageTag: String): Context {
        val application: Application = RuntimeEnvironment.getApplication()
        val configuration = Configuration(application.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(languageTag))
        return application.createConfigurationContext(configuration)
    }
}
