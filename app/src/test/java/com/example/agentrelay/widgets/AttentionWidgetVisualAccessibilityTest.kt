package com.example.agentrelay.widgets

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.agentrelay.R
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.agentrelay.session.api.AttentionUrgency
import dev.agentrelay.session.api.AttentionWidgetActionRequest
import dev.agentrelay.session.api.AttentionWidgetContent
import dev.agentrelay.session.api.AttentionWidgetEntry
import dev.agentrelay.session.api.AttentionWidgetSize
import dev.agentrelay.session.api.AttentionWidgetSurface
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class AttentionWidgetVisualAccessibilityTest {
    @Test
    fun compactLoadingLightIsBoundedAndHasNoActions() {
        val context = configuredContext()
        val root = renderHome(
            context = context,
            size = AttentionWidgetSize.COMPACT,
            phase = AttentionWidgetRenderPhase.LOADING,
            widthDp = 180,
            heightDp = 96,
            fileName = "widget_home_compact_loading_light.png",
        )

        assertText(root, R.id.home_widget_status, context.getString(R.string.home_widget_status_quiet))
        assertText(root, R.id.home_widget_title, context.getString(R.string.home_widget_loading))
        assertNoVisibleActions(root)
    }

    @Test
    fun mediumEmptyDarkLargeTextUsesNightPalette() {
        val context = configuredContext(dark = true, fontScale = 1.5f)
        val root = renderHome(
            context = context,
            size = AttentionWidgetSize.MEDIUM,
            phase = AttentionWidgetRenderPhase.READY,
            widthDp = 260,
            heightDp = 150,
            fileName = "widget_home_medium_empty_dark_large_text.png",
        )

        assertText(root, R.id.home_widget_title, context.getString(R.string.home_widget_no_attention))
        assertEquals(
            ContextCompat.getColor(context, R.color.widget_background),
            (root.background as ColorDrawable).color,
        )
        assertNoVisibleActions(root)
        assertThemeContrast(context)
    }

    @Test
    fun expandedAttentionExposesLocalizedActionSemantics() {
        val context = configuredContext()
        val root = renderHome(
            context = context,
            content = attentionContent(AttentionWidgetSurface.HOME_SCREEN),
            size = AttentionWidgetSize.EXPANDED,
            phase = AttentionWidgetRenderPhase.READY,
            widthDp = 360,
            heightDp = 220,
            fileName = "widget_home_expanded_attention_light.png",
        )

        assertText(root, R.id.home_widget_title, "Review backup access")
        assertEquals(
            context.getString(R.string.home_widget_action_open_details_for, "Review backup access"),
            root.findViewById<TextView>(R.id.home_widget_title).contentDescription,
        )
        listOf(
            R.id.home_widget_acknowledge to R.string.home_widget_action_acknowledge,
            R.id.home_widget_defer to R.string.home_widget_action_defer,
            R.id.home_widget_mute to R.string.home_widget_action_mute_confirm,
        ).forEach { (viewId, labelId) ->
            val button = root.findViewById<Button>(viewId)
            assertEquals(View.VISIBLE, button.visibility)
            assertEquals(context.getString(labelId), button.contentDescription)
            assertTrue(button.isEnabled)
        }
        assertThemeContrast(context)
    }

    @Test
    fun expandedErrorRtlLargeTextHidesStaleContentAndControls() {
        val context = configuredContext(languageTag = "ar-XB", fontScale = 1.5f)
        val root = renderHome(
            context = context,
            content = attentionContent(AttentionWidgetSurface.HOME_SCREEN),
            size = AttentionWidgetSize.EXPANDED,
            phase = AttentionWidgetRenderPhase.ERROR,
            widthDp = 360,
            heightDp = 220,
            fileName = "widget_home_expanded_error_rtl_large_text.png",
        )

        assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
        assertFalse(visibleText(root).contains("Review backup access"))
        assertNoVisibleActions(root)
    }

    @Test
    fun lockScreenAttentionOmitsSummaryAndEveryAction() {
        val context = configuredContext()
        val root = renderLock(
            context = context,
            content = attentionContent(AttentionWidgetSurface.LOCK_SCREEN),
            phase = AttentionWidgetRenderPhase.READY,
            widthDp = 260,
            heightDp = 110,
            fileName = "widget_lock_attention_redacted_light.png",
        )

        assertText(root, R.id.lock_widget_title, "Review backup access")
        assertEquals(View.GONE, root.findViewById<View>(R.id.lock_widget_summary).visibility)
        assertFalse(visibleText(root).contains(PROTECTED_SUMMARY))
        assertTrue(root.descendants().none { it is Button })
    }

    @Test
    fun lockScreenLoadingDarkRtlOmitsPriorContent() {
        val context = configuredContext(languageTag = "ar-XB", dark = true, fontScale = 1.5f)
        val root = renderLock(
            context = context,
            content = attentionContent(AttentionWidgetSurface.LOCK_SCREEN),
            phase = AttentionWidgetRenderPhase.LOADING,
            widthDp = 260,
            heightDp = 130,
            fileName = "widget_lock_loading_dark_rtl.png",
        )

        assertEquals(View.LAYOUT_DIRECTION_RTL, root.layoutDirection)
        assertFalse(visibleText(root).contains("Review backup access"))
        assertFalse(visibleText(root).contains(PROTECTED_SUMMARY))
        assertEquals(
            ContextCompat.getColor(context, R.color.widget_background),
            (root.background as ColorDrawable).color,
        )
        assertThemeContrast(context)
    }

    private fun renderHome(
        context: Context,
        content: AttentionWidgetContent? = null,
        size: AttentionWidgetSize,
        phase: AttentionWidgetRenderPhase,
        widthDp: Int,
        heightDp: Int,
        fileName: String,
    ): View = capture(
        context = context,
        remoteView = HomeScreenAttentionWidgetRenderer.render(
            context = context,
            content = content,
            size = size,
            actionIssuer = issuer,
            phase = phase,
        ).apply(context, FrameLayout(context)),
        widthDp = widthDp,
        heightDp = heightDp,
        fileName = fileName,
    )

    private fun renderLock(
        context: Context,
        content: AttentionWidgetContent?,
        phase: AttentionWidgetRenderPhase,
        widthDp: Int,
        heightDp: Int,
        fileName: String,
    ): View = capture(
        context = context,
        remoteView = LockScreenAttentionWidgetRenderer.render(context, content, phase)
            .apply(context, FrameLayout(context)),
        widthDp = widthDp,
        heightDp = heightDp,
        fileName = fileName,
    )

    private fun capture(
        context: Context,
        remoteView: View,
        widthDp: Int,
        heightDp: Int,
        fileName: String,
    ): View {
        val root = FrameLayout(context).apply {
            layoutDirection = context.resources.configuration.layoutDirection
            addView(remoteView)
        }
        ((context as ContextThemeWrapper).baseContext as Activity).setContentView(root)
        val width = context.dp(widthDp)
        val height = context.dp(heightDp)
        root.measure(exactly(width), exactly(height))
        root.layout(0, 0, width, height)
        assertEquals(width, root.measuredWidth)
        assertEquals(height, root.measuredHeight)
        root.assertVisibleDescendantsAreBounded()
        remoteView.captureRoboImage(
            filePath = "src/test/screenshots/$fileName",
            roborazziOptions = RoborazziOptions(
                compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0f),
            ),
        )
        return remoteView
    }

    private fun configuredContext(
        languageTag: String = "en-US",
        dark: Boolean = false,
        fontScale: Float = 1f,
    ): Context {
        val base = RuntimeEnvironment.getApplication()
        val locale = Locale.forLanguageTag(languageTag)
        val configuration = Configuration(base.resources.configuration).apply {
            setLocales(LocaleList(locale))
            setLayoutDirection(locale)
            this.fontScale = fontScale
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) {
                Configuration.UI_MODE_NIGHT_YES
            } else {
                Configuration.UI_MODE_NIGHT_NO
            }
        }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        return ContextThemeWrapper(activity, android.R.style.Theme_DeviceDefault).apply {
            applyOverrideConfiguration(configuration)
        }
    }

    private fun attentionContent(surface: AttentionWidgetSurface) = AttentionWidgetContent(
        revision = 7,
        size = AttentionWidgetSize.EXPANDED,
        surface = surface,
        entries = listOf(
            AttentionWidgetEntry(
                id = "attention-1",
                title = "Review backup access",
                summary = PROTECTED_SUMMARY,
                urgency = AttentionUrgency.HIGH,
                ageMillis = 1_000,
                canOpen = true,
                canAcknowledge = true,
                canDefer = true,
                canMute = true,
            ),
            AttentionWidgetEntry(
                id = "attention-2",
                title = "Confirm deployment window",
                summary = null,
                urgency = AttentionUrgency.NORMAL,
                ageMillis = 2_000,
                canOpen = true,
                canAcknowledge = true,
                canDefer = true,
                canMute = false,
            ),
            AttentionWidgetEntry(
                id = "attention-3",
                title = "Agent needs a decision",
                summary = null,
                urgency = AttentionUrgency.NORMAL,
                ageMillis = 3_000,
                canOpen = true,
                canAcknowledge = true,
                canDefer = false,
                canMute = false,
            ),
        ),
        hasMore = false,
        stale = false,
    )

    private fun assertNoVisibleActions(root: View) {
        listOf(
            R.id.home_widget_acknowledge,
            R.id.home_widget_defer,
            R.id.home_widget_mute,
        ).forEach { viewId -> assertEquals(View.GONE, root.findViewById<View>(viewId).visibility) }
    }

    private fun assertText(root: View, viewId: Int, expected: String) {
        assertEquals(expected, root.findViewById<TextView>(viewId).text.toString())
    }

    private fun assertThemeContrast(context: Context) {
        val background = ContextCompat.getColor(context, R.color.widget_background)
        listOf(
            R.color.widget_primary_text,
            R.color.widget_secondary_text,
            R.color.widget_action_text,
        ).forEach { colorId ->
            assertTrue(
                "Widget foreground must meet WCAG AA contrast",
                ColorUtils.calculateContrast(ContextCompat.getColor(context, colorId), background) >= 4.5,
            )
        }
    }

    private fun visibleText(root: View): List<String> = root.descendants()
        .filterIsInstance<TextView>()
        .filter { it.visibility == View.VISIBLE }
        .map { it.text.toString() }
        .toList()

    private fun View.assertVisibleDescendantsAreBounded() {
        descendants().filter { it.visibility == View.VISIBLE }.forEach { child ->
            val label = if (child.id == View.NO_ID) {
                child.javaClass.simpleName
            } else {
                child.resources.getResourceEntryName(child.id)
            }
            assertTrue("Visible widget child has no width: $label", child.width > 0)
            assertTrue("Visible widget child has no height: $label", child.height > 0)
            assertTrue("Visible widget child exceeds horizontal bounds", child.left >= 0 && child.right <= width)
            assertTrue("Visible widget child exceeds vertical bounds", child.top >= 0 && child.bottom <= height)
        }
    }

    private fun View.descendants(): Sequence<View> = sequence {
        if (this@descendants is ViewGroup) {
            repeat(childCount) { index ->
                val child = getChildAt(index)
                yield(child)
                yieldAll(child.descendants())
            }
        }
    }

    private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun exactly(value: Int) = View.MeasureSpec.makeMeasureSpec(value, View.MeasureSpec.EXACTLY)

    private val issuer = AttentionWidgetActionRequestIssuer { itemId, revision, action ->
        AttentionWidgetActionRequest(
            requestId = "widget_action_v1_snapshot0001",
            itemId = itemId,
            snapshotRevision = revision,
            authorityGeneration = 1,
            action = action,
            issuedAtEpochMillis = 100,
            expiresAtEpochMillis = 200,
            authenticationTag = "authenticated-tag",
        )
    }

    private companion object {
        const val PROTECTED_SUMMARY = "Private upstream context must never render on the lock screen"
    }
}
