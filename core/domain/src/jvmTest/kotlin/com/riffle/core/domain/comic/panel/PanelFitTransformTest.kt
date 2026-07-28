package com.riffle.core.domain.comic.panel

import org.junit.Assert.assertEquals
import org.junit.Test

class PanelFitTransformTest {

    @Test
    fun `panel already filling the page yields no zoom`() {
        // Image is 400x560; panel is the whole page; viewport matches image aspect ratio.
        val t = PanelFitTransform.compute(
            viewportWidth = 400,
            viewportHeight = 560,
            imageWidth = 400,
            imageHeight = 560,
            panel = PanelRegion(0, 0, 400, 560),
        )
        assertEquals(1f, t.scale, 0.001f)
        assertEquals(0f, t.translationX, 0.001f)
        assertEquals(0f, t.translationY, 0.001f)
    }

    @Test
    fun `centered half-page panel doubles the scale`() {
        // Image 400x400, viewport 400x400 (no letterboxing at fit-whole).
        // Panel is centred, 200x200 → half the page in each dimension.
        // Fitting the panel to viewport requires scale = 2.
        val t = PanelFitTransform.compute(
            viewportWidth = 400,
            viewportHeight = 400,
            imageWidth = 400,
            imageHeight = 400,
            panel = PanelRegion(100, 100, 200, 200),
        )
        assertEquals(2f, t.scale, 0.001f)
        // Panel centroid at fit-whole is (200,200), which equals the viewport centre → no translation.
        assertEquals(0f, t.translationX, 0.001f)
        assertEquals(0f, t.translationY, 0.001f)
    }

    @Test
    fun `top-left panel translates content down-right so the panel lands centred`() {
        // Image 400x400, viewport 400x400. Panel at top-left, 200x200.
        // Panel centroid at fit-whole: (100, 100). Viewport centre: (200, 200).
        // Zoom = 2. tx = 2 * (200 - 100) = 200. Same for ty.
        val t = PanelFitTransform.compute(
            viewportWidth = 400,
            viewportHeight = 400,
            imageWidth = 400,
            imageHeight = 400,
            panel = PanelRegion(0, 0, 200, 200),
        )
        assertEquals(2f, t.scale, 0.001f)
        assertEquals(200f, t.translationX, 0.001f)
        assertEquals(200f, t.translationY, 0.001f)
    }

    @Test
    fun `letterboxed viewport - image taller than viewport aspect`() {
        // Viewport 800x400 (2:1) but image 400x400 (square). Fit-whole scales image by 400/400=1
        // on height, and 800/400=2 on width — take min → fitScale=1. Image displays at 400x400
        // centred in viewport → letterbox 200 px on each side.
        // Panel is centred at (100,100,200,200): centroid at fit-whole is
        //   (letterboxX + 200 * 1, letterboxY + 200 * 1) = (200 + 200, 0 + 200) = (400, 200).
        // Viewport centre: (400, 200) → no translation.
        // Zoom to fit panel: panelDisplayed=200x200; viewport 800x400 → min(800/200, 400/200) = 2.
        val t = PanelFitTransform.compute(
            viewportWidth = 800,
            viewportHeight = 400,
            imageWidth = 400,
            imageHeight = 400,
            panel = PanelRegion(100, 100, 200, 200),
        )
        assertEquals(2f, t.scale, 0.001f)
        assertEquals(0f, t.translationX, 0.001f)
        assertEquals(0f, t.translationY, 0.001f)
    }

    @Test
    fun `zero viewport returns identity`() {
        val t = PanelFitTransform.compute(0, 0, 400, 560, PanelRegion(0, 0, 100, 100))
        assertEquals(PanelFitTransform.Identity, t)
    }

    @Test
    fun `zero image dimensions return identity`() {
        val t = PanelFitTransform.compute(400, 560, 0, 0, PanelRegion(0, 0, 100, 100))
        assertEquals(PanelFitTransform.Identity, t)
    }
}
