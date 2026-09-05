package com.example.agentrelay.ui.main

import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agentrelay.theme.AgentRelayTheme
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

private const val CAPTURE_DIRECTORY = "usage-guide"
private const val PUBLISHED_CAPTURE_DIRECTORY = "/sdcard/Download/agent-relay-usage-guide"
private const val SYSTEM_BAR_CROP_DP = 24

class UsageJourneyTest {

    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var screen: MutableState<UsageGuideScreen>

    private lateinit var previousLocale: Locale
    private lateinit var previousTimeZone: TimeZone

    @Before
    fun setDeterministicFormatting() {
        previousLocale = Locale.getDefault()
        previousTimeZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreFormatting() {
        Locale.setDefault(previousLocale)
        TimeZone.setDefault(previousTimeZone)
    }

    @Test
    fun capturesVerifiedJourneys() {
        resetCaptureDirectory()
        screen = mutableStateOf(UsageGuideScreen.Hub(freshHub()))
        composeTestRule.setContent {
            AgentRelayTheme {
                when (val current = screen.value) {
                    is UsageGuideScreen.Hub -> MainScreenContent(
                        state = MainScreenUiState.Ready(
                            hub = current.hub,
                            profileEditor = current.profileEditor,
                            sessionCreator = current.sessionCreator,
                        ),
                        actions = guideActions(),
                        modifier = Modifier.padding(16.dp),
                    )
                    is UsageGuideScreen.Detail -> SessionDetailRoute(
                        state = MainScreenUiState.Ready(current.hub),
                        onBack = {},
                        onDraftChanged = { _, _, _, _ -> },
                        onSubmitDraft = {},
                        onResumeSession = {},
                        onInterruptSession = {},
                        onRespondToAction = { _, _, _, _, _ -> },
                        onRefreshArtifacts = {},
                        onSaveArtifact = { _, _, _ -> },
                        onCancelArtifact = {},
                    )
                }
            }
        }

        captureFreshStartJourney()
        captureIdentityJourney()
        captureAttentionJourney()
        captureSessionJourney()
        captureFileJourney()
        captureAttentionOverview()
        publishCaptures()
    }

    private fun captureFreshStartJourney() {
        showHub(freshHub())
        composeTestRule
            .onNodeWithText("No connection profiles are available. Refresh to try again.")
            .assertIsDisplayed()
        capture("fresh-start-first-session", "open-fresh-hub.png")

        composeTestRule
            .onNodeWithText("Add Secure Shell profile")
            .performScrollTo()
            .performClick()
        composeTestRule.waitForIdle()
        capture("fresh-start-first-session", "add-secure-shell-profile.png")

        composeTestRule.onNodeWithText("Save").performClick()
        scrollToText("Trust identity")
        capture("fresh-start-first-session", "review-first-host-identity.png")

        composeTestRule.onNodeWithText("Trust identity").performClick()
        scrollToText("Start Codex on Workshop host")
        composeTestRule.onNodeWithText("Start Codex on Workshop host").performClick()
        composeTestRule.onNodeWithTag("start-session").performClick()
        composeTestRule.onNodeWithTag("session-composer-input").performScrollTo().assertIsDisplayed()
        capture("fresh-start-first-session", "start-first-session.png")
    }

    private fun captureIdentityJourney() {
        showHub(changedIdentityHub())
        scrollToText("Workshop host")
        capture("host-identity-review", "changed-host-identity.png")

        composeTestRule.onNodeWithText("Replace identity").performClick()
        composeTestRule.onNodeWithText("Online").assertIsDisplayed()
        capture("host-identity-review", "trusted-host-online.png")
    }

    private fun captureAttentionJourney() {
        showDetail(actionHub())
        composeTestRule.onNodeWithText("Focused tests").performScrollTo().assertIsDisplayed()
        capture("attention-approval", "attention-required.png")

        composeTestRule.onNodeWithText("Focused tests").performClick()
        composeTestRule
            .onNodeWithText("Submit answers")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("action-confirmation-dialog").assertIsDisplayed()
        capture("attention-approval", "approval-confirmation.png")
        composeTestRule.onNodeWithText("Go back").performClick()

        showDetail(deliveringActionHub())
        composeTestRule
            .onNode(hasText("Response delivery is awaiting provider confirmation", substring = true))
            .performScrollTo()
            .assertIsDisplayed()
        capture("attention-approval", "delivery-pending.png")
    }

    private fun captureSessionJourney() {
        showHub(twoSessionHub())
        scrollToText("Recent sessions")
        capture("session-switching-steering", "session-list.png")

        showDetail(interactiveHub())
        composeTestRule.onNodeWithTag("session-composer-input").performScrollTo().assertIsDisplayed()
        capture("session-switching-steering", "running-session.png")
    }

    private fun captureFileJourney() {
        showDetail(testHub())
        composeTestRule.onNodeWithText("reports/result.txt").performScrollTo().assertIsDisplayed()
        capture("changed-file-export", "changed-files.png")

        showDetail(exportCompleteHub())
        composeTestRule.onNodeWithText("Save another copy").performScrollTo().assertIsDisplayed()
        capture("changed-file-export", "export-complete.png")
    }

    private fun captureAttentionOverview() {
        showHub(actionHub())
        scrollToText("Needs attention")
        capture("attention-overview", "attention-overview.png")
    }

    private fun showHub(hub: SessionHubUiModel) {
        composeTestRule.runOnIdle {
            screen.value = UsageGuideScreen.Hub(hub)
        }
        composeTestRule.waitForIdle()
    }

    private fun showDetail(hub: SessionHubUiModel) {
        composeTestRule.runOnIdle {
            screen.value = UsageGuideScreen.Detail(hub)
        }
        composeTestRule.waitForIdle()
    }

    private fun scrollToText(text: String) {
        composeTestRule
            .onNode(hasScrollAction())
            .performScrollToNode(hasText(text))
        composeTestRule.onNodeWithText(text).assertIsDisplayed()
    }

    private fun guideActions() = SessionHubActions(
        retry = {},
        refresh = {},
        connect = {},
        disconnect = {},
        trustIdentity = { _, _ -> screen.value = UsageGuideScreen.Hub(onlineHub()) },
        rejectIdentity = {},
        selectSession = {},
        openSession = {},
        dismissError = {},
        addProfile = {
            screen.value = UsageGuideScreen.Hub(
                hub = freshHub(),
                profileEditor = newSshEditor(),
            )
        },
        updateProfileField = { fieldId, value ->
            val current = screen.value as? UsageGuideScreen.Hub
            val editor = current?.profileEditor as? ConnectionProfileEditorUiState.Editing
            if (current != null && editor != null) {
                screen.value = current.copy(profileEditor = editor.updateField(fieldId, value))
            }
        },
        saveProfile = { screen.value = UsageGuideScreen.Hub(firstUseIdentityHub()) },
        openSessionCreator = {
            val current = screen.value as UsageGuideScreen.Hub
            screen.value = current.copy(sessionCreator = testSessionCreator())
        },
        startSession = { screen.value = UsageGuideScreen.Detail(firstReadySessionHub()) },
    )

    private fun capture(scenario: String, name: String) {
        composeTestRule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val density = instrumentation.targetContext.resources.displayMetrics.density
        val crop = (SYSTEM_BAR_CROP_DP * density).roundToInt()
        val full = waitForRenderedScreenshot(crop)
        check(full.height > crop * 2)
        val image = Bitmap.createBitmap(full, 0, crop, full.width, full.height - crop * 2)
        val directory = File(captureRoot(), scenario)
        check(directory.mkdirs() || directory.isDirectory)
        FileOutputStream(File(directory, name)).use { stream ->
            check(image.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        image.recycle()
        full.recycle()
    }

    private fun waitForRenderedScreenshot(crop: Int): Bitmap {
        var rendered: Bitmap? = null
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            val candidate = InstrumentationRegistry.getInstrumentation()
                .uiAutomation
                .takeScreenshot()
                ?: return@waitUntil false
            if (hasRenderedContent(candidate, crop)) {
                rendered = candidate
                true
            } else {
                candidate.recycle()
                false
            }
        }
        return checkNotNull(rendered)
    }

    private fun hasRenderedContent(image: Bitmap, crop: Int): Boolean {
        if (image.height <= crop * 2) return false
        val background = image.getPixel(image.width - 1, image.height - crop - 1)
        val xStep = maxOf(1, image.width / 90)
        val yStep = maxOf(1, (image.height - crop * 2) / 120)
        var distinctSamples = 0
        for (y in crop until image.height - crop step yStep) {
            for (x in 0 until image.width step xStep) {
                val pixel = image.getPixel(x, y)
                val difference = abs(Color.red(pixel) - Color.red(background)) +
                    abs(Color.green(pixel) - Color.green(background)) +
                    abs(Color.blue(pixel) - Color.blue(background))
                if (difference > 24 && ++distinctSamples >= 12) return true
            }
        }
        return false
    }

    private fun publishCaptures() {
        val source = captureRoot().canonicalFile
        check(source.name == CAPTURE_DIRECTORY)
        check(source.path.all { it.isLetterOrDigit() || it in "/._-" })
        executeShell("rm -rf $PUBLISHED_CAPTURE_DIRECTORY")
        executeShell("mkdir -p $PUBLISHED_CAPTURE_DIRECTORY")
        executeShell("cp -R ${source.path}/. $PUBLISHED_CAPTURE_DIRECTORY/")

        val capturedFiles = executeShell("find $PUBLISHED_CAPTURE_DIRECTORY -type f")
            .lineSequence()
            .filter(String::isNotBlank)
            .toList()
        check(capturedFiles.size == 14) {
            "Expected 14 published usage-guide screenshots, found ${capturedFiles.size}"
        }
    }

    private fun executeShell(command: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        return instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                .use { stream -> stream.readBytes().decodeToString().trim() }
        }
    }

    private fun resetCaptureDirectory() {
        val root = captureRoot()
        check(root.name == CAPTURE_DIRECTORY)
        if (root.exists()) {
            check(root.deleteRecursively())
        }
        check(root.mkdirs())
    }

    private fun captureRoot(): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(checkNotNull(context.getExternalFilesDir(null)), CAPTURE_DIRECTORY)
    }
}

private sealed interface UsageGuideScreen {
    data class Hub(
        val hub: SessionHubUiModel,
        val profileEditor: ConnectionProfileEditorUiState? = null,
        val sessionCreator: SessionCreatorUiState? = null,
    ) : UsageGuideScreen

    data class Detail(val hub: SessionHubUiModel) : UsageGuideScreen
}
