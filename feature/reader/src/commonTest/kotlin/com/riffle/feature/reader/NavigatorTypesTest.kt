package com.riffle.feature.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NavigatorTypesTest {

    @Test
    fun `NavigatorPosition holds locatorJson round-trip`() {
        val json = """{"href":"chapter1.xhtml","type":"application/xhtml+xml","locations":{"progression":0.5}}"""
        val pos = NavigatorPosition(
            href = "chapter1.xhtml",
            progression = 0.5f,
            totalProgression = null,
            locatorJson = json,
        )
        assertEquals(json, pos.locatorJson)
        assertEquals("chapter1.xhtml", pos.href)
        assertEquals(0.5f, pos.progression)
        assertNull(pos.totalProgression)
    }

    @Test
    fun `NavigatorDecoration Highlight holds color and locatorJson`() {
        val json = """{"href":"ch1.xhtml","locations":{}}"""
        val dec = NavigatorDecoration.Highlight(id = "ann-1", locatorJson = json, color = "#FFFF00", alpha = 0.4f)
        assertEquals("ann-1", dec.id)
        assertEquals("#FFFF00", dec.color)
        assertEquals(json, dec.locatorJson)
        assertEquals(0.4f, dec.alpha)
    }

    @Test
    fun `NavigatorScrollBoundary None has both flags false`() {
        val boundary = NavigatorScrollBoundary.None
        assertEquals(false, boundary.atForwardBoundary)
        assertEquals(false, boundary.atBackwardBoundary)
    }

    @Test
    fun `NavigatorNavigationTarget ToLocatorJson holds locatorJson`() {
        val json = """{"href":"ch2.xhtml","locations":{}}"""
        val target = NavigatorNavigationTarget.ToLocatorJson(json)
        assertEquals(json, target.locatorJson)
    }

    @Test
    fun `NavigatorNavigationTarget ToHref holds href and optional fragment`() {
        val target = NavigatorNavigationTarget.ToHref("ch3.xhtml", "section-2")
        assertEquals("ch3.xhtml", target.href)
        assertEquals("section-2", target.fragment)
    }

    @Test
    fun `NavigatorNavigationOptions defaults`() {
        val opts = NavigatorNavigationOptions()
        assertEquals(true, opts.snap)
        assertEquals(true, opts.landAtStartWhenNoTarget)
        assertEquals(false, opts.snapProgressionToNearestColumn)
        assertEquals(true, opts.animated)
        assertEquals(false, opts.alignToTop)
        assertNull(opts.focusAnnotationId)
    }

    @Test
    fun `NavigatorFollowResult values are distinct`() {
        val values = NavigatorFollowResult.entries
        assertEquals(3, values.size)
        assert(NavigatorFollowResult.Snapped in values)
        assert(NavigatorFollowResult.OffPage in values)
        assert(NavigatorFollowResult.Unavailable in values)
    }

    @Test
    fun `NavigatorPageLoad value class wraps int`() {
        val load = NavigatorPageLoad(42)
        assertEquals(42, load.generation)
    }
}
