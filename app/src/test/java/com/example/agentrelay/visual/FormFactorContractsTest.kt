/*
 * Copyright (C) Huawei Technologies Co., Ltd. 2026. All rights reserved.
 * SPDX-License-Identifier: MIT
 */

package com.example.agentrelay.visual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormFactorContractsTest {
    @Test
    fun `catalog covers phone tablet and folded postures deterministically`() {
        val catalog = FormFactorCatalog.current
        assertEquals(4, catalog.fixtures.size)
        assertEquals(FormFactorPosture.BOOK, catalog.fixture("foldable-book").posture)
        assertTrue(catalog.fixture("foldable-book").isFolded)
        assertTrue(catalog.canonicalJson().contains("foldable-tabletop"))
    }

    @Test
    fun `content cannot overlap hinge or escape window`() {
        val fixture = FormFactorCatalog.current.fixture("foldable-book")
        assertTrue(fixture.acceptsContentBounds(0, 0, 640, 800))
        assertFalse(fixture.acceptsContentBounds(640, 0, 40, 800))
        assertFalse(fixture.acceptsContentBounds(0, 0, 1_000, 800))
        assertEquals(664, fixture.paneWidthDp())
    }

    @Test
    fun `invalid fixture bounds posture and rotation fail closed`() {
        assertIllegalArgument {
            FormFactorFixture("bad", 100, 200, FormFactorPosture.FLAT, FormFactorWindowMode.FULLSCREEN, 0)
        }
        assertIllegalArgument {
            FormFactorFixture("bad-rotation", 360, 800, FormFactorPosture.FLAT, FormFactorWindowMode.FULLSCREEN, 45)
        }
        assertIllegalArgument {
            FormFactorFixture(
                "flat-hinge",
                800,
                800,
                FormFactorPosture.FLAT,
                FormFactorWindowMode.FULLSCREEN,
                0,
                hinge = HingeOcclusion(390, 0, 410, 800),
            )
        }
    }

    private fun assertIllegalArgument(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected contract rejection.
        }
    }
}
