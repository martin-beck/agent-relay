package com.example.agentrelay.ui.main

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.InternalRoborazziApi
import com.github.takahirom.roborazzi.fetchRobolectricWindowRoots
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

internal object ProfileScreenshotAssertions {

    @OptIn(ExperimentalRoborazziApi::class, InternalRoborazziApi::class)
    fun composeRoots(): List<RootForTest> =
        fetchRobolectricWindowRoots().flatMap { root ->
            buildList {
                collectComposeRoots(root.decorView, this)
            }
        }

    fun assertWindowAccessibilityBounds(
        languageTag: String,
        composeRoots: List<RootForTest>,
        renderContext: Context,
        expectedLayoutDirection: LayoutDirection,
        requiredVisibleStringResources: List<Int>,
        requiredEditableLabelStringResources: List<Int>,
        requiredSelectedOptionStringResources: List<Int>,
    ) {
        assertTrue("$languageTag must expose Compose window roots", composeRoots.isNotEmpty())
        val textNodes = mutableListOf<Pair<SemanticsNode, SemanticsNode>>()
        composeRoots.forEach { composeRoot ->
            val root = composeRoot.semanticsOwner.unmergedRootSemanticsNode
            val rootTextNodes = composeRoot.semanticsOwner
                .getAllSemanticsNodes(mergingEnabled = false)
                .filter { node ->
                    node.layoutInfo.isPlaced &&
                        node.boundsInRoot.width > 0f &&
                        node.config.contains(SemanticsProperties.Text)
                }
            rootTextNodes.forEach { node ->
                assertEquals(
                    "$languageTag visible text must use $expectedLayoutDirection: " +
                        node.config.getOrElseNullable(SemanticsProperties.Text) { null },
                    expectedLayoutDirection,
                    node.layoutInfo.layoutDirection,
                )
            }
            textNodes += rootTextNodes.map { node -> node to root }
        }
        val rootNodeCounts = composeRoots.map { root ->
            root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = false).size
        }
        assertTrue(
            "$languageTag must expose semantic text; roots=${composeRoots.size}, nodes=$rootNodeCounts",
            textNodes.isNotEmpty(),
        )
        val renderedSemanticCopy = textNodes.flatMap { (node, _) ->
            node.config[SemanticsProperties.Text].map { text -> text.text }
        } + composeRoots.flatMap { composeRoot ->
            composeRoot.semanticsOwner
                .getAllSemanticsNodes(mergingEnabled = true)
                .filter(::isPlacedSemanticNode)
                .flatMap(::contentDescriptions)
        }
        requiredVisibleStringResources.forEach { stringResource ->
            val expected = renderContext.getString(stringResource)
            assertTrue(
                "$languageTag must display localized semantic text: $expected",
                renderedSemanticCopy.any { actual -> expected in actual },
            )
        }
        val semanticNodes = composeRoots
            .flatMap { composeRoot ->
                composeRoot.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
            }
            .filter(::isPlacedSemanticNode)
        requiredEditableLabelStringResources.forEach { labelResource ->
            val expected = renderContext.getString(
                com.example.agentrelay.R.string.profile_editor_required,
                renderContext.getString(labelResource),
            )
            val matches = semanticNodes.filter { node ->
                node.config.getOrElseNullable(
                    SemanticsProperties.ContentDescription,
                ) { null }.orEmpty() == listOf(expected) &&
                    node.config.contains(SemanticsActions.SetText)
            }
            assertEquals(
                "$languageTag must attach the required label to an editable field: $expected",
                1,
                matches.size,
            )
        }
        if (requiredSelectedOptionStringResources.isNotEmpty()) {
            assertTrue(
                "$languageTag choice fields must expose a selectable group",
                semanticNodes.any { node ->
                    node.config.contains(SemanticsProperties.SelectableGroup)
                },
            )
        }
        requiredSelectedOptionStringResources.forEach { optionResource ->
            val expected = renderContext.getString(optionResource)
            assertTrue(
                "$languageTag must expose the selected radio option: $expected",
                semanticNodes.any { node ->
                    node.config.getOrElseNullable(SemanticsProperties.Role) { null } ==
                        Role.RadioButton &&
                        node.config.getOrElseNullable(SemanticsProperties.Selected) { null } == true &&
                        node.config.contains(SemanticsActions.OnClick) &&
                        expected in collectSemanticText(node)
                },
            )
        }
        textNodes.forEach { (node, root) ->
            val bounds = node.boundsInRoot
            val viewport = root.boundsInRoot
            assertTrue(
                "$languageTag semantic text is outside $viewport: $bounds",
                bounds.width > 0f &&
                    bounds.height > 0f &&
                    bounds.left >= viewport.left - BOUNDS_TOLERANCE_PX &&
                    bounds.right <= viewport.right + BOUNDS_TOLERANCE_PX &&
                    bounds.top >= viewport.top - BOUNDS_TOLERANCE_PX &&
                    bounds.bottom <= viewport.bottom + BOUNDS_TOLERANCE_PX,
            )
            assertTextLayoutDoesNotOverflow(languageTag, node)
        }
    }

    fun assertProfileActionOrder(
        languageTag: String,
        renderContext: Context,
        composeRoots: List<RootForTest>,
    ) {
        val clickableNodes = composeRoots.flatMap { root ->
            root.semanticsOwner.getAllSemanticsNodes(mergingEnabled = true)
                .filter { node ->
                    isPlacedSemanticNode(node) &&
                        node.config.getOrElseNullable(SemanticsProperties.Role) { null } == Role.Button &&
                        node.config.contains(SemanticsActions.OnClick)
                }
                .map { node -> root to node }
        }
        fun action(stringResource: Int): Pair<RootForTest, SemanticsNode> {
            val expected = renderContext.getString(stringResource)
            val matches = clickableNodes.filter { (_, node) ->
                expected in collectSemanticText(node)
            }
            assertEquals(
                "$languageTag must expose one clickable profile action: $expected",
                1,
                matches.size,
            )
            return matches.single()
        }
        val save = action(com.example.agentrelay.R.string.profile_editor_save)
        val close = action(com.example.agentrelay.R.string.action_close)
        val delete = action(com.example.agentrelay.R.string.action_delete)
        assertTrue(
            "$languageTag profile actions must share one dialog root",
            save.first === close.first && close.first === delete.first,
        )
        val yCoordinates = listOf(
            save.second.boundsInRoot.center.y,
            close.second.boundsInRoot.center.y,
            delete.second.boundsInRoot.center.y,
        )
        assertTrue(
            "$languageTag profile actions must share one row: $yCoordinates",
            yCoordinates.max() - yCoordinates.min() <= ACTION_ROW_TOLERANCE_PX,
        )
        assertTrue(
            "$languageTag RTL profile actions must mirror Save, Close, Delete",
            save.second.boundsInRoot.center.x < close.second.boundsInRoot.center.x &&
                close.second.boundsInRoot.center.x < delete.second.boundsInRoot.center.x,
        )
    }

    fun assertTextLayoutDoesNotOverflow(
        languageTag: String,
        node: SemanticsNode,
    ) {
        if (!node.config.contains(SemanticsActions.GetTextLayoutResult)) return
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        results.forEach { result ->
            assertFalse(
                "$languageTag text height overflows: ${result.layoutInput.text}",
                result.didOverflowHeight,
            )
            repeat(result.lineCount) { line ->
                val left = result.getLineLeft(line)
                val right = result.getLineRight(line)
                val fitsWidth = if (node.layoutInfo.layoutDirection == LayoutDirection.Rtl) {
                    right - left <= result.size.width + BOUNDS_TOLERANCE_PX
                } else {
                    left >= -BOUNDS_TOLERANCE_PX &&
                        right <= result.size.width + BOUNDS_TOLERANCE_PX
                }
                assertTrue(
                    "$languageTag text line overflows horizontally: " +
                        "[$left, $right] outside width ${result.size.width} for ${result.layoutInput.text}",
                    fitsWidth,
                )
                assertFalse(
                    "$languageTag text line is ellipsized: ${result.layoutInput.text}",
                    result.isLineEllipsized(line),
                )
            }
        }
    }

    private fun collectComposeRoots(
        view: View,
        destination: MutableList<RootForTest>,
    ) {
        if (view is RootForTest) {
            destination += view
        }
        if (view is ViewGroup) {
            repeat(view.childCount) { index ->
                collectComposeRoots(view.getChildAt(index), destination)
            }
        }
    }

    private fun isPlacedSemanticNode(node: SemanticsNode): Boolean =
        node.layoutInfo.isPlaced &&
            node.boundsInRoot.width > 0f &&
            node.boundsInRoot.height > 0f

    private fun contentDescriptions(node: SemanticsNode): List<String> =
        node.config.getOrElseNullable(SemanticsProperties.ContentDescription) { null }
            .orEmpty()

    private fun collectSemanticText(node: SemanticsNode): List<String> =
        node.config.getOrElseNullable(SemanticsProperties.Text) { null }
            .orEmpty()
            .map { text -> text.text } +
            node.children.flatMap(::collectSemanticText)

    private const val BOUNDS_TOLERANCE_PX = 1f
    private const val ACTION_ROW_TOLERANCE_PX = 4f
}
