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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.agentrelay.R
import com.example.agentrelay.theme.AgentRelayTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentrelay.provider.api.AgentFileChangeKind
import java.text.NumberFormat
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
class ArtifactScreenshotTest {

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
        var cancelledArtifact: String? = null
        var savedArtifact: Triple<String, String, String>? = null
        val content = setDetailContent(
            languageTag = languageTag,
            detail = progressDetail(),
            onCancelArtifact = { cancelledArtifact = it },
            onSaveArtifact = { sessionKey, artifactKey, fileName ->
                savedArtifact = Triple(sessionKey, artifactKey, fileName)
            },
        )
        val progressStrings = progressStrings(content.renderContext)
        scrollPastText(progressStrings.cancel)
        assertProgressSemantics(languageTag, content.layoutDirection, progressStrings)
        composeTestRule.onNodeWithText(progressStrings.cancel).performClick()
        assertEquals(PROGRESS_ARTIFACT_KEY, cancelledArtifact)
        capture("locale_${fileName}_artifact_progress_compact_large_text.png")

        showDetail(content.detail, resultDetail())
        val resultStrings = resultStrings(content.renderContext)
        scrollPastText(resultStrings.unavailable)
        assertResultSemantics(languageTag, content.layoutDirection, resultStrings)
        composeTestRule.onNodeWithText(resultStrings.saveAnother).performClick()
        assertEquals(
            Triple("artifact-session", SAVED_ARTIFACT_KEY, "artifact.txt"),
            savedArtifact,
        )
        capture("locale_${fileName}_artifact_result_compact_large_text.png")
    }

    private fun assertProgressSemantics(
        languageTag: String,
        layoutDirection: LayoutDirection,
        strings: ProgressStrings,
    ) {
        val required = listOf(
            strings.heading,
            strings.refresh,
            strings.kind,
            strings.ready,
            strings.progress,
            strings.cancel,
        ).map(composeTestRule::onNodeWithText)
        assertFullyContained(languageTag, layoutDirection, required)
        composeTestRule.onNodeWithText(strings.progress).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
        composeTestRule.onNodeWithText(strings.cancel)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertIsEnabled()
        val progressMatcher =
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            )
        val progress = composeTestRule.onAllNodes(progressMatcher).fetchSemanticsNodes()
        assertEquals("$languageTag must expose one artifact progress indicator", 1, progress.size)
        assertFullyContained(
            languageTag,
            layoutDirection,
            listOf(composeTestRule.onNode(progressMatcher)),
        )
        assertVisibleTextGeometry(languageTag)
    }

    private fun assertResultSemantics(
        languageTag: String,
        layoutDirection: LayoutDirection,
        strings: ResultStrings,
    ) {
        val required = listOf(
            strings.modifiedKind,
            strings.ready,
            strings.saved,
            strings.saveAnother,
            strings.deletedPath,
            strings.deletedKind,
            strings.unavailable,
        ).map(composeTestRule::onNodeWithText)
        assertFullyContained(languageTag, layoutDirection, required)
        composeTestRule.onNodeWithText(strings.saved).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite),
        )
        composeTestRule.onNodeWithText(strings.saveAnother)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertIsEnabled()
        composeTestRule.onNodeWithText(strings.unavailable).assertIsDisplayed()
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
                "$languageTag required artifact content must use $layoutDirection",
                layoutDirection,
                node.layoutInfo.layoutDirection,
            )
            assertTrue(
                "$languageTag required artifact content is cut off: " +
                    "[$origin, $right, $bottom] outside " +
                    "[$rootOrigin, $rootRight, $rootBottom]",
                origin.x >= rootOrigin.x - BOUNDS_TOLERANCE_PX &&
                    right <= rootRight + BOUNDS_TOLERANCE_PX &&
                    origin.y >= rootOrigin.y - BOUNDS_TOLERANCE_PX &&
                    bottom <= rootBottom + BOUNDS_TOLERANCE_PX,
            )
        }
    }

    private fun assertVisibleTextGeometry(languageTag: String) {
        val root = composeTestRule.onRoot().fetchSemanticsNode().boundsInRoot
        val textNodes = composeTestRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().filter { node ->
            node.layoutInfo.isPlaced && node.boundsInRoot.width > 0f && node.boundsInRoot.height > 0f
        }
        assertTrue("$languageTag must expose visible artifact text semantics", textNodes.isNotEmpty())
        textNodes.forEach { node ->
            val left = node.layoutInfo.coordinates.localToRoot(Offset.Zero).x
            val right = left + node.layoutInfo.width
            assertTrue(
                "$languageTag artifact text overflows horizontally: [$left, $right] outside $root",
                left >= root.left - BOUNDS_TOLERANCE_PX &&
                    right <= root.right + BOUNDS_TOLERANCE_PX,
            )
            ProfileScreenshotAssertions.assertTextLayoutDoesNotOverflow(languageTag, node)
        }
    }

    private fun setDetailContent(
        languageTag: String,
        detail: SessionDetailUiModel,
        onCancelArtifact: (String) -> Unit,
        onSaveArtifact: (String, String, String) -> Unit,
    ): ArtifactContent {
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
        val detailState = mutableStateOf(detail)
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
                        SessionDetailPane(
                            detail = detailState.value,
                            onSaveArtifact = onSaveArtifact,
                            onCancelArtifact = onCancelArtifact,
                        )
                    }
                }
            }
        }
        settle()
        return ArtifactContent(renderContext, layoutDirection, detailState)
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

    private fun showDetail(detailState: MutableState<SessionDetailUiModel>, detail: SessionDetailUiModel) {
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.runOnIdle { detailState.value = detail }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.autoAdvance = false
        settle()
    }

    private fun scrollPastText(text: String) {
        composeTestRule.mainClock.autoAdvance = true
        try {
            val scrollable = composeTestRule.onNode(hasScrollAction())
            scrollable.performScrollToNode(hasText(text))
            val extraScroll = EXTRA_SCROLL_DP * composeTestRule.activity.resources.displayMetrics.density
            scrollable.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
                assertTrue("Artifact viewport must accept a bounded follow-up scroll", scrollBy(0f, extraScroll))
            }
            composeTestRule.waitForIdle()
        } finally {
            composeTestRule.mainClock.autoAdvance = false
        }
        settle()
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

    private fun progressStrings(context: Context): ProgressStrings {
        val numberFormat = NumberFormat.getIntegerInstance(context.resources.configuration.locales[0])
        return ProgressStrings(
            heading = context.getString(R.string.session_detail_changed_files),
            refresh = context.getString(R.string.session_detail_refresh_changed_files),
            kind = context.getString(R.string.session_artifact_change_modified),
            ready = context.getString(R.string.session_artifact_availability_ready),
            progress = context.getString(
                R.string.session_artifact_save_progress_total,
                numberFormat.format(4_096),
                numberFormat.format(8_192),
            ),
            cancel = context.getString(R.string.session_artifact_cancel_saving),
        )
    }

    private fun resultStrings(context: Context) = ResultStrings(
        modifiedKind = context.getString(R.string.session_artifact_change_modified),
        ready = context.getString(R.string.session_artifact_availability_ready),
        saved = context.getString(R.string.session_artifact_copy_saved_verified),
        saveAnother = context.getString(R.string.session_artifact_save_another_copy),
        deletedPath = context.getString(R.string.session_artifact_path_deleted),
        deletedKind = context.getString(R.string.session_artifact_change_deleted),
        unavailable = context.getString(R.string.session_artifact_availability_deleted),
    )

    private fun progressDetail() = baseDetail(
        artifacts = listOf(
            artifact(
                stableKey = PROGRESS_ARTIFACT_KEY,
                displayPath = "reports/progress.txt",
                isDownloadable = true,
                canSave = false,
                bytesWritten = 4_096,
                totalBytes = 8_192,
                isExporting = true,
            ),
        ),
    )

    private fun resultDetail() = baseDetail(
        artifacts = listOf(
            artifact(
                stableKey = SAVED_ARTIFACT_KEY,
                displayPath = "reports/saved.txt",
                isDownloadable = true,
                canSave = true,
                isExportComplete = true,
            ),
            artifact(
                stableKey = "deleted-artifact",
                displayPath = null,
                changeKind = AgentFileChangeKind.DELETED,
                availabilityStatus = SessionArtifactAvailabilityStatus.DELETED,
                isDownloadable = false,
                canSave = false,
            ),
        ),
    )

    private fun baseDetail(artifacts: List<SessionArtifactUiModel>): SessionDetailUiModel {
        val base = checkNotNull(previewHub(approvalRequired = false).selectedSession)
        return base.copy(
            actions = emptyList(),
            artifacts = artifacts,
            transcript = emptyList(),
            activities = emptyList(),
            canRefreshArtifacts = true,
            isRefreshingArtifacts = false,
        )
    }

    private fun artifact(
        stableKey: String,
        displayPath: String?,
        changeKind: AgentFileChangeKind = AgentFileChangeKind.MODIFIED,
        availabilityStatus: SessionArtifactAvailabilityStatus = SessionArtifactAvailabilityStatus.READY,
        isDownloadable: Boolean,
        canSave: Boolean,
        bytesWritten: Long = 0,
        totalBytes: Long? = null,
        isExporting: Boolean = false,
        isExportComplete: Boolean = false,
    ) = SessionArtifactUiModel(
        stableKey = stableKey,
        sessionKey = "artifact-session",
        displayPath = displayPath,
        changeKind = changeKind,
        availabilityStatus = availabilityStatus,
        suggestedFileName = "artifact.txt",
        isDownloadable = isDownloadable,
        canSave = canSave,
        bytesWritten = bytesWritten,
        totalBytes = totalBytes,
        isExporting = isExporting,
        isExportComplete = isExportComplete,
    )

    private companion object {
        const val SCREENSHOT_CLOCK_MILLIS = 1_000L
        const val FONT_SCALE = 1.3f
        const val BOUNDS_TOLERANCE_PX = 1f
        const val EXTRA_SCROLL_DP = 32f
        const val PROGRESS_ARTIFACT_KEY = "progress-artifact"
        const val SAVED_ARTIFACT_KEY = "saved-artifact"
    }
}

private data class ArtifactContent(
    val renderContext: Context,
    val layoutDirection: LayoutDirection,
    val detail: MutableState<SessionDetailUiModel>,
)

private data class ProgressStrings(
    val heading: String,
    val refresh: String,
    val kind: String,
    val ready: String,
    val progress: String,
    val cancel: String,
)

private data class ResultStrings(
    val modifiedKind: String,
    val ready: String,
    val saved: String,
    val saveAnother: String,
    val deletedPath: String,
    val deletedKind: String,
    val unavailable: String,
)
