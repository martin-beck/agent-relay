/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import android.content.Context
import android.os.Looper
import android.content.res.Configuration
import android.text.TextUtils
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.agentrelay.background.BackgroundTransportState
import com.example.agentrelay.theme.AgentRelayTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.InternalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.agentrelay.connection.api.ConnectionProfileFieldType
import dev.agentrelay.ssh.api.SshConnectionProfileSchema
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "420dpi")
@LooperMode(LooperMode.Mode.PAUSED)
class MainScreenScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var originalLocale: Locale
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun configureDeterministicDateFormatting() {
        originalLocale = Locale.getDefault()
        originalTimeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreDateFormatting() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun loadingCompactLight() {
        capture(
            name = "main_loading_compact_light",
            widthDp = 360,
            heightDp = 780,
            state = MainScreenUiState.Loading,
        )
    }

    @Test
    fun fatalErrorCompactDarkLargeText() {
        capture(
            name = "main_error_compact_dark_large_text",
            widthDp = 360,
            heightDp = 780,
            fontScale = 1.5f,
            darkTheme = true,
            state = MainScreenUiState.FatalError(
                UiMessage.Verbatim(
                    "The encrypted session store could not be opened. Retry after the device is unlocked.",
                ),
            ),
        )
    }

    @Test
    fun emptyMediumLight() {
        capture(
            name = "main_empty_medium_light",
            widthDp = 700,
            heightDp = 900,
            state = MainScreenUiState.Ready(previewEmptyHub()),
        )
    }

    @Test
    fun contentCompactLight() {
        capture(
            name = "main_content_compact_light",
            widthDp = 360,
            heightDp = 800,
            state = MainScreenUiState.Ready(previewHub()),
        )
    }

    @Test
    fun backgroundActiveCompactLight() {
        capture(
            name = "main_background_active_compact_light",
            widthDp = 360,
            heightDp = 800,
            state = MainScreenUiState.Ready(previewHub()),
            backgroundTransportState = BackgroundTransportState.ACTIVE,
        )
    }

    @Test
    fun longContentCompactLightLargeText() {
        capture(
            name = "main_long_content_compact_light_large_text",
            widthDp = 360,
            heightDp = 800,
            fontScale = 1.3f,
            state = MainScreenUiState.Ready(previewHub(longContent = true)),
        )
    }

    @Test
    fun localeEnglishCompactLargeText() = captureBundledLocale("en-US", "en_us")

    @Test
    fun localeGermanCompactLargeText() = captureBundledLocale("de", "de")

    @Test
    fun localeSimplifiedChineseCompactLargeText() = captureBundledLocale("zh-CN", "zh_cn")

    @Test
    fun localeTraditionalChineseCompactLargeText() = captureBundledLocale("zh-TW", "zh_tw")

    @Test
    fun localeRussianCompactLargeText() = captureBundledLocale("ru", "ru")

    @Test
    fun localeSpanishCompactLargeText() = captureBundledLocale("es", "es")

    @Test
    fun localeItalianCompactLargeText() = captureBundledLocale("it", "it")

    @Test
    fun localeFrenchCompactLargeText() = captureBundledLocale("fr", "fr")

    @Test
    fun localeBrazilianPortugueseCompactLargeText() = captureBundledLocale("pt-BR", "pt_br")

    @Test
    fun localeHindiCompactLargeText() = captureBundledLocale("hi", "hi")

    @Test
    fun localeArabicCompactLargeText() = captureBundledLocale("ar", "ar")

    @Test
    fun localeBengaliCompactLargeText() = captureBundledLocale("bn", "bn")

    @Test
    fun localeIndonesianCompactLargeText() = captureBundledLocale("id", "id")

    @Test
    fun localeJapaneseCompactLargeText() = captureBundledLocale("ja", "ja")

    @Test
    fun localeAccentedEnglishCompactLargeText() = captureBundledLocale("en-XA", "en_xa")

    @Test
    fun localeBidirectionalArabicCompactLargeText() = captureBundledLocale("ar-XB", "ar_xb")

    @Test
    fun profileLocaleEnglishCompactLargeText() = captureProfileBundledLocale("en-US", "en_us")

    @Test
    fun profileLocaleGermanCompactLargeText() = captureProfileBundledLocale("de", "de")

    @Test
    fun profileLocaleSimplifiedChineseCompactLargeText() =
        captureProfileBundledLocale("zh-CN", "zh_cn")

    @Test
    fun profileLocaleTraditionalChineseCompactLargeText() =
        captureProfileBundledLocale("zh-TW", "zh_tw")

    @Test
    fun profileLocaleRussianCompactLargeText() = captureProfileBundledLocale("ru", "ru")

    @Test
    fun profileLocaleSpanishCompactLargeText() = captureProfileBundledLocale("es", "es")

    @Test
    fun profileLocaleItalianCompactLargeText() = captureProfileBundledLocale("it", "it")

    @Test
    fun profileLocaleFrenchCompactLargeText() = captureProfileBundledLocale("fr", "fr")

    @Test
    fun profileLocaleBrazilianPortugueseCompactLargeText() =
        captureProfileBundledLocale("pt-BR", "pt_br")

    @Test
    fun profileLocaleHindiCompactLargeText() = captureProfileBundledLocale("hi", "hi")

    @Test
    fun profileLocaleArabicCompactLargeText() = captureProfileBundledLocale("ar", "ar")

    @Test
    fun profileLocaleBengaliCompactLargeText() = captureProfileBundledLocale("bn", "bn")

    @Test
    fun profileLocaleIndonesianCompactLargeText() = captureProfileBundledLocale("id", "id")

    @Test
    fun profileLocaleJapaneseCompactLargeText() = captureProfileBundledLocale("ja", "ja")

    @Test
    fun profileLocaleAccentedEnglishCompactLargeText() =
        captureProfileBundledLocale("en-XA", "en_xa")

    @Test
    fun profileLocaleBidirectionalArabicCompactLargeText() =
        captureProfileBundledLocale("ar-XB", "ar_xb")

    @Test
    fun profilePasswordAccentedEnglishLargeText() = captureProfileCredentialBranch(
        languageTag = "en-XA",
        fileName = "en_xa",
        scenario = "password",
        editor = screenshotPasswordEditor(),
        expectedResources = listOf(
            com.example.agentrelay.R.string.ssh_profile_field_authentication,
            com.example.agentrelay.R.string.ssh_profile_field_password,
        ),
        selectedOptionResources = listOf(
            com.example.agentrelay.R.string.ssh_profile_auth_password,
        ),
    )

    @Test
    fun profilePasswordBidirectionalArabicLargeText() = captureProfileCredentialBranch(
        languageTag = "ar-XB",
        fileName = "ar_xb",
        scenario = "password",
        editor = screenshotPasswordEditor(),
        expectedResources = listOf(
            com.example.agentrelay.R.string.ssh_profile_field_authentication,
            com.example.agentrelay.R.string.ssh_profile_field_password,
        ),
        selectedOptionResources = listOf(
            com.example.agentrelay.R.string.ssh_profile_auth_password,
        ),
    )

    @Test
    fun profileImportedKeyAccentedEnglishLargeText() = captureProfileCredentialBranch(
        languageTag = "en-XA",
        fileName = "en_xa",
        scenario = "imported_key",
        editor = screenshotImportedKeyEditor(),
        expectedResources = listOf(
            com.example.agentrelay.R.string.ssh_profile_field_authentication,
            com.example.agentrelay.R.string.ssh_profile_field_private_key,
        ),
        selectedOptionResources = listOf(
            com.example.agentrelay.R.string.ssh_profile_auth_imported_key,
        ),
    )

    @Test
    fun profileImportedKeyBidirectionalArabicLargeText() = captureProfileCredentialBranch(
        languageTag = "ar-XB",
        fileName = "ar_xb",
        scenario = "imported_key",
        editor = screenshotImportedKeyEditor(),
        expectedResources = listOf(
            com.example.agentrelay.R.string.ssh_profile_field_authentication,
            com.example.agentrelay.R.string.ssh_profile_field_private_key,
        ),
        selectedOptionResources = listOf(
            com.example.agentrelay.R.string.ssh_profile_auth_imported_key,
        ),
    )

    @Test
    fun profilePassphraseAccentedEnglishLargeText() = captureProfileCredentialBranch(
        languageTag = "en-XA",
        fileName = "en_xa",
        scenario = "passphrase",
        editor = screenshotPassphraseEditor(),
        expectedResources = listOf(
            com.example.agentrelay.R.string.ssh_profile_field_passphrase_mode,
            com.example.agentrelay.R.string.ssh_profile_field_new_passphrase,
        ),
        selectedOptionResources = listOf(
            com.example.agentrelay.R.string.ssh_profile_auth_imported_key,
            com.example.agentrelay.R.string.ssh_profile_passphrase_replace,
        ),
        requiredEditableLabelStringResources = listOf(
            com.example.agentrelay.R.string.ssh_profile_field_new_passphrase,
        ),
    )

    @Test
    fun profilePassphraseBidirectionalArabicLargeText() = captureProfileCredentialBranch(
        languageTag = "ar-XB",
        fileName = "ar_xb",
        scenario = "passphrase",
        editor = screenshotPassphraseEditor(),
        expectedResources = listOf(
            com.example.agentrelay.R.string.ssh_profile_field_passphrase_mode,
            com.example.agentrelay.R.string.ssh_profile_field_new_passphrase,
        ),
        selectedOptionResources = listOf(
            com.example.agentrelay.R.string.ssh_profile_auth_imported_key,
            com.example.agentrelay.R.string.ssh_profile_passphrase_replace,
        ),
        requiredEditableLabelStringResources = listOf(
            com.example.agentrelay.R.string.ssh_profile_field_new_passphrase,
        ),
    )

    @Test
    fun contentExpandedLight() {
        capture(
            name = "main_content_expanded_light",
            widthDp = 1_000,
            heightDp = 720,
            state = MainScreenUiState.Ready(previewHub()),
        )
    }

    @Test
    fun approvalExpandedDark() {
        capture(
            name = "main_approval_expanded_dark",
            widthDp = 1_000,
            heightDp = 820,
            darkTheme = true,
            state = MainScreenUiState.Ready(previewHub(approvalRequired = true)),
        )
    }

    private fun captureBundledLocale(languageTag: String, fileName: String) {
        capture(
            name = "locale_${fileName}_content_compact_large_text",
            widthDp = 360,
            heightDp = 800,
            fontScale = 1.3f,
            state = MainScreenUiState.Ready(previewHub(approvalRequired = true)),
            languageTag = languageTag,
        )
    }

    private fun captureProfileBundledLocale(languageTag: String, fileName: String) {
        val validationMessage = localizedContext(languageTag).getString(
            com.example.agentrelay.R.string.profile_error_validation,
        )
        val profileEditor = screenshotEndpointValidationEditor().copy(
            fieldErrors = mapOf("profile-label" to validationMessage),
            error = UiMessage.Localized(
                com.example.agentrelay.R.string.profile_error_validation,
            ),
            notice = null,
        )
        capture(
            name = "locale_${fileName}_profile_validation_compact_large_text",
            widthDp = 360,
            heightDp = 800,
            fontScale = 1.3f,
            state = MainScreenUiState.Ready(previewHub()),
            profileEditor = profileEditor,
            languageTag = languageTag,
            clockSettlingSteps = 3,
            captureAllWindows = true,
            requiredVisibleStringResources = listOf(
                com.example.agentrelay.R.string.ssh_profile_title_edit,
                com.example.agentrelay.R.string.profile_error_validation,
            ),
            requiredEditableLabelStringResources = listOf(
                com.example.agentrelay.R.string.ssh_profile_field_name,
                com.example.agentrelay.R.string.ssh_profile_field_host,
                com.example.agentrelay.R.string.ssh_profile_field_port,
            ),
            verifyProfileActionOrder = languageTag == "ar-XB",
        )
        capture(
            name = "locale_${fileName}_profile_managed_key_compact_large_text",
            widthDp = 360,
            heightDp = 800,
            fontScale = 1.3f,
            state = MainScreenUiState.Ready(previewHub()),
            profileEditor = screenshotManagedKeyEditor(),
            languageTag = languageTag,
            clockSettlingSteps = 3,
            captureAllWindows = true,
            requiredVisibleStringResources = listOf(
                com.example.agentrelay.R.string.ssh_profile_title_edit,
                com.example.agentrelay.R.string.ssh_profile_field_public_key,
                com.example.agentrelay.R.string.ssh_profile_operation_install_key,
                com.example.agentrelay.R.string.ssh_profile_operation_verify_key,
            ),
        )
    }

    private fun captureProfileCredentialBranch(
        languageTag: String,
        fileName: String,
        scenario: String,
        editor: ConnectionProfileEditorUiState.Editing,
        expectedResources: List<Int>,
        selectedOptionResources: List<Int>,
        requiredEditableLabelStringResources: List<Int> = emptyList(),
    ) {
        capture(
            name = "locale_${fileName}_profile_${scenario}_compact_large_text",
            widthDp = 360,
            heightDp = 1_200,
            fontScale = 1.3f,
            state = MainScreenUiState.Ready(previewHub()),
            profileEditor = editor,
            languageTag = languageTag,
            clockSettlingSteps = 3,
            captureAllWindows = true,
            requiredVisibleStringResources =
            listOf(com.example.agentrelay.R.string.ssh_profile_title_edit) +
                expectedResources,
            requiredEditableLabelStringResources = requiredEditableLabelStringResources,
            requiredSelectedOptionStringResources = selectedOptionResources,
        )
    }

    @OptIn(ExperimentalRoborazziApi::class, InternalRoborazziApi::class)
    private fun capture(
        name: String,
        widthDp: Int,
        heightDp: Int,
        state: MainScreenUiState,
        fontScale: Float = 1f,
        darkTheme: Boolean = false,
        backgroundTransportState: BackgroundTransportState = BackgroundTransportState.STOPPED,
        languageTag: String? = null,
        clockSettlingSteps: Int = 1,
        captureAllWindows: Boolean = false,
        profileEditor: ConnectionProfileEditorUiState? = null,
        requiredVisibleStringResources: List<Int> = listOf(
            com.example.agentrelay.R.string.background_transport_title,
            com.example.agentrelay.R.string.app_name,
        ),
        requiredEditableLabelStringResources: List<Int> = emptyList(),
        requiredSelectedOptionStringResources: List<Int> = emptyList(),
        verifyProfileActionOrder: Boolean = false,
        afterSetContent: () -> Unit = {},
    ) {
        RuntimeEnvironment.setQualifiers("w${widthDp}dp-h${heightDp}dp-420dpi")
        val renderContext = localizedContext(languageTag)
        val renderConfiguration = Configuration(renderContext.resources.configuration)
        val layoutDirection = if (
            renderConfiguration.layoutDirection == View.LAYOUT_DIRECTION_RTL
        ) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }
        languageTag?.let { tag ->
            val localeDirection = if (
                TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag(tag)) ==
                View.LAYOUT_DIRECTION_RTL
            ) {
                LayoutDirection.Rtl
            } else {
                LayoutDirection.Ltr
            }
            assertEquals("$tag configuration must use its locale layout direction", localeDirection, layoutDirection)
        }
        updateActivityConfiguration(renderConfiguration, layoutDirection)
        composeTestRule.mainClock.autoAdvance = false
        composeTestRule.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides renderContext,
                LocalConfiguration provides renderConfiguration,
                LocalLayoutDirection provides layoutDirection,
                LocalResources provides renderContext.resources,
                LocalDensity provides Density(
                    density = density.density,
                    fontScale = fontScale,
                ),
            ) {
                AgentRelayTheme(
                    darkTheme = darkTheme,
                    dynamicColor = false,
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        if (profileEditor == null) {
                            MainScreenContent(
                                state = state,
                                actions = previewActions(),
                                backgroundTransportState = backgroundTransportState,
                            )
                        } else {
                            ConnectionProfileEditorDialog(
                                state = profileEditor,
                                actions = previewActions(),
                            )
                        }
                    }
                }
            }
        }
        afterSetContent()
        repeat(clockSettlingSteps) {
            composeTestRule.mainClock.advanceTimeBy(SCREENSHOT_CLOCK_MILLIS)
        }
        shadowOf(Looper.getMainLooper()).idle()
        val screenshotOptions = RoborazziOptions(
            compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f),
            recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5),
        )
        if (captureAllWindows) {
            val composeRoots = ProfileScreenshotAssertions.composeRoots()
            checkNotNull(languageTag)
            captureScreenRoboImage(
                filePath = "src/test/screenshots/$name.png",
                roborazziOptions = screenshotOptions,
            )
            ProfileScreenshotAssertions.assertWindowAccessibilityBounds(
                composeRoots = composeRoots,
                languageTag = languageTag,
                renderContext = renderContext,
                expectedLayoutDirection = layoutDirection,
                requiredVisibleStringResources = requiredVisibleStringResources,
                requiredEditableLabelStringResources = requiredEditableLabelStringResources,
                requiredSelectedOptionStringResources = requiredSelectedOptionStringResources,
            )
            if (verifyProfileActionOrder) {
                ProfileScreenshotAssertions.assertProfileActionOrder(
                    languageTag = languageTag,
                    renderContext = renderContext,
                    composeRoots = composeRoots,
                )
            }
        } else {
            composeTestRule.waitForIdle()
            languageTag?.let {
                requiredVisibleStringResources.forEach { stringResource ->
                    composeTestRule
                        .onNodeWithText(renderContext.getString(stringResource))
                        .assertIsDisplayed()
                }
                assertTextDoesNotOverflowHorizontally(it)
            }
            composeTestRule.onRoot().captureRoboImage(
                filePath = "src/test/screenshots/$name.png",
                roborazziOptions = screenshotOptions,
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun updateActivityConfiguration(
        configuration: Configuration,
        layoutDirection: LayoutDirection,
    ) {
        val activity = composeTestRule.activity
        activity.resources.updateConfiguration(
            configuration,
            activity.resources.displayMetrics,
        )
        activity.window.decorView.layoutDirection = if (layoutDirection == LayoutDirection.Rtl) {
            View.LAYOUT_DIRECTION_RTL
        } else {
            View.LAYOUT_DIRECTION_LTR
        }
    }

    private fun localizedContext(languageTag: String?): Context {
        val activity = composeTestRule.activity
        if (languageTag == null) return activity
        val locale = Locale.forLanguageTag(languageTag)
        Locale.setDefault(locale)
        val configuration = Configuration(activity.resources.configuration)
        configuration.setLocale(locale)
        return activity.createConfigurationContext(configuration)
    }

    private fun assertTextDoesNotOverflowHorizontally(languageTag: String) {
        val root = composeTestRule.onRoot().fetchSemanticsNode().boundsInRoot
        val textNodes = composeTestRule
            .onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
                useUnmergedTree = true,
            )
            .fetchSemanticsNodes()
            .filter { node -> node.layoutInfo.isPlaced && node.boundsInRoot.width > 0f }
        assertTrue("$languageTag must expose text semantics", textNodes.isNotEmpty())
        textNodes.forEach { node ->
            val left = node.layoutInfo.coordinates.localToRoot(Offset.Zero).x
            val right = left + node.layoutInfo.width
            assertTrue(
                "$languageTag text overflows horizontally: [$left, $right] outside $root",
                left >= root.left - BOUNDS_TOLERANCE_PX &&
                    right <= root.right + BOUNDS_TOLERANCE_PX,
            )
            ProfileScreenshotAssertions.assertTextLayoutDoesNotOverflow(languageTag, node)
        }
    }

    private companion object {
        const val SCREENSHOT_CLOCK_MILLIS = 1_000L
        const val BOUNDS_TOLERANCE_PX = 1f
    }
}

private fun screenshotEndpointValidationEditor() = screenshotProfileEditor(
    fields = listOf(
        screenshotField(
            id = SshConnectionProfileSchema.PROFILE_LABEL.value,
            label = "Profile name",
            type = ConnectionProfileFieldType.TEXT,
            value = "Development server",
            supportingText = "Shown in the connection list.",
            required = true,
            maxLength = 128,
        ),
        screenshotField(
            id = SshConnectionProfileSchema.HOST.value,
            label = "Host",
            type = ConnectionProfileFieldType.TEXT,
            value = "example.test",
            required = true,
            maxLength = 253,
        ),
        screenshotField(
            id = SshConnectionProfileSchema.PORT.value,
            label = "Port",
            type = ConnectionProfileFieldType.PORT,
            value = "22",
            required = true,
            maxLength = 5,
        ),
    ),
)

private fun screenshotManagedKeyEditor() = screenshotProfileEditor(
    fields = listOf(
        screenshotField(
            id = SshConnectionProfileSchema.PUBLIC_KEY.value,
            label = "App-managed public key",
            type = ConnectionProfileFieldType.READ_ONLY,
            supportingText =
            "The private key stays in Android Keystore. Install this public key on the remote account.",
            maxLength = 16_384,
        ),
    ),
    operations = screenshotProfileOperations(),
)

private fun screenshotPasswordEditor() = screenshotProfileEditor(
    fields = listOf(
        authenticationField(SshConnectionProfileSchema.AUTHENTICATION_PASSWORD),
        screenshotField(
            id = SshConnectionProfileSchema.PASSWORD.value,
            label = "Password",
            type = ConnectionProfileFieldType.PASSWORD,
            supportingText = "Enter a replacement password.",
            maxLength = 16_384,
            visibleWhen = listOf(authenticationCondition("password")),
            hasStoredSecret = true,
        ),
        screenshotField(
            id = SshConnectionProfileSchema.PRIVATE_KEY.value,
            label = "Private key",
            type = ConnectionProfileFieldType.MULTILINE_SECRET,
            maxLength = 4 * 1024 * 1024,
            visibleWhen = listOf(authenticationCondition("imported-key")),
        ),
    ),
)

private fun screenshotImportedKeyEditor() = screenshotProfileEditor(
    fields = listOf(
        authenticationField(
            value = SshConnectionProfileSchema.AUTHENTICATION_IMPORTED_KEY,
            options = listOf(
                screenshotOption(
                    value = SshConnectionProfileSchema.AUTHENTICATION_IMPORTED_KEY,
                    label = "Imported private key",
                    supportingText = "Paste an OpenSSH or PEM private key.",
                ),
            ),
        ),
        screenshotField(
            id = SshConnectionProfileSchema.PASSWORD.value,
            label = "Password",
            type = ConnectionProfileFieldType.PASSWORD,
            maxLength = 16_384,
            visibleWhen = listOf(authenticationCondition("password")),
        ),
        screenshotField(
            id = SshConnectionProfileSchema.PRIVATE_KEY.value,
            label = "Private key",
            type = ConnectionProfileFieldType.MULTILINE_SECRET,
            supportingText = "Paste a replacement private key.",
            maxLength = 4 * 1024 * 1024,
            visibleWhen = listOf(authenticationCondition("imported-key")),
            hasStoredSecret = true,
        ),
    ),
)

private fun screenshotPassphraseEditor() = screenshotProfileEditor(
    fields = listOf(
        authenticationField(
            value = SshConnectionProfileSchema.AUTHENTICATION_IMPORTED_KEY,
            options = listOf(
                screenshotOption(
                    value = SshConnectionProfileSchema.AUTHENTICATION_IMPORTED_KEY,
                    label = "Imported private key",
                ),
            ),
        ),
        screenshotField(
            id = SshConnectionProfileSchema.PASSPHRASE_MODE.value,
            label = "Private-key passphrase",
            type = ConnectionProfileFieldType.SINGLE_CHOICE,
            value = SshConnectionProfileSchema.PASSPHRASE_REPLACE,
            required = true,
            maxLength = 16,
            options = listOf(
                screenshotOption(
                    value = SshConnectionProfileSchema.PASSPHRASE_REPLACE,
                    label = "Set a new passphrase",
                ),
            ),
            visibleWhen = listOf(authenticationCondition("imported-key")),
        ),
        screenshotField(
            id = SshConnectionProfileSchema.PASSPHRASE.value,
            label = "New private-key passphrase",
            type = ConnectionProfileFieldType.PASSWORD,
            supportingText = "Encrypted separately from the private key.",
            required = true,
            maxLength = 16_384,
            visibleWhen = listOf(
                authenticationCondition("imported-key"),
                screenshotCondition(
                    SshConnectionProfileSchema.PASSPHRASE_MODE.value,
                    SshConnectionProfileSchema.PASSPHRASE_REPLACE,
                ),
            ),
        ),
    ),
)

private fun authenticationField(
    value: String,
    options: List<ConnectionProfileFieldOptionUiModel> = listOf(
        screenshotOption("password", "Password", "Encrypted in protected app storage."),
        screenshotOption("imported-key", "Imported private key", "Paste an OpenSSH or PEM private key."),
        screenshotOption("agent-backed", "Android Keystore key", "The private key never leaves Android Keystore."),
    ),
) = screenshotField(
    id = SshConnectionProfileSchema.AUTHENTICATION.value,
    label = "Authentication",
    type = ConnectionProfileFieldType.SINGLE_CHOICE,
    value = value,
    required = true,
    maxLength = 32,
    options = options,
)

private fun authenticationCondition(expected: String) = screenshotCondition(
    SshConnectionProfileSchema.AUTHENTICATION.value,
    expected,
)

private fun screenshotCondition(
    fieldId: String,
    expectedValue: String,
) = ConnectionProfileFieldConditionUiModel(fieldId, expectedValue)

private fun screenshotOption(
    value: String,
    label: String,
    supportingText: String? = null,
) = ConnectionProfileFieldOptionUiModel(value, label, supportingText)

private fun screenshotProfileEditor(
    fields: List<ConnectionProfileFieldUiModel>,
    operations: List<ConnectionProfileOperationUiModel> = emptyList(),
) = ConnectionProfileEditorUiState.Editing(
    providerId = "ssh.secure-shell",
    profileId = "screenshot-profile",
    title = "Edit Secure Shell profile",
    fields = fields,
    operations = operations,
    canDelete = true,
)

private fun screenshotField(
    id: String,
    label: String,
    type: ConnectionProfileFieldType,
    value: String = "",
    supportingText: String? = null,
    required: Boolean = false,
    maxLength: Int,
    options: List<ConnectionProfileFieldOptionUiModel> = emptyList(),
    visibleWhen: List<ConnectionProfileFieldConditionUiModel> = emptyList(),
    hasStoredSecret: Boolean = false,
) = ConnectionProfileFieldUiModel(
    id = id,
    label = label,
    type = type,
    value = value,
    supportingText = supportingText,
    required = required,
    maxLength = maxLength,
    options = options,
    visibleWhen = visibleWhen,
    hasStoredSecret = hasStoredSecret,
)

private fun screenshotProfileOperations() = listOf(
    ConnectionProfileOperationUiModel(
        id = SshConnectionProfileSchema.INSTALL_PUBLIC_KEY.value,
        label = "Install public key",
        supportingText = "Install the app-managed public key on the remote account.",
        confirmationTitle = "Install public key on this remote account?",
        confirmationMessage =
        "Agent Relay will add only the displayed public key to the remote account.",
    ),
    ConnectionProfileOperationUiModel(
        id = SshConnectionProfileSchema.VERIFY_KEY_LOGIN.value,
        label = "Test key-only login",
        supportingText = "Confirm passwordless app-managed key login works.",
        confirmationTitle = null,
        confirmationMessage = null,
    ),
)
