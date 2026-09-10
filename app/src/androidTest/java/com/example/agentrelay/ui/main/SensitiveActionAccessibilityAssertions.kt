/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.ui.main

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.platform.app.InstrumentationRegistry
import com.example.agentrelay.R

internal fun assertSensitiveActionAccessibility(
    root: SemanticsNodeInteraction,
    nodeWithText: (String) -> SemanticsNodeInteraction,
) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val submit = context.getString(R.string.session_action_decision_submit)
    val confirm = context.getString(R.string.session_action_confirm_decision, submit)
    root.tryPerformAccessibilityChecks()
    nodeWithText("Focused tests")
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        .performScrollTo()
        .performClick()
    nodeWithText(submit)
        .performScrollTo()
        .performClick()
    nodeWithText(context.getString(R.string.session_action_confirm_title))
        .assertIsDisplayed()
        .tryPerformAccessibilityChecks()
    nodeWithText(confirm).assertIsDisplayed()
    nodeWithText(context.getString(R.string.action_go_back)).assertIsDisplayed()
}
