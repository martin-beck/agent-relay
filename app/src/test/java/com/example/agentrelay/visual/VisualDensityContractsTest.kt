package com.example.agentrelay.visual

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualDensityContractsTest {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    @Test
    fun `catalog is versioned unique and machine readable`() {
        val decoded = json.decodeFromString<VisualDensityCatalog>(VisualDensityCatalog.current.canonicalJson())
        assertEquals(1, decoded.schemaVersion)
        assertEquals(3, decoded.layouts.size)
        assertEquals(decoded.layouts.size, decoded.layouts.map { it.id }.toSet().size)
    }

    @Test
    fun `compact and expanded layouts have different reflow budgets`() {
        val compact = VisualDensityCatalog.current.layout("session-hub-compact")
        val expanded = VisualDensityCatalog.current.layout("session-hub-expanded")
        assertEquals(ViewportClass.COMPACT, compact.viewport)
        assertEquals(ViewportClass.EXPANDED, expanded.viewport)
        assertTrue(expanded.budget.maxContentGroups > compact.budget.maxContentGroups)
        assertTrue(compact.supportedTextScales.contains(TextScale.EXTRA_LARGE))
    }

    @Test
    fun `budgets reject clipping overlap truncation and hidden primary actions`() {
        val contract = VisualDensityCatalog.current.layout("session-hub-compact")
        assertTrue(contract.accepts(VisualDensityMeasure(contentGroups = 6, scrollsToPrimaryAction = 2)))
        assertFalse(contract.accepts(VisualDensityMeasure(contentGroups = 6, scrollsToPrimaryAction = 2, clippedElements = 1)))
        assertFalse(contract.accepts(VisualDensityMeasure(contentGroups = 6, scrollsToPrimaryAction = 2, primaryActionVisible = false)))
    }

    @Test
    fun `large text contract raises body text floor and preserves touch target`() {
        val contract = VisualDensityCatalog.current.layout("connection-form-large-text")
        assertFalse(contract.accepts(VisualDensityMeasure(contentGroups = 5, scrollsToPrimaryAction = 3, bodyTextSp = 14)))
        assertFalse(
            contract.accepts(
                VisualDensityMeasure(
                    contentGroups = 5,
                    scrollsToPrimaryAction = 3,
                    bodyTextSp = 16,
                    minTouchTargetDp = 44,
                ),
            ),
        )
        assertTrue(contract.accepts(VisualDensityMeasure(contentGroups = 5, scrollsToPrimaryAction = 3, bodyTextSp = 16)))
    }
}
