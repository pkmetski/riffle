package com.riffle.app.feature.reader

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the figure-zoom feature. Two pure helpers back the whole feature:
 *  - [FigureTapMessageParser.parse] — accepts the JSON payload from figure-tap.js and turns it
 *    into a typed [FigureZoomState]. A schema drift on the JS side must fail loudly here rather
 *    than silently no-op the tap.
 *  - [clampPanZoom] — the pinch/pan clamp inside [FigureZoomOverlay]. If the clamp lets the image
 *    leave the viewport, a user can pinch-and-lose their tap target with nothing left to tap on to
 *    dismiss.
 *  - [fitImageIntoViewport] — the initial-fit computation. Fitting to the WRONG axis on a wide
 *    landscape image would leave the image extending beyond the viewport at scale=1, which the
 *    clamp would then treat as legitimately zoomed.
 *
 * Each assertion is written so a revert of the corresponding production code would flip it red.
 */
class FigureZoomTest {

    // ── Parser ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `parse img payload returns href and natural dimensions`() {
        val json = JSONObject()
            .put("kind", "img")
            .put("href", "images/fig1.jpg")
            .put("w", 800)
            .put("h", 600)
            .toString()
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
        val json = JSONObject().put("kind", "svg").put("svg", svg).put("w", 100).put("h", 100).toString()
        val parsed = FigureTapMessageParser.parse(json)
        assertNotNull(parsed)
        assertEquals(svg, parsed!!.svgMarkup)
        assertEquals("", parsed.href)
    }

    @Test
    fun `parse rejects zero-sized figures`() {
        val json = JSONObject().put("kind", "img").put("href", "a.png").put("w", 0).put("h", 100).toString()
        assertNull(FigureTapMessageParser.parse(json))
    }

    @Test
    fun `parse rejects blank input`() {
        assertNull(FigureTapMessageParser.parse(null))
        assertNull(FigureTapMessageParser.parse(""))
        assertNull(FigureTapMessageParser.parse("not-json"))
    }

    @Test
    fun `parse rejects img without href`() {
        val json = JSONObject().put("kind", "img").put("w", 100).put("h", 100).toString()
        assertNull(FigureTapMessageParser.parse(json))
    }

    @Test
    fun `parse rejects svg without markup`() {
        val json = JSONObject().put("kind", "svg").put("w", 100).put("h", 100).toString()
        assertNull(FigureTapMessageParser.parse(json))
    }

    // ── Fit-into-viewport ─────────────────────────────────────────────────────────────────────

    @Test
    fun `fit prefers width for landscape image on portrait viewport`() {
        // Landscape image (2:1), portrait viewport (1:2) — width binds; height at half the width.
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
        val below = clampPanZoom(
            scale = 0.1f,
            translationX = 0f, translationY = 0f,
            fittedWidth = 100f, fittedHeight = 100f,
            viewportWidth = 200f, viewportHeight = 200f,
        )
        assertEquals(1f, below.scale, 0.0001f)

        val above = clampPanZoom(
            scale = 99f,
            translationX = 0f, translationY = 0f,
            fittedWidth = 100f, fittedHeight = 100f,
            viewportWidth = 200f, viewportHeight = 200f,
        )
        assertEquals(5f, above.scale, 0.0001f)
    }

    @Test
    fun `clamp forbids pan when image smaller than viewport`() {
        // At scale = 1, the fitted image is smaller than viewport in both axes — pan must be 0.
        val clamped = clampPanZoom(
            scale = 1f,
            translationX = 500f, translationY = -300f,
            fittedWidth = 100f, fittedHeight = 100f,
            viewportWidth = 400f, viewportHeight = 400f,
        )
        assertEquals(0f, clamped.translationX, 0.0001f)
        assertEquals(0f, clamped.translationY, 0.0001f)
    }

    @Test
    fun `clamp allows pan up to half the excess when zoomed`() {
        // fitted 200x200, viewport 200x200, zoomed 2x → scaled 400x400, excess 200 in each axis.
        // Max pan = 100 in each direction (half the excess).
        val extreme = clampPanZoom(
            scale = 2f,
            translationX = 500f, translationY = 500f,
            fittedWidth = 200f, fittedHeight = 200f,
            viewportWidth = 200f, viewportHeight = 200f,
        )
        assertEquals(100f, extreme.translationX, 0.0001f)
        assertEquals(100f, extreme.translationY, 0.0001f)

        val negative = clampPanZoom(
            scale = 2f,
            translationX = -500f, translationY = -500f,
            fittedWidth = 200f, fittedHeight = 200f,
            viewportWidth = 200f, viewportHeight = 200f,
        )
        assertEquals(-100f, negative.translationX, 0.0001f)
        assertEquals(-100f, negative.translationY, 0.0001f)
    }

    @Test
    fun `clamp allows moderate pans within bounds unchanged`() {
        val clamped = clampPanZoom(
            scale = 3f,
            translationX = 50f, translationY = -25f,
            fittedWidth = 100f, fittedHeight = 100f,
            viewportWidth = 100f, viewportHeight = 100f,
        )
        assertTrue(clamped.translationX in -100f..100f)
        assertEquals(50f, clamped.translationX, 0.0001f)
        assertEquals(-25f, clamped.translationY, 0.0001f)
    }
}
