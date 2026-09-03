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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.example.agentrelay.R
import com.example.agentrelay.theme.AgentRelayTheme
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.InternalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.agentrelay.provider.api.AgentApprovalDecision
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
@OptIn(ExperimentalRoborazziApi::class, InternalRoborazziApi::class)
class SessionDetailScreenshotTest {

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
        val questionDetail = questionDetail()
        val content = setDetailContent(languageTag, questionDetail)
        val renderContext = content.renderContext
        settle()
        scrollToText(QUESTION_OPTION)
        clickText(QUESTION_OPTION)
        composeTestRule.onNodeWithText(QUESTION_OPTION).assertIsSelected()
        assertQuestionSemantics(languageTag, renderContext)
        assertVisibleTextGeometry(languageTag)
        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/locale_${fileName}_session_question_compact_large_text.png",
            roborazziOptions = screenshotOptions(),
        )
        if (languageTag == "en-XA" || languageTag == "ar-XB") {
            assertOtherKeyboard(renderContext)
        }
        showDetail(content, multipleQuestionDetail())
        assertMultipleQuestionSemantics(languageTag)
        val approvalDetail = approvalDetail()
        showDetail(content, approvalDetail)
        val approve = renderContext.getString(R.string.session_action_decision_approve_once)
        scrollToText(approve)
        composeTestRule.onNodeWithText(approve).performClick()
        settle()
        assertConfirmationSemantics(languageTag, renderContext)
        captureScreenRoboImage(
            filePath = "src/test/screenshots/locale_${fileName}_session_confirmation_compact_large_text.png",
            roborazziOptions = screenshotOptions(),
        )
        composeTestRule.onNodeWithText(renderContext.getString(R.string.action_go_back)).performClick()
    }

    private fun assertQuestionSemantics(
        languageTag: String,
        renderContext: Context,
    ) {
        val option = composeTestRule.onNodeWithText(QUESTION_OPTION)
        option.assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton),
        )
        composeTestRule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup),
        ).fetchSemanticsNodes().let { groups ->
            assertTrue("$languageTag single-choice question must expose a selectable group", groups.isNotEmpty())
        }
        val other = composeTestRule.onNodeWithText(
            renderContext.getString(R.string.session_action_other_answer),
        ).assert(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
        scrollToText(renderContext.getString(R.string.action_cancel))
        val submit = composeTestRule.onNodeWithText(
            renderContext.getString(R.string.session_action_decision_submit),
        ).assertIsEnabled()
        val cancel = composeTestRule.onNodeWithText(
            renderContext.getString(R.string.action_cancel),
        ).assertIsEnabled()
        assertFullyContained(languageTag, listOf(option, other, submit, cancel))
        if (languageTag == "ar-XB") {
            assertTrue(
                "$languageTag question actions must mirror Submit and Cancel",
                submit.fetchSemanticsNode().boundsInRoot.center.x >
                    cancel.fetchSemanticsNode().boundsInRoot.center.x,
            )
        }
    }

    private fun assertOtherKeyboard(renderContext: Context) {
        val other = composeTestRule.onNodeWithText(
            renderContext.getString(R.string.session_action_other_answer),
        )
        other.performClick().performTextInput("Custom scope")
        other.performImeAction()
        other.assertIsNotFocused()
    }

    private fun assertMultipleQuestionSemantics(languageTag: String) {
        composeTestRule.mainClock.autoAdvance = true
        try {
            composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(MULTIPLE_OPTION))
            val option = composeTestRule.onNodeWithText(MULTIPLE_OPTION)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            val groups = composeTestRule.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup),
            ).fetchSemanticsNodes()
            assertTrue("$languageTag multi-choice question must not expose a selectable group", groups.isEmpty())
            option.performClick().assertIsSelected()
            option.performClick().assertIsNotSelected()
        } finally {
            composeTestRule.mainClock.autoAdvance = false
        }
    }

    private fun assertConfirmationSemantics(languageTag: String, renderContext: Context) {
        val title = renderContext.getString(R.string.session_action_confirm_title)
        val approve = renderContext.getString(R.string.session_action_decision_approve_once)
        val confirmLabel = renderContext.getString(R.string.session_action_confirm_decision, approve)
        val dismissLabel = renderContext.getString(R.string.action_go_back)
        composeTestRule.onNodeWithText(title).assertIsDisplayed()
        val confirm = composeTestRule.onNodeWithText(confirmLabel).assertIsDisplayed()
        val dismiss = composeTestRule.onNodeWithText(dismissLabel).assertIsDisplayed()
        val roots = ProfileScreenshotAssertions.composeRoots()
        assertWindowTextGeometry(languageTag, roots)
        assertWindowContentContained(languageTag, roots, listOf(title, confirmLabel, dismissLabel))
        if (languageTag == "ar-XB") {
            assertTrue(
                "$languageTag confirmation actions must mirror Confirm and Go back",
                confirm.fetchSemanticsNode().boundsInRoot.center.x <
                    dismiss.fetchSemanticsNode().boundsInRoot.center.x,
            )
        }
    }

    private fun assertFullyContained(
        languageTag: String,
        interactions: List<androidx.compose.ui.test.SemanticsNodeInteraction>,
    ) {
        val root = composeTestRule.onRoot().fetchSemanticsNode().boundsInRoot
        interactions.forEach { interaction ->
            val bounds = interaction.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(
                "$languageTag required content is cut off: $bounds outside $root",
                bounds.left >= root.left - BOUNDS_TOLERANCE_PX &&
                    bounds.right <= root.right + BOUNDS_TOLERANCE_PX &&
                    bounds.top >= root.top - BOUNDS_TOLERANCE_PX &&
                    bounds.bottom <= root.bottom + BOUNDS_TOLERANCE_PX,
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
        assertTrue("$languageTag must expose visible text semantics", textNodes.isNotEmpty())
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

    private fun assertWindowTextGeometry(
        languageTag: String,
        roots: List<androidx.compose.ui.node.RootForTest>,
    ) {
        assertTrue("$languageTag must expose Compose window roots", roots.isNotEmpty())
        roots.forEach { composeRoot ->
            val root = composeRoot.semanticsOwner.unmergedRootSemanticsNode
            composeRoot.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false)
                .filter { node ->
                    node.layoutInfo.isPlaced &&
                        node.boundsInRoot.width > 0f &&
                        node.boundsInRoot.height > 0f &&
                        node.config.contains(SemanticsProperties.Text)
                }.forEach { node ->
                    assertTrue(
                        "$languageTag window text is outside ${root.boundsInRoot}: ${node.boundsInRoot}",
                        node.boundsInRoot.left >= root.boundsInRoot.left - BOUNDS_TOLERANCE_PX &&
                            node.boundsInRoot.right <= root.boundsInRoot.right + BOUNDS_TOLERANCE_PX,
                    )
                    ProfileScreenshotAssertions.assertTextLayoutDoesNotOverflow(languageTag, node)
                }
        }
    }

    private fun assertWindowContentContained(
        languageTag: String,
        roots: List<androidx.compose.ui.node.RootForTest>,
        requiredText: List<String>,
    ) {
        val nodes = roots.flatMap { composeRoot ->
            val root = composeRoot.semanticsOwner.unmergedRootSemanticsNode
            composeRoot.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
                .filter { node -> node.layoutInfo.isPlaced }
                .map { node -> node to root }
        }
        requiredText.forEach { expected ->
            val matches = nodes.filter { (node, _) ->
                node.config.getOrElseNullable(SemanticsProperties.Text) { null }
                    .orEmpty()
                    .any { text -> text.text == expected }
            }
            assertEquals("$languageTag must expose one required dialog element: $expected", 1, matches.size)
            val (node, root) = matches.single()
            assertTrue(
                "$languageTag dialog content is cut off: ${node.boundsInRoot} outside ${root.boundsInRoot}",
                node.boundsInRoot.left >= root.boundsInRoot.left - BOUNDS_TOLERANCE_PX &&
                    node.boundsInRoot.right <= root.boundsInRoot.right + BOUNDS_TOLERANCE_PX &&
                    node.boundsInRoot.top >= root.boundsInRoot.top - BOUNDS_TOLERANCE_PX &&
                    node.boundsInRoot.bottom <= root.boundsInRoot.bottom + BOUNDS_TOLERANCE_PX,
            )
        }
    }

    private fun showDetail(content: DetailContent, detail: SessionDetailUiModel) {
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.runOnIdle { content.detail.value = detail }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.autoAdvance = false
        settle()
    }

    private fun scrollToText(text: String) {
        composeTestRule.mainClock.autoAdvance = true
        try {
            composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
            composeTestRule.waitForIdle()
        } finally {
            composeTestRule.mainClock.autoAdvance = false
        }
        settle()
    }

    private fun clickText(text: String) {
        composeTestRule.mainClock.autoAdvance = true
        try {
            composeTestRule.onNodeWithText(text).performClick()
            composeTestRule.waitForIdle()
        } finally {
            composeTestRule.mainClock.autoAdvance = false
        }
        settle()
    }

    private fun setDetailContent(languageTag: String, detail: SessionDetailUiModel): DetailContent {
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
                        SessionDetailPane(detail = detailState.value)
                    }
                }
            }
        }
        return DetailContent(renderContext, detailState)
    }

    private fun settle() {
        composeTestRule.mainClock.advanceTimeBy(SCREENSHOT_CLOCK_MILLIS)
        shadowOf(Looper.getMainLooper()).idle()
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

    private fun localizedContext(languageTag: String): Context {
        val locale = Locale.forLanguageTag(languageTag)
        Locale.setDefault(locale)
        val configuration = Configuration(composeTestRule.activity.resources.configuration)
        configuration.setLocale(locale)
        return composeTestRule.activity.createConfigurationContext(configuration)
    }

    private fun questionDetail(): SessionDetailUiModel {
        val base = checkNotNull(previewHub(approvalRequired = true).selectedSession)
        val action = base.actions.single().copy(
            stableKey = "question-action",
            title = UiMessage.Verbatim("Choose scope"),
            description = null,
            command = null,
            scope = null,
            connectionLabel = "C",
            connectionProviderName = "SSH",
            connectionTarget = "T",
            agentProviderLabel = "A",
            sessionTitle = UiMessage.Verbatim("S"),
            questions = listOf(
                SessionQuestionUiModel(
                    stableKey = "question-key",
                    header = null,
                    prompt = UiMessage.Verbatim("Which tests?"),
                    options = listOf(
                        SessionQuestionOptionUiModel(QUESTION_OPTION, null),
                    ),
                    allowsOther = true,
                    allowsMultiple = false,
                ),
            ),
            decisions = listOf(
                SessionDecisionUiModel(AgentApprovalDecision.SUBMIT, false, true),
                SessionDecisionUiModel(AgentApprovalDecision.CANCEL, false, false),
            ),
            risks = emptyList(),
        )
        return base.copy(actions = listOf(action), artifacts = emptyList(), transcript = emptyList(), activities = emptyList())
    }

    private fun multipleQuestionDetail(): SessionDetailUiModel {
        val base = questionDetail()
        val action = base.actions.single().copy(
            questions = listOf(
                SessionQuestionUiModel(
                    stableKey = "multiple-question-key",
                    header = null,
                    prompt = UiMessage.Verbatim("Keep which evidence?"),
                    options = listOf(SessionQuestionOptionUiModel(MULTIPLE_OPTION, null)),
                    allowsOther = false,
                    allowsMultiple = true,
                ),
            ),
            decisions = listOf(
                SessionDecisionUiModel(AgentApprovalDecision.CANCEL, false, false),
            ),
        )
        return base.copy(actions = listOf(action))
    }

    private fun approvalDetail(): SessionDetailUiModel {
        val base = checkNotNull(previewHub(approvalRequired = true).selectedSession)
        return base.copy(artifacts = emptyList(), transcript = emptyList(), activities = emptyList())
    }

    private fun screenshotOptions() = RoborazziOptions(
        compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f),
        recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5),
    )

    private companion object {
        const val SCREENSHOT_CLOCK_MILLIS = 1_000L
        const val FONT_SCALE = 1.3f
        const val BOUNDS_TOLERANCE_PX = 1f
        const val QUESTION_OPTION = "Focused tests"
        const val MULTIPLE_OPTION = "Keep logs"
    }
}

private data class DetailContent(
    val renderContext: Context,
    val detail: MutableState<SessionDetailUiModel>,
)
