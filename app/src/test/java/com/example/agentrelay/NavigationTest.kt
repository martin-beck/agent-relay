/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {
    @Test
    fun topLevelNavigationKeepsOneRootAndReplacesTheCurrentDestination() {
        val stack = mutableListOf<NavKey>(Main)

        navigateToTopLevel(stack, PinnedSessions)
        navigateToTopLevel(stack, NotificationCenter)

        assertEquals(listOf<NavKey>(Main, NotificationCenter), stack)
        assertTrue(canNavigateBack(stack))
    }

    @Test
    fun navigatingToTheCurrentRootDoesNotCreateDuplicateEntries() {
        val stack = mutableListOf<NavKey>(Main)

        navigateToTopLevel(stack, Main)

        assertEquals(listOf<NavKey>(Main), stack)
        assertFalse(canNavigateBack(stack))
    }

    @Test
    fun sessionDetailsAreReplacedByTheSelectedTopLevelDestination() {
        val key = "a".repeat(64)
        val stack = mutableListOf<NavKey>(Main, SessionDetails(key))

        navigateToTopLevel(stack, Settings)

        assertEquals(listOf<NavKey>(Main, Settings), stack)
    }

    @Test
    fun sessionDetailsKeepTheSessionsTabSelected() {
        assertEquals(Main, topLevelRoot(SessionDetails("b".repeat(64))))
        assertEquals(NotificationCenter, topLevelRoot(NotificationCenter))
    }
}
