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
            val risk = context.getString(
                R.string.session_hub_risk,
                opaqueValues[0],
            )
            val downloadProgress = context.getString(R.string.speech_download_progress, 42)

            opaqueValues.forEach { value ->
                assertTrue(languageTag, sessionContext.contains(value))
            }
            assertTrue(languageTag, selectedModel.contains("model.test"))
            assertTrue(languageTag, addProfile.contains(opaqueValues[0]))
            assertTrue(languageTag, startOnConnection.contains(opaqueValues[2]))
            assertTrue(languageTag, startOnConnection.contains(opaqueValues[1]))
            assertTrue(languageTag, reviewIn.contains(opaqueValues[1]))
            opaqueValues.forEach { value ->
                assertTrue(languageTag, actionContext.contains(value))
            }
            assertTrue(languageTag, risk.contains(opaqueValues[0]))
            assertFalse(languageTag, actionContext.contains("%1\$"))
            assertFalse(languageTag, risk.contains("%1\$"))
            assertFalse(languageTag, addProfile.contains("%1\$"))
            assertFalse(languageTag, startOnConnection.contains("%1\$"))
            val localizedNumber = NumberFormat
                .getIntegerInstance(Locale.forLanguageTag(languageTag))
                .format(42)
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
