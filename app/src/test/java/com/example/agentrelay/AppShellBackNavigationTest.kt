/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppShellBackNavigationTest {
    @Test
    fun rootDestinationIsNotPopped() {
        assertFalse(canPopAppShell(0))
        assertFalse(canPopAppShell(1))
    }

    @Test
    fun detailDestinationCanBePopped() {
        assertTrue(canPopAppShell(2))
        assertTrue(canPopAppShell(3))
    }
}
