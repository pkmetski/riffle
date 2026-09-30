package com.riffle.feature.reader

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression coverage for the figure-zoom feature: [FigureTapMessageParser], [clampPanZoom], and
 * [fitImageIntoViewport]. In commonTest so the same assertions run on both Android and iOS.
 */
class FigureZoomTest {

    // ── Parser ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `parse img payload returns href and natural dimensions`() {
        val json = """{"kind":"img","href":"images/fig1.jpg","w":800,"h":600}"""
        val parsed = FigureTapMessageParser.parse(json)
        assertNotNull(parsed)
        assertEquals("images/fig1.jpg", parsed!!.href)
        assertEquals(800, parsed.naturalWidth)
        assertEquals(600, parsed.naturalHeight)
        assertNull(parsed.svgMarkup)
    }

    @Test
    fun `parse svg payload returns markup and skips href`() {
        val svg = "<svg width='100' height='100'><rect width='100' height='100'/></svg>"
        val json = """{"kind":"svg","svg":"$svg","w":100,"h":100}"""
        val parsed = FigureTapMessageParser.parse(json)
        assertNotNull(parsed)
        assertEquals(svg, parsed!!.svgMarkup)
        assertEquals("", parsed.href)
    }

    @Test
    fun parseDimensionsWithFloatJsonValues() {
        // Some browsers encode naturalWidth as a float (e.g. 800.0); parser must not crash.
        val parsed = FigureTapMessageParser.parse("""{"kind":"img","href":"a.png","w":800.0,"h":600.0}""")
        assertNotNull(parsed)
        assertEquals(800, parsed!!.naturalWidth)
        assertEquals(600, parsed.naturalHeight)
    }

    @Test
    fun `parse rejects zero-sized figures`() {
        assertNull(FigureTapMessageParser.parse("""{"kind":"img","href":"a.png","w":0,"h":100}"""))
    }

    @Test
    fun `parse rejects blank input`() {
        assertNull(FigureTapMessageParser.parse(null))
        assertNull(FigureTapMessageParser.parse(""))
        assertNull(FigureTapMessageParser.parse("not-json"))
    }

    @Test
    fun `parse rejects img without href`() {
        assertNull(FigureTapMessageParser.parse("""{"kind":"img","w":100,"h":100}"""))
    }

    @Test
    fun `parse rejects svg without markup`() {
        assertNull(FigureTapMessageParser.parse("""{"kind":"svg","w":100,"h":100}"""))
    }

    // ── Fit-into-viewport ─────────────────────────────────────────────────────────────────────

    @Test
    fun `fit prefers width for landscape image on portrait viewport`() {
        val fit = fitImageIntoViewport(2000, 1000, viewportWidth = 800f, viewportHeight = 1600f)
        assertEquals(800, fit.width)
        assertEquals(400, fit.height)
    }

    @Test
    fun `fit prefers height for portrait image on landscape viewport`() {
        val fit = fitImageIntoViewport(500, 1000, viewportWidth = 1600f, viewportHeight = 800f)
        assertEquals(400, fit.width)
        assertEquals(800, fit.height)
    }

    @Test
    fun `fit returns empty on degenerate inputs`() {
        assertEquals(0, fitImageIntoViewport(0, 100, 100f, 100f).width)
        assertEquals(0, fitImageIntoViewport(100, 100, 0f, 100f).width)
    }

    // ── PanZoom clamp ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `clamp scale to bounds`() {
        val below = clampPanZoom(0.1f, 0f, 0f, 100f, 100f, 200f, 200f)
        assertApprox(1f, below.scale)

        val above = clampPanZoom(99f, 0f, 0f, 100f, 100f, 200f, 200f)
        assertApprox(5f, above.scale)
    }

    @Test
    fun `clamp forbids pan when image smaller than viewport`() {
        val clamped = clampPanZoom(1f, 500f, -300f, 100f, 100f, 400f, 400f)
        assertApprox(0f, clamped.translationX)
        assertApprox(0f, clamped.translationY)
    }

    @Test
    fun `clamp allows pan up to half the excess when zoomed`() {
        val extreme = clampPanZoom(2f, 500f, 500f, 200f, 200f, 200f, 200f)
        assertApprox(100f, extreme.translationX)
        assertApprox(100f, extreme.translationY)

        val negative = clampPanZoom(2f, -500f, -500f, 200f, 200f, 200f, 200f)
        assertApprox(-100f, negative.translationX)
        assertApprox(-100f, negative.translationY)
    }

    @Test
    fun `clamp allows moderate pans within bounds unchanged`() {
        val clamped = clampPanZoom(3f, 50f, -25f, 100f, 100f, 100f, 100f)
        assertTrue(clamped.translationX in -100f..100f)
        assertApprox(50f, clamped.translationX)
        assertApprox(-25f, clamped.translationY)
    }

    private fun assertApprox(expected: Float, actual: Float, eps: Float = 0.0001f) {
        assertTrue(abs(expected - actual) < eps, "expected $expected ± $eps but was $actual")
    }
}
