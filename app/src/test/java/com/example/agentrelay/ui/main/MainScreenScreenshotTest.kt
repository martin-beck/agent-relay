package com.example.agentrelay.ui.main

import android.content.Context
import android.content.res.Configuration
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.agentrelay.background.BackgroundTransportState
import com.example.agentrelay.theme.AgentRelayTheme
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
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
            afterSetContent = {
                composeTestRule.onNodeWithTag(SESSION_HUB_LIST_TEST_TAG)
                    .performScrollToKey("preview-session")
            },
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

    private fun capture(
        name: String,
        widthDp: Int,
        heightDp: Int,
        state: MainScreenUiState,
        fontScale: Float = 1f,
        darkTheme: Boolean = false,
        backgroundTransportState: BackgroundTransportState = BackgroundTransportState.STOPPED,
        languageTag: String? = null,
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
                        MainScreenContent(
                            state = state,
                            actions = previewActions(),
                            backgroundTransportState = backgroundTransportState,
                        )
                    }
                }
            }
        }
        afterSetContent()
        composeTestRule.mainClock.advanceTimeBy(SCREENSHOT_CLOCK_MILLIS)
        composeTestRule.waitForIdle()
        languageTag?.let {
            listOf(
                com.example.agentrelay.R.string.background_transport_title,
                com.example.agentrelay.R.string.app_name,
                com.example.agentrelay.R.string.session_hub_attention_title,
            ).forEach { stringResource ->
                composeTestRule
                    .onNodeWithText(renderContext.getString(stringResource))
                    .assertIsDisplayed()
            }
            assertTextDoesNotOverflowHorizontally(it)
        }

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/$name.png",
            roborazziOptions = RoborazziOptions(
                compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f),
                recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5),
            ),
        )
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
            assertTextLayoutDoesNotOverflow(languageTag, node)
        }
    }

    private fun assertTextLayoutDoesNotOverflow(
        languageTag: String,
        node: androidx.compose.ui.semantics.SemanticsNode,
    ) {
        if (!node.config.contains(SemanticsActions.GetTextLayoutResult)) return
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        results.forEach { result ->
            repeat(result.lineCount) { line ->
                val left = result.getLineLeft(line)
                val right = result.getLineRight(line)
                assertTrue(
                    "$languageTag text line overflows horizontally: " +
                        "[$left, $right] outside [0, ${result.size.width}] for ${result.layoutInput.text}",
                    right - left <= result.size.width + BOUNDS_TOLERANCE_PX,
                )
                assertFalse(
                    "$languageTag text line is ellipsized: ${result.layoutInput.text}",
                    result.isLineEllipsized(line),
                )
            }
        }
    }

    private companion object {
        const val SCREENSHOT_CLOCK_MILLIS = 1_000L
        const val BOUNDS_TOLERANCE_PX = 1f
    }
}
