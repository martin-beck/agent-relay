/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.view.View
import java.text.NumberFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalizedResourcesTest {

    private val application: Application
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun bundledLocalesResolveTheirNotificationPermissionCopy() {
        val english = application.getString(R.string.notification_permission_allow)

        EXPECTED_ALLOW_LABELS.forEach { (languageTag, expected) ->
            val localized = localizedContext(languageTag)
                .getString(R.string.notification_permission_allow)

            assertEquals(languageTag, expected, localized)
            assertNotEquals(languageTag, english, localized)
        }
    }

    @Test
    fun bundledLocalesFormatOpaqueSessionAndSpeechValues() {
        val opaqueValues = listOf("Secure Shell", "Host 42", "Codex")

        (setOf("en-US") + EXPECTED_ALLOW_LABELS.keys).forEach { languageTag ->
            val context = localizedContext(languageTag)
            val sessionContext = context.getString(
                R.string.session_creator_context,
                *opaqueValues.toTypedArray(),
            )
            val selectedModel = context.getString(
                R.string.speech_selected_model,
                "model.test",
            )
            val addProfile = context.getString(
                R.string.session_hub_add_profile,
                opaqueValues[0],
            )
            val startOnConnection = context.getString(
                R.string.session_hub_start_on_connection,
                opaqueValues[2],
                opaqueValues[1],
            )
            val reviewIn = context.getString(
                R.string.session_hub_review_in,
                opaqueValues[1],
            )
            val actionContext = context.getString(
                R.string.session_hub_action_context,
                *opaqueValues.toTypedArray(),
            )
            val detailContext = context.getString(
                R.string.session_detail_context,
                *opaqueValues.toTypedArray(),
            )
            val messageTo = context.getString(
                R.string.session_composer_message_to,
                opaqueValues[2],
            )
            val connectDraft = context.getString(
                R.string.session_composer_status_connect_draft,
                opaqueValues[1],
            )
            val fallbackSessionTitle = context.getString(
                R.string.session_title_fallback,
                opaqueValues[2],
            )
            val artifactProgress = context.getString(
                R.string.session_artifact_save_progress_total,
                NumberFormat
                    .getIntegerInstance(Locale.forLanguageTag(languageTag))
                    .format(42),
                NumberFormat
                    .getIntegerInstance(Locale.forLanguageTag(languageTag))
                    .format(84),
            )
            val risk = context.getString(
                R.string.session_hub_risk,
                opaqueValues[0],
            )
            val authentication = context.getString(
                R.string.connection_authentication,
                "Public key: id_test",
            )
            val identityContext = context.getString(
                R.string.connection_identity_context,
                "relay.example.test:22",
                "ssh-ed25519",
            )
            val previouslyTrusted = context.getString(
                R.string.connection_identity_previously_trusted,
                "SHA256:test-fingerprint",
            )
            val readyAgents = context.resources.getQuantityString(
                R.plurals.connection_agent_ready,
                2,
                2,
                3,
            )
            val checkingAgents = context.resources.getQuantityString(
                R.plurals.connection_agent_checking,
                3,
                3,
            )
            val speechTranscriptTooLong = context.resources.getQuantityString(
                R.plurals.speech_error_transcript_too_long,
                32_000,
                32_000,
            )
            val sessionDraftTooLong = context.resources.getQuantityString(
                R.plurals.main_error_session_draft_too_long,
                32_000,
                32_000,
            )
            val workingDirectoryTooLong = context.resources.getQuantityString(
                R.plurals.main_error_working_directory_too_long,
                4_096,
                4_096,
            )
            val modelNameTooLong = context.resources.getQuantityString(
                R.plurals.main_error_model_name_too_long,
                256,
                256,
            )
            val downloadProgress = context.getString(R.string.speech_download_progress, 42)
            val requiredField = context.getString(
                R.string.profile_editor_required,
                opaqueValues[1],
            )
            val actionDecision = context.getString(
                R.string.session_action_resolved_with_confirmation,
                "Decision 42",
            )
            val actionScope = context.getString(
                R.string.session_action_scope_value,
                "/workspace/test",
            )
            val sessionAccessibilityContext = context.getString(
                R.string.session_card_accessibility_context,
                *opaqueValues.toTypedArray(),
            )
            val unreadSessions = context.resources.getQuantityString(
                R.plurals.session_card_unread,
                2,
                2,
            )
            val awaitingActions = context.resources.getQuantityString(
                R.plurals.session_card_awaiting_action,
                2,
                2,
            )
            val wearOfferTitle = context.getString(
                R.string.wear_install_offer_title,
                "Watch 42",
            )
            val wearProgressTitle = context.getString(
                R.string.wear_install_progress_title,
                "Watch 42",
            )
            val wearRecoveryTitle = context.getString(
                R.string.wear_install_recovery_title,
                "Watch 42",
            )

            opaqueValues.forEach { value ->
                assertTrue(languageTag, sessionContext.contains(value))
            }
            assertTrue(languageTag, selectedModel.contains("model.test"))
            assertTrue(languageTag, addProfile.contains(opaqueValues[0]))
            assertTrue(languageTag, startOnConnection.contains(opaqueValues[2]))
            assertTrue(languageTag, startOnConnection.contains(opaqueValues[1]))
            assertTrue(languageTag, reviewIn.contains(opaqueValues[1]))
            assertTrue(languageTag, messageTo.contains(opaqueValues[2]))
            assertTrue(languageTag, connectDraft.contains(opaqueValues[1]))
            assertTrue(languageTag, fallbackSessionTitle.contains(opaqueValues[2]))
            opaqueValues.forEach { value ->
                assertTrue(languageTag, actionContext.contains(value))
                assertTrue(languageTag, detailContext.contains(value))
            }
            assertTrue(languageTag, risk.contains(opaqueValues[0]))
            assertTrue(languageTag, authentication.contains("Public key: id_test"))
            assertTrue(languageTag, identityContext.contains("relay.example.test:22"))
            assertTrue(languageTag, identityContext.contains("ssh-ed25519"))
            assertTrue(languageTag, previouslyTrusted.contains("SHA256:test-fingerprint"))
            assertFalse(languageTag, authentication.contains("%1\$"))
            assertFalse(languageTag, identityContext.contains("%1\$"))
            assertFalse(languageTag, identityContext.contains("%2\$"))
            assertFalse(languageTag, previouslyTrusted.contains("%1\$"))
            assertTrue(languageTag, readyAgents.isNotBlank())
            assertFalse(languageTag, readyAgents.contains("%1\$"))
            assertFalse(languageTag, readyAgents.contains("%2\$"))
            assertFalse(languageTag, checkingAgents.contains("%1\$"))
            assertFalse(languageTag, actionContext.contains("%1\$"))
            assertFalse(languageTag, detailContext.contains("%1\$"))
            assertFalse(languageTag, messageTo.contains("%1\$"))
            SPEECH_ERROR_RESOURCES.forEach { resource ->
                assertTrue(languageTag, context.getString(resource).isNotBlank())
            }
            SPEECH_STATUS_RESOURCES.forEach { resource ->
                assertTrue(languageTag, context.getString(resource).isNotBlank())
            }
            assertFalse(languageTag, risk.contains("%1\$"))
            assertFalse(languageTag, addProfile.contains("%1\$"))
            assertFalse(languageTag, startOnConnection.contains("%1\$"))
            assertTrue(languageTag, requiredField.contains(opaqueValues[1]))
            assertFalse(languageTag, requiredField.contains("%1\$"))
            assertTrue(languageTag, actionDecision.contains("Decision 42"))
            assertFalse(languageTag, actionDecision.contains("%1\$"))
            assertTrue(languageTag, actionScope.contains("/workspace/test"))
            assertFalse(languageTag, actionScope.contains("%1\$"))
            (ACTION_LABEL_RESOURCES + PRESENTATION_FALLBACK_RESOURCES).forEach { resource ->
                assertTrue(languageTag, context.getString(resource).isNotBlank())
            }
            (COORDINATOR_ISSUE_RESOURCES + ACTIVITY_SUMMARY_RESOURCES).forEach {
                    (resource, arguments),
                ->
                val rendered = context.getString(resource, *arguments.toTypedArray())
                arguments.forEach { argument ->
                    assertTrue(languageTag, rendered.contains(argument))
                }
                assertFalse(languageTag, rendered.contains("%1\$"))
                assertFalse(languageTag, rendered.contains("%2\$"))
            }
            SSH_PROFILE_RESOURCES.forEach { resource ->
                assertTrue(languageTag, context.getString(resource).isNotBlank())
            }
            MAIN_ERROR_RESOURCES.forEach { resource ->
                assertTrue(languageTag, context.getString(resource).isNotBlank())
            }
            ARTIFACT_RESOURCES.forEach { resource ->
                assertTrue(languageTag, context.getString(resource).isNotBlank())
            }
            opaqueValues.forEach { value ->
                assertTrue(languageTag, sessionAccessibilityContext.contains(value))
            }
            assertFalse(languageTag, sessionAccessibilityContext.contains("%1\$"))
            assertTrue(languageTag, unreadSessions.isNotBlank())
            assertTrue(languageTag, awaitingActions.isNotBlank())
            WEAR_INSTALL_RESOURCES.forEach { resource ->
                assertTrue(languageTag, context.getString(resource).isNotBlank())
            }
            listOf(wearOfferTitle, wearProgressTitle, wearRecoveryTitle).forEach { title ->
                assertTrue(languageTag, title.contains("Watch 42"))
                assertFalse(languageTag, title.contains("%1\$"))
            }
            assertFalse(languageTag, unreadSessions.contains("%1\$"))
            assertFalse(languageTag, awaitingActions.contains("%1\$"))
            assertTrue(languageTag, context.getString(R.string.main_loading).isNotBlank())
            val localizedNumber = NumberFormat
                .getIntegerInstance(Locale.forLanguageTag(languageTag))
                .format(42)
            val localizedTotal = NumberFormat
                .getIntegerInstance(Locale.forLanguageTag(languageTag))
                .format(84)
            assertTrue(languageTag, artifactProgress.contains(localizedNumber))
            assertTrue(languageTag, artifactProgress.contains(localizedTotal))
            assertFalse(languageTag, artifactProgress.contains("%1\$"))
            assertFalse(languageTag, speechTranscriptTooLong.contains("%1\$"))
            assertFalse(languageTag, speechTranscriptTooLong.contains("%d"))
            assertFalse(languageTag, sessionDraftTooLong.contains("%1\$"))
            assertFalse(languageTag, sessionDraftTooLong.contains("%d"))
            assertFalse(languageTag, workingDirectoryTooLong.contains("%1\$"))
            assertFalse(languageTag, workingDirectoryTooLong.contains("%d"))
            assertFalse(languageTag, modelNameTooLong.contains("%1\$"))
            assertFalse(languageTag, modelNameTooLong.contains("%d"))
            assertFalse(languageTag, artifactProgress.contains("%2\$"))

            assertTrue(languageTag, downloadProgress.contains(localizedNumber))
            assertTrue(languageTag, downloadProgress.contains("%"))
            assertFalse(languageTag, sessionContext.contains("%1\$"))
            assertFalse(languageTag, selectedModel.contains("%1\$"))
        }
    }

    @Test
    fun generatedLocaleConfigDeclaresRealAndPseudoLocales() {
        val resourceId = application.resources.getIdentifier(
            "_generated_res_locale_config",
            "xml",
            application.packageName,
        )
        assertNotEquals(0, resourceId)

        val declared = buildSet {
            application.resources.getXml(resourceId).use { parser ->
                while (parser.next() != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                        add(parser.getAttributeValue(ANDROID_NAMESPACE, "name"))
                    }
                }
            }
        }

        assertEquals(EXPECTED_GENERATED_LOCALES, declared)
    }

    @Test
    fun arabicResourcesUseRightToLeftLayoutDirection() {
        val configuration = localizedContext("ar").resources.configuration

        assertEquals(View.LAYOUT_DIRECTION_RTL, configuration.layoutDirection)
    }

    private fun localizedContext(languageTag: String): Context {
        val configuration = Configuration(application.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(languageTag))
        return application.createConfigurationContext(configuration)
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"

        val ACTION_LABEL_RESOURCES = listOf(
            R.string.session_action_type_command,
            R.string.session_action_type_file_change,
            R.string.session_action_type_question,
            R.string.session_action_type_permission,
            R.string.session_action_type_external_tool,
            R.string.session_action_decision_approve_once,
            R.string.session_action_decision_approve_for_session,
            R.string.session_action_decision_submit,
            R.string.session_action_decision_decline,
            R.string.session_action_risk_destructive_command,
            R.string.session_action_risk_broad_filesystem,
            R.string.session_action_risk_credential_access,
            R.string.session_action_risk_network_expansion,
            R.string.session_action_risk_external_tool,
        )

        val PRESENTATION_FALLBACK_RESOURCES = listOf(
            R.string.connection_failure_profile_preparation,
            R.string.session_action_title_review_required,
            R.string.session_question_prompt_fallback,
        )

        val WEAR_INSTALL_RESOURCES = listOf(
            R.string.wear_install_offer_explanation,
            R.string.wear_install_action,
            R.string.wear_install_not_now,
            R.string.wear_install_progress_explanation,
            R.string.wear_install_cancel,
            R.string.wear_install_recovery_authorization,
            R.string.wear_install_recovery_installation,
            R.string.wear_install_recovery_connection,
            R.string.wear_install_retry,
        )

        val COORDINATOR_ISSUE_RESOURCES = mapOf(
            R.string.session_issue_profile_discovery to listOf("Provider 42"),
            R.string.session_issue_connection_setup to listOf("Connection 42"),
            R.string.session_issue_provider_synchronization to
                listOf("Agent 42", "Connection 42"),
            R.string.session_issue_session_persistence to listOf("Agent 42"),
        )

        val ACTIVITY_SUMMARY_RESOURCES = mapOf(
            R.string.session_activity_summary_new_agent_output to emptyList(),
            R.string.session_activity_summary_tool_failed to emptyList(),
            R.string.session_activity_summary_named_tool_failed to listOf("Tool 42"),
            R.string.session_activity_summary_agent_turn_completed to emptyList(),
            R.string.session_activity_summary_agent_turn_failed to emptyList(),
            R.string.session_activity_summary_agent_provider_failed to emptyList(),
            R.string.session_activity_summary_agent_question_requires_answer to emptyList(),
            R.string.session_activity_summary_agent_approval_required to emptyList(),
            R.string.session_activity_summary_connection_reconnected to listOf("Connection 42"),
        )

        val SSH_PROFILE_RESOURCES = listOf(
            R.string.ssh_profile_title_add,
            R.string.ssh_profile_title_edit,
            R.string.ssh_profile_field_name,
            R.string.ssh_profile_field_host,
            R.string.ssh_profile_field_port,
            R.string.ssh_profile_field_username,
            R.string.ssh_profile_field_jump_host,
            R.string.ssh_profile_field_authentication,
            R.string.ssh_profile_field_password,
            R.string.ssh_profile_field_private_key,
            R.string.ssh_profile_field_passphrase_mode,
            R.string.ssh_profile_field_new_passphrase,
            R.string.ssh_profile_field_public_key,
            R.string.ssh_profile_support_name,
            R.string.ssh_profile_support_jump_host,
            R.string.ssh_profile_support_password,
            R.string.ssh_profile_support_private_key,
            R.string.ssh_profile_support_passphrase,
            R.string.ssh_profile_support_public_key,
            R.string.ssh_profile_public_key_pending,
            R.string.ssh_profile_option_direct,
            R.string.ssh_profile_passphrase_keep,
            R.string.ssh_profile_passphrase_none,
            R.string.ssh_profile_passphrase_replace,
            R.string.ssh_profile_auth_password,
            R.string.ssh_profile_auth_imported_key,
            R.string.ssh_profile_auth_keystore_key,
            R.string.ssh_profile_auth_password_support,
            R.string.ssh_profile_auth_imported_key_support,
            R.string.ssh_profile_auth_keystore_key_support,
            R.string.ssh_profile_operation_install_key,
            R.string.ssh_profile_operation_install_key_support,
            R.string.ssh_profile_operation_install_key_title,
            R.string.ssh_profile_operation_install_key_message,
            R.string.ssh_profile_operation_verify_key,
            R.string.ssh_profile_operation_verify_key_support,
            R.string.profile_error_provider_unavailable,
            R.string.profile_error_profile_unavailable,
            R.string.profile_error_validation,
            R.string.profile_error_save,
            R.string.profile_error_save_refresh,
            R.string.profile_error_operation,
            R.string.profile_error_operation_timeout,
            R.string.profile_error_operation_refresh,
            R.string.profile_error_delete,
            R.string.profile_error_open,
        )

        val MAIN_ERROR_RESOURCES = listOf(
            R.string.main_error_profiles_refresh,
            R.string.main_error_connection_open,
            R.string.main_error_connection_close,
            R.string.main_error_identity_decision,
            R.string.main_error_secure_state_open,
            R.string.main_error_session_unavailable,
            R.string.main_error_session_read_save,
            R.string.main_error_session_draft_save,
            R.string.main_error_session_message_required,
            R.string.main_error_session_steer,
            R.string.main_error_session_send,
            R.string.main_error_session_draft_clear,
            R.string.main_error_session_resume,
            R.string.main_error_session_interrupt,
            R.string.main_error_agent_endpoint_unavailable,
            R.string.main_error_session_start,
            R.string.main_error_action_unavailable,
            R.string.main_error_action_delivery_uncertain,
            R.string.main_error_action_audit,
            R.string.main_error_action_response,
        )

        val ARTIFACT_RESOURCES = listOf(
            R.string.artifact_error_refresh,
            R.string.artifact_error_unavailable,
            R.string.artifact_error_save_in_progress,
            R.string.artifact_error_save,
            R.string.session_artifact_availability_reconnect,
            R.string.session_artifact_availability_unsupported,
            R.string.session_artifact_availability_ready,
            R.string.session_artifact_availability_deleted,
            R.string.session_artifact_availability_outside_workspace,
            R.string.session_artifact_availability_workspace_unknown,
            R.string.session_artifact_change_added,
            R.string.session_artifact_change_modified,
            R.string.session_artifact_change_deleted,
            R.string.session_artifact_change_renamed,
            R.string.session_artifact_change_unknown,
            R.string.session_artifact_path_deleted,
            R.string.session_artifact_path_outside_workspace,
            R.string.session_artifact_path_workspace_unknown,
        )

        val SPEECH_ERROR_RESOURCES = listOf(
            R.string.speech_error_microphone_permission,
            R.string.speech_error_transcript_unavailable,
            R.string.speech_error_session_unavailable,
            R.string.speech_error_transcript_changed,
            R.string.speech_error_model_install,
            R.string.speech_error_model_download_cancel,
            R.string.speech_error_model_required,
            R.string.speech_error_start,
            R.string.speech_error_stop,
            R.string.speech_error_previous_session_cancel,
            R.string.speech_error_cancel,
        )

        val SPEECH_STATUS_RESOURCES = listOf(
            R.string.speech_status_waiting,
            R.string.speech_status_starting,
            R.string.speech_status_listening,
            R.string.speech_status_transcribing,
            R.string.speech_status_review_transcript,
            R.string.speech_status_no_model,
            R.string.speech_status_install_model,
            R.string.speech_status_downloading_model,
            R.string.speech_status_ready_private,
            R.string.speech_status_unavailable_build,
        )

        val EXPECTED_GENERATED_LOCALES = setOf(
            "en-US",
            "ar",
            "bn",
            "de",
            "es",
            "fr",
            "hi",
            "in",
            "it",
            "ja",
            "pt-BR",
            "ru",
            "zh-CN",
            "zh-TW",
            "en-XA",
            "ar-XB",
        )

        val EXPECTED_ALLOW_LABELS = mapOf(
            "de" to "Benachrichtigungen erlauben",
            "zh-CN" to "允许通知",
            "zh-TW" to "允許通知",
            "ru" to "Разрешить уведомления",
            "es" to "Permitir notificaciones",
            "it" to "Consenti notifiche",
            "fr" to "Autoriser les notifications",
            "pt-BR" to "Permitir notificações",
            "hi" to "सूचनाओं की अनुमति दें",
            "ar" to "السماح بالإشعارات",
            "bn" to "বিজ্ঞপ্তির অনুমতি দিন",
            "id" to "Izinkan notifikasi",
            "ja" to "通知を許可",
        )
    }
}
