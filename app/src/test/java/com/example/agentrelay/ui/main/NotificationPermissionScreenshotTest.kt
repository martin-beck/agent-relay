/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.text.TextUtils
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.agentrelay.R
import com.example.agentrelay.background.BackgroundTransportState
import com.example.agentrelay.notifications.SessionNotificationPermissionState
import com.example.agentrelay.notifications.resolveSessionNotificationPermissionState
import com.example.agentrelay.theme.AgentRelayTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "420dpi")
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalRoborazziApi::class)
class NotificationPermissionScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var originalLocale: Locale
    private lateinit var originalTimeZone: TimeZone

    @Before
    fun configureDeterministicFormatting() {
        originalLocale = Locale.getDefault()
        originalTimeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreFormatting() {
        Locale.setDefault(originalLocale)
        TimeZone.setDefault(originalTimeZone)
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

    private fun captureBundledLocale(languageTag: String, fileName: String) {
        var permissionRequests = 0
        var settingsRequests = 0
        val content = setPermissionContent(
            languageTag = languageTag,
            onRequestPermission = { state ->
                permissionRequests += 1
                state.value = resolveSessionNotificationPermissionState(
                    runtimePermissionRequired = true,
                    permissionGranted = false,
                    requestedBefore = true,
                    shouldShowRationale = false,
                )
            },
            onOpenSettings = { settingsRequests += 1 },
        )
        val requestStrings = requestStrings(content.renderContext)
        assertPermissionSemantics(languageTag, content.layoutDirection, requestStrings)
        capture("locale_${fileName}_notification_request_compact_large_text.png")

        clickAndSettle(requestStrings.action)
        assertEquals("$languageTag must issue one notification permission request", 1, permissionRequests)
        composeTestRule.onNodeWithText(requestStrings.explanation).assertDoesNotExist()

        val settingsStrings = settingsStrings(content.renderContext)
        assertPermissionSemantics(languageTag, content.layoutDirection, settingsStrings)
        capture("locale_${fileName}_notification_settings_compact_large_text.png")
        clickAndSettle(settingsStrings.action)
        assertEquals("$languageTag must issue one notification settings request", 1, settingsRequests)
    }

    private fun assertPermissionSemantics(
        languageTag: String,
        layoutDirection: LayoutDirection,
        strings: PermissionStrings,
    ) {
        val title = composeTestRule.onNodeWithText(strings.title)
        val explanation = composeTestRule.onNodeWithText(strings.explanation)
        val action = composeTestRule.onNodeWithText(strings.action)
        val backgroundTitle = composeTestRule.onNodeWithText(strings.backgroundTitle)
        val backgroundExplanation = composeTestRule.onNodeWithText(strings.backgroundExplanation)
        val backgroundAction = composeTestRule.onNodeWithText(strings.backgroundAction)
        val permissionCard = composeTestRule.onNodeWithTag(NOTIFICATION_PERMISSION_TEST_TAG)
        val backgroundCard = composeTestRule.onNodeWithTag(BACKGROUND_TRANSPORT_TEST_TAG)

        assertFullyContained(
            languageTag,
            layoutDirection,
            listOf(
                permissionCard,
                title,
                explanation,
                action,
                backgroundCard,
                backgroundTitle,
                backgroundExplanation,
                backgroundAction,
            ),
        )
        action.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertIsEnabled()
        backgroundAction.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertIsNotEnabled()
        assertTouchTarget(languageTag, action)
        assertTouchTarget(languageTag, backgroundAction)
        assertEndAligned(languageTag, layoutDirection, permissionCard, action)
        assertEndAligned(languageTag, layoutDirection, backgroundCard, backgroundAction)
        assertVisibleTextGeometry(languageTag)
    }

    private fun assertFullyContained(
        languageTag: String,
        layoutDirection: LayoutDirection,
        interactions: List<SemanticsNodeInteraction>,
    ) {
        val root = composeTestRule.onRoot().fetchSemanticsNode()
        val rootOrigin = root.layoutInfo.coordinates.localToRoot(Offset.Zero)
        val rootRight = rootOrigin.x + root.layoutInfo.width
        val rootBottom = rootOrigin.y + root.layoutInfo.height
        interactions.forEach { interaction ->
            val node = interaction.assertIsDisplayed().fetchSemanticsNode()
            val origin = node.layoutInfo.coordinates.localToRoot(Offset.Zero)
            val right = origin.x + node.layoutInfo.width
            val bottom = origin.y + node.layoutInfo.height
            assertEquals(
                "$languageTag required permission content must use $layoutDirection",
                layoutDirection,
                node.layoutInfo.layoutDirection,
            )
            assertTrue(
                "$languageTag required permission content is cut off: " +
                    "[$origin, $right, $bottom] outside " +
                    "[$rootOrigin, $rootRight, $rootBottom]",
                origin.x >= rootOrigin.x - BOUNDS_TOLERANCE_PX &&
                    right <= rootRight + BOUNDS_TOLERANCE_PX &&
                    origin.y >= rootOrigin.y - BOUNDS_TOLERANCE_PX &&
                    bottom <= rootBottom + BOUNDS_TOLERANCE_PX,
            )
        }
    }

    private fun assertTouchTarget(languageTag: String, interaction: SemanticsNodeInteraction) {
        val node = interaction.fetchSemanticsNode()
        val minimumPixels = MINIMUM_TOUCH_TARGET_DP *
            composeTestRule.activity.resources.displayMetrics.density
        assertTrue(
            "$languageTag action touch target is narrower than $MINIMUM_TOUCH_TARGET_DP dp",
            node.layoutInfo.width >= minimumPixels,
        )
        assertTrue(
            "$languageTag action touch target is shorter than $MINIMUM_TOUCH_TARGET_DP dp",
            node.layoutInfo.height >= minimumPixels,
        )
    }

    private fun assertEndAligned(
        languageTag: String,
        layoutDirection: LayoutDirection,
        card: SemanticsNodeInteraction,
        action: SemanticsNodeInteraction,
    ) {
        val cardNode = card.fetchSemanticsNode()
        val actionNode = action.fetchSemanticsNode()
        val cardOrigin = cardNode.layoutInfo.coordinates.localToRoot(Offset.Zero)
        val actionOrigin = actionNode.layoutInfo.coordinates.localToRoot(Offset.Zero)
        val endGap = if (layoutDirection == LayoutDirection.Rtl) {
            actionOrigin.x - cardOrigin.x
        } else {
            cardOrigin.x + cardNode.layoutInfo.width - actionOrigin.x - actionNode.layoutInfo.width
        }
        val expectedGap = CARD_CONTENT_PADDING_DP *
            composeTestRule.activity.resources.displayMetrics.density
        assertTrue(
            "$languageTag action must align to the $layoutDirection end: " +
                "$endGap px instead of $expectedGap px",
            abs(endGap - expectedGap) <= BOUNDS_TOLERANCE_PX,
        )
    }

    private fun assertVisibleTextGeometry(languageTag: String) {
        val root = composeTestRule.onRoot().fetchSemanticsNode()
        val rootOrigin = root.layoutInfo.coordinates.localToRoot(Offset.Zero)
        val rootRight = rootOrigin.x + root.layoutInfo.width
        val textNodes = composeTestRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().filter { node ->
            node.layoutInfo.isPlaced && node.layoutInfo.width > 0 && node.layoutInfo.height > 0
        }
        assertTrue("$languageTag must expose visible permission text semantics", textNodes.isNotEmpty())
        textNodes.forEach { node ->
            val origin = node.layoutInfo.coordinates.localToRoot(Offset.Zero)
            val right = origin.x + node.layoutInfo.width
            assertTrue(
                "$languageTag permission text overflows horizontally: [$origin, $right] outside " +
                    "[$rootOrigin, $rootRight]",
                origin.x >= rootOrigin.x - BOUNDS_TOLERANCE_PX &&
                    right <= rootRight + BOUNDS_TOLERANCE_PX,
            )
            ProfileScreenshotAssertions.assertTextLayoutDoesNotOverflow(languageTag, node)
        }
    }

    private fun setPermissionContent(
        languageTag: String,
        onRequestPermission: (MutableState<SessionNotificationPermissionState>) -> Unit,
        onOpenSettings: () -> Unit,
    ): PermissionContent {
        RuntimeEnvironment.setQualifiers("w360dp-h800dp-420dpi")
        val renderContext = localizedContext(languageTag)
        val renderConfiguration = Configuration(renderContext.resources.configuration)
        val layoutDirection = if (renderConfiguration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }
        val localeDirection = if (
            TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag(languageTag)) ==
            View.LAYOUT_DIRECTION_RTL
        ) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }
        assertEquals("$languageTag must use its locale layout direction", localeDirection, layoutDirection)
        updateActivityConfiguration(renderConfiguration, layoutDirection)
        composeTestRule.mainClock.autoAdvance = false
        val permissionState = mutableStateOf(
            resolveSessionNotificationPermissionState(
                runtimePermissionRequired = true,
                permissionGranted = false,
                requestedBefore = false,
                shouldShowRationale = false,
            ),
        )
        composeTestRule.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides renderContext,
                LocalConfiguration provides renderConfiguration,
                LocalLayoutDirection provides layoutDirection,
                LocalResources provides renderContext.resources,
                LocalDensity provides Density(density.density, FONT_SCALE),
            ) {
                AgentRelayTheme(dynamicColor = false) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background,
                    ) {
                        MainScreenContent(
                            state = MainScreenUiState.Ready(previewEmptyHub()),
                            actions = previewActions(),
                            notificationPermissionState = permissionState.value,
                            onRequestNotificationPermission = {
                                onRequestPermission(permissionState)
                            },
                            onOpenNotificationSettings = onOpenSettings,
                            backgroundTransportState = BackgroundTransportState.STOPPED,
                        )
                    }
                }
            }
        }
        settle()
        return PermissionContent(renderContext, layoutDirection)
    }

    private fun localizedContext(languageTag: String): Context {
        val locale = Locale.forLanguageTag(languageTag)
        Locale.setDefault(locale)
        val configuration = Configuration(composeTestRule.activity.resources.configuration)
        configuration.setLocale(locale)
        return composeTestRule.activity.createConfigurationContext(configuration)
    }

    @Suppress("DEPRECATION")
    private fun updateActivityConfiguration(
        configuration: Configuration,
        layoutDirection: LayoutDirection,
    ) {
        val activity = composeTestRule.activity
        activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
        activity.window.decorView.layoutDirection = if (layoutDirection == LayoutDirection.Rtl) {
            View.LAYOUT_DIRECTION_RTL
        } else {
            View.LAYOUT_DIRECTION_LTR
        }
    }

    private fun settle() {
        composeTestRule.mainClock.advanceTimeBy(SCREENSHOT_CLOCK_MILLIS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun clickAndSettle(text: String) {
        composeTestRule.mainClock.autoAdvance = true
        try {
            composeTestRule.onNodeWithText(text).performClick()
            composeTestRule.waitForIdle()
        } finally {
            composeTestRule.mainClock.autoAdvance = false
        }
        settle()
    }

    private fun capture(fileName: String) {
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/$fileName",
            roborazziOptions = RoborazziOptions(
                compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f),
                recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5),
            ),
        )
    }

    private fun requestStrings(context: Context) = PermissionStrings(
        title = context.getString(R.string.notification_permission_title),
        explanation = context.getString(R.string.notification_permission_request_explanation),
        action = context.getString(R.string.notification_permission_allow),
        backgroundTitle = context.getString(R.string.background_transport_title),
        backgroundExplanation = context.getString(R.string.background_transport_requires_notifications),
        backgroundAction = context.getString(R.string.background_transport_start),
    )

    private fun settingsStrings(context: Context) = PermissionStrings(
        title = context.getString(R.string.notification_permission_title),
        explanation = context.getString(R.string.notification_permission_settings_explanation),
        action = context.getString(R.string.notification_permission_open_settings),
        backgroundTitle = context.getString(R.string.background_transport_title),
        backgroundExplanation = context.getString(R.string.background_transport_requires_notifications),
        backgroundAction = context.getString(R.string.background_transport_start),
    )

    private companion object {
        const val SCREENSHOT_CLOCK_MILLIS = 1_000L
        const val FONT_SCALE = 1.3f
        const val BOUNDS_TOLERANCE_PX = 1f
        const val MINIMUM_TOUCH_TARGET_DP = 48f
        const val CARD_CONTENT_PADDING_DP = 16f
    }
}

private data class PermissionContent(
    val renderContext: Context,
    val layoutDirection: LayoutDirection,
)

private data class PermissionStrings(
    val title: String,
    val explanation: String,
    val action: String,
    val backgroundTitle: String,
    val backgroundExplanation: String,
    val backgroundAction: String,
)
