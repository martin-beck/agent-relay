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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.agentrelay.R
import com.example.agentrelay.theme.AgentRelayTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "420dpi")
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalRoborazziApi::class)
class SpeechInstallScreenshotTest {

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
        var selectedModel: String? = null
        var installRequests = 0
        var cancelRequests = 0
        var retryRequests = 0
        var startedSession: String? = null
        val selectedModelId = mutableStateOf(FIRST_MODEL_ID)
        val selectionActions = SpeechInputUiActions(
            selectModel = {
                selectedModel = it
                selectedModelId.value = it
            },
            installModel = { installRequests += 1 },
        )
        val content = setLocalizedContent(languageTag) {
            SpeechInputControls(
                state = selectionState(selectedModelId.value),
                sessionKey = SESSION_KEY,
                actions = selectionActions,
            )
        }
        val selectionStrings = selectionStrings(content.renderContext)
        assertSelectionSemantics(languageTag, content.layoutDirection, selectionStrings)
        composeTestRule.mainClock.autoAdvance = true
        try {
            composeTestRule.onNode(selectionRowMatcher(SECOND_MODEL)).performClick()
            composeTestRule.waitForIdle()
        } finally {
            composeTestRule.mainClock.autoAdvance = false
        }
        settle()
        composeTestRule.onNode(selectionRowMatcher(SECOND_MODEL)).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Selected, true),
        )
        composeTestRule.onNodeWithText(selectionStrings.install).performClick()
        assertEquals(SECOND_MODEL_ID, selectedModel)
        assertEquals(1, installRequests)
        capture("locale_${fileName}_speech_model_selection_compact_large_text.png")

        val lifecycleActions = SpeechInputUiActions(
            installModel = { retryRequests += 1 },
            cancelModelInstall = { cancelRequests += 1 },
            requestStart = { startedSession = it },
        )
        setLocalizedContent(languageTag) {
            InstallLifecycleSurface(lifecycleActions)
        }
        val lifecycleStrings = lifecycleStrings(content.renderContext)
        assertLifecycleSemantics(languageTag, content.layoutDirection, lifecycleStrings)
        capture("locale_${fileName}_speech_install_lifecycle_compact_large_text.png")
        composeTestRule.onNodeWithText(lifecycleStrings.cancel).performClick()
        composeTestRule.onNodeWithText(lifecycleStrings.retry).performClick()
        composeTestRule.onNodeWithText(lifecycleStrings.start).performClick()
        assertEquals(1, cancelRequests)
        assertEquals(1, retryRequests)
        assertEquals(SESSION_KEY, startedSession)
    }

    @Composable
    private fun InstallLifecycleSurface(actions: SpeechInputUiActions) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SpeechInputControls(
                state = installingState(),
                sessionKey = SESSION_KEY,
                actions = actions,
            )
            SpeechInputControls(
                state = failedState(),
                sessionKey = SESSION_KEY,
                actions = actions,
            )
            SpeechInputControls(
                state = readyState(),
                sessionKey = SESSION_KEY,
                actions = actions,
            )
        }
    }

    private fun assertSelectionSemantics(
        languageTag: String,
        layoutDirection: LayoutDirection,
        strings: SelectionStrings,
    ) {
        assertRequiredContent(
            languageTag,
            layoutDirection,
            listOf(
                strings.title,
                strings.modelLabel,
                strings.installedModel,
                SECOND_MODEL,
                strings.status,
                strings.install,
            ),
        )
        val groups = composeTestRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup),
        ).fetchSemanticsNodes()
        assertEquals("$languageTag must expose one speech model selectable group", 1, groups.size)
        val radios = composeTestRule.onAllNodes(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
        ).fetchSemanticsNodes()
        assertEquals("$languageTag must expose two speech model radio options", 2, radios.size)
        assertEquals(
            "$languageTag must expose one selected speech model",
            1,
            radios.count { it.config.getOrElseNullable(SemanticsProperties.Selected) { null } == true },
        )
        assertTrue(
            "$languageTag speech model options must be actionable",
            radios.all { it.config.contains(SemanticsActions.OnClick) },
        )
        assertRawContained(
            languageTag,
            layoutDirection,
            interactionsFor(groups + radios),
        )
        composeTestRule.onNodeWithText(strings.status).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
        assertButton(strings.install)
        assertAllTextGeometry(languageTag, layoutDirection)
    }

    private fun assertLifecycleSemantics(
        languageTag: String,
        layoutDirection: LayoutDirection,
        strings: LifecycleStrings,
    ) {
        assertRequiredContent(
            languageTag,
            layoutDirection,
            listOf(
                strings.downloading,
                strings.progress,
                strings.cancel,
                strings.failure,
                strings.retry,
                strings.ready,
                strings.start,
            ),
        )
        assertRepeatedContent(languageTag, layoutDirection, strings.title, expectedCount = 3)
        assertRepeatedContent(
            languageTag,
            layoutDirection,
            strings.selectedMultilingualModel,
            expectedCount = 2,
        )
        assertRepeatedContent(
            languageTag,
            layoutDirection,
            strings.selectedEnglishModel,
            expectedCount = 1,
        )
        listOf(strings.downloading, strings.failure, strings.ready).forEach { status ->
            composeTestRule.onNodeWithText(status).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
            )
        }
        listOf(strings.cancel, strings.retry, strings.start).forEach(::assertButton)
        val progressNodes = composeTestRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
        ).fetchSemanticsNodes()
        assertEquals("$languageTag must expose one model download progress bar", 1, progressNodes.size)
        assertEquals(
            "$languageTag must expose deterministic model download progress",
            PROGRESS_PERCENT / 100f,
            progressNodes.single().config[SemanticsProperties.ProgressBarRangeInfo].current,
        )
        assertRawContained(
            languageTag,
            layoutDirection,
            listOf(
                composeTestRule.onNode(
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
                ),
            ),
        )
        assertAllTextGeometry(languageTag, layoutDirection)
    }

    private fun assertRequiredContent(
        languageTag: String,
        layoutDirection: LayoutDirection,
        text: List<String>,
    ) {
        assertRawContained(
            languageTag,
            layoutDirection,
            text.map(composeTestRule::onNodeWithText),
        )
    }

    private fun assertRepeatedContent(
        languageTag: String,
        layoutDirection: LayoutDirection,
        text: String,
        expectedCount: Int,
    ) {
        val nodes = composeTestRule.onAllNodes(hasText(text)).fetchSemanticsNodes()
        assertEquals("$languageTag must expose $expectedCount instances of $text", expectedCount, nodes.size)
        assertRawContained(languageTag, layoutDirection, interactionsFor(nodes))
    }

    private fun assertRawContained(
        languageTag: String,
        layoutDirection: LayoutDirection,
        interactions: List<SemanticsNodeInteraction>,
    ) {
        val root = composeTestRule.onRoot().fetchSemanticsNode()
        val rootOrigin = root.layoutInfo.coordinates.localToRoot(Offset.Zero)
        val rootRight = rootOrigin.x + root.layoutInfo.width
        val rootBottom = rootOrigin.y + root.layoutInfo.height
        interactions.forEach { interaction ->
            val node = interaction.fetchSemanticsNode()
            val origin = node.layoutInfo.coordinates.localToRoot(Offset.Zero)
            val right = origin.x + node.layoutInfo.width
            val bottom = origin.y + node.layoutInfo.height
            assertEquals(
                "$languageTag speech install content must use $layoutDirection",
                layoutDirection,
                node.layoutInfo.layoutDirection,
            )
            assertTrue(
                "$languageTag speech install content is cut off: " +
                    "[$origin, $right, $bottom] outside " +
                    "[$rootOrigin, $rootRight, $rootBottom]",
                origin.x >= rootOrigin.x - BOUNDS_TOLERANCE_PX &&
                    right <= rootRight + BOUNDS_TOLERANCE_PX &&
                    origin.y >= rootOrigin.y - BOUNDS_TOLERANCE_PX &&
                    bottom <= rootBottom + BOUNDS_TOLERANCE_PX,
            )
        }
    }

    private fun assertAllTextGeometry(languageTag: String, layoutDirection: LayoutDirection) {
        val nodes = composeTestRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().filter { node ->
            node.layoutInfo.isPlaced && node.layoutInfo.width > 0 && node.layoutInfo.height > 0
        }
        assertTrue("$languageTag must expose speech install text", nodes.isNotEmpty())
        assertRawContained(
            languageTag,
            layoutDirection,
            interactionsFor(nodes),
        )
        nodes.forEach { node ->
            ProfileScreenshotAssertions.assertTextLayoutDoesNotOverflow(languageTag, node)
        }
    }

    private fun assertButton(text: String) {
        composeTestRule.onNodeWithText(text)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertIsEnabled()
    }

    private fun interactionsFor(nodes: List<androidx.compose.ui.semantics.SemanticsNode>) =
        nodes.map { node ->
            composeTestRule.onNode(
                SemanticsMatcher("node ${node.id}") { it.id == node.id },
                useUnmergedTree = true,
            )
        }

    private fun selectionRowMatcher(modelName: String) =
        hasText(modelName).and(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
        )

    private fun setLocalizedContent(
        languageTag: String,
        content: @Composable () -> Unit,
    ): LocalizedContent {
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
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            content()
                        }
                    }
                }
            }
        }
        settle()
        return LocalizedContent(renderContext, layoutDirection)
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

    private fun capture(fileName: String) {
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/$fileName",
            roborazziOptions = RoborazziOptions(
                compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f),
                recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5),
            ),
        )
    }

    private fun selectionState(selectedModelId: String) = SpeechInputUiState(
        phase = SpeechInputPhase.MODEL_REQUIRED,
        models = listOf(
            SpeechModelOptionUiModel(FIRST_MODEL_ID, FIRST_MODEL, isReady = true),
            SpeechModelOptionUiModel(SECOND_MODEL_ID, SECOND_MODEL, isReady = false),
        ),
        selectedModelId = selectedModelId,
        selectedModelName = if (selectedModelId == FIRST_MODEL_ID) FIRST_MODEL else SECOND_MODEL,
        statusMessage = UiMessage.Localized(R.string.speech_status_install_model),
    )

    private fun installingState() = SpeechInputUiState(
        phase = SpeechInputPhase.INSTALLING,
        models = lifecycleModels(SECOND_MODEL_ID, SECOND_MODEL, isReady = false),
        selectedModelId = SECOND_MODEL_ID,
        selectedModelName = SECOND_MODEL,
        progressPercent = PROGRESS_PERCENT,
        statusMessage = UiMessage.Localized(R.string.speech_status_downloading_model),
    )

    private fun failedState() = SpeechInputUiState(
        phase = SpeechInputPhase.FAILED,
        models = lifecycleModels(SECOND_MODEL_ID, SECOND_MODEL, isReady = false),
        selectedModelId = SECOND_MODEL_ID,
        selectedModelName = SECOND_MODEL,
        statusMessage = UiMessage.Localized(R.string.speech_error_model_install),
    )

    private fun readyState() = SpeechInputUiState(
        phase = SpeechInputPhase.READY,
        models = lifecycleModels(FIRST_MODEL_ID, FIRST_MODEL, isReady = true),
        selectedModelId = FIRST_MODEL_ID,
        selectedModelName = FIRST_MODEL,
        statusMessage = UiMessage.Localized(R.string.speech_status_ready_private),
    )

    private fun lifecycleModels(id: String, name: String, isReady: Boolean) = listOf(
        SpeechModelOptionUiModel(id, name, isReady),
    )

    private fun selectionStrings(context: Context) = SelectionStrings(
        title = context.getString(R.string.speech_input_title),
        modelLabel = context.getString(R.string.speech_model_label),
        installedModel = context.getString(R.string.speech_model_installed, FIRST_MODEL),
        status = context.getString(R.string.speech_status_install_model),
        install = context.getString(R.string.speech_install_model),
    )

    private fun lifecycleStrings(context: Context) = LifecycleStrings(
        title = context.getString(R.string.speech_input_title),
        selectedMultilingualModel = context.getString(R.string.speech_selected_model, SECOND_MODEL),
        selectedEnglishModel = context.getString(R.string.speech_selected_model, FIRST_MODEL),
        downloading = context.getString(R.string.speech_status_downloading_model),
        progress = context.getString(R.string.speech_download_progress, PROGRESS_PERCENT),
        cancel = context.getString(R.string.speech_cancel_model_download),
        failure = context.getString(R.string.speech_error_model_install),
        retry = context.getString(R.string.speech_retry_model_installation),
        ready = context.getString(R.string.speech_status_ready_private),
        start = context.getString(R.string.speech_start_input),
    )

    private companion object {
        const val SESSION_KEY = "speech-install-session"
        const val FIRST_MODEL_ID = "quartz-english"
        const val SECOND_MODEL_ID = "quartz-multilingual"
        const val FIRST_MODEL = "Quartz English"
        const val SECOND_MODEL = "Quartz Multilingual"
        const val PROGRESS_PERCENT = 42
        const val SCREENSHOT_CLOCK_MILLIS = 1_000L
        const val FONT_SCALE = 1.3f
        const val BOUNDS_TOLERANCE_PX = 1f
    }
}

private data class LocalizedContent(
    val renderContext: Context,
    val layoutDirection: LayoutDirection,
)

private data class SelectionStrings(
    val title: String,
    val modelLabel: String,
    val installedModel: String,
    val status: String,
    val install: String,
)

private data class LifecycleStrings(
    val title: String,
    val selectedMultilingualModel: String,
    val selectedEnglishModel: String,
    val downloading: String,
    val progress: String,
    val cancel: String,
    val failure: String,
    val retry: String,
    val ready: String,
    val start: String,
)
