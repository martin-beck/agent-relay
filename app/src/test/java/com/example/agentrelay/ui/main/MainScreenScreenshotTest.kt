package com.example.agentrelay.ui.main

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.Density
import com.example.agentrelay.theme.AgentRelayTheme
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
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
                "The encrypted session store could not be opened. Retry after the device is unlocked.",
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

    private fun capture(
        name: String,
        widthDp: Int,
        heightDp: Int,
        state: MainScreenUiState,
        fontScale: Float = 1f,
        darkTheme: Boolean = false,
        afterSetContent: () -> Unit = {},
    ) {
        RuntimeEnvironment.setQualifiers("w${widthDp}dp-h${heightDp}dp-420dpi")
        composeTestRule.activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
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
                        )
                    }
                }
            }
        }
        afterSetContent()

        composeTestRule.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/$name.png",
            roborazziOptions = RoborazziOptions(
                compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f),
                recordOptions = RoborazziOptions.RecordOptions(resizeScale = 0.5),
            ),
        )
    }
}
