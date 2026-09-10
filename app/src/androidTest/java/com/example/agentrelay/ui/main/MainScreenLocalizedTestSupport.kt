/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.rules.ActivityScenarioRule

internal typealias MainScreenComposeRule =
    AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity>

internal fun MainScreenComposeRule.resourceText(
    @StringRes resource: Int,
    vararg formatArgs: Any,
): String = activity.getString(resource, *formatArgs)

internal fun MainScreenComposeRule.profileOperation(
    @StringRes labelResource: Int,
): SemanticsNodeInteraction = scrollProfileToText(resourceText(labelResource))

internal fun MainScreenComposeRule.scrollProfileToText(
    text: String,
): SemanticsNodeInteraction {
    onNodeWithTag(PROFILE_EDITOR_LIST_TEST_TAG).performScrollToNode(hasText(text))
    return onNodeWithText(text)
}
