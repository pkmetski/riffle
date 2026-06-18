package com.riffle.app.feature.reader

import android.graphics.RectF
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReaderCoordinatesTest {

    // ── toWindowIntRect ──────────────────────────────────────────────────────

    @Test
    fun `toWindowIntRect adds view offset to rect coordinates`() {
        val rect = RectF(10f, 20f, 50f, 60f)
        val result = rect.toWindowIntRect(viewLeft = 100, viewTop = 200)
        assertEquals(IntRect(left = 110, top = 220, right = 150, bottom = 260), result)
    }

    @Test
    fun `toWindowIntRect rounds float coordinates`() {
        val rect = RectF(10.4f, 20.6f, 50.3f, 60.7f)
        val result = rect.toWindowIntRect(viewLeft = 0, viewTop = 0)
        assertEquals(IntRect(left = 10, top = 21, right = 50, bottom = 61), result)
    }

    // ── HighlightPopupPositionProvider ───────────────────────────────────────

    private fun provider(anchorRect: IntRect, margin: Int = 8) =
        HighlightPopupPositionProvider(anchorRect, margin)

    private fun position(
        provider: HighlightPopupPositionProvider,
        windowSize: IntSize,
        popupSize: IntSize,
    ): IntOffset = provider.calculatePosition(
        anchorBounds = IntRect.Zero,
        windowSize = windowSize,
        layoutDirection = LayoutDirection.Ltr,
        popupContentSize = popupSize,
    )

    @Test
    fun `popup positioned above anchor when space available`() {
        // anchorTop=300, popupHeight=100, margin=8 → preferredTop = 300-100-8 = 192
        val anchor = IntRect(left = 100, top = 300, right = 200, bottom = 320)
        val result = position(provider(anchor), IntSize(400, 800), IntSize(280, 100))
        assertEquals(192, result.y)
    }

    @Test
    fun `popup flips below anchor when not enough space above`() {
        // anchorTop=80, popupHeight=100, margin=8 → preferredTop = -28 < 8 → flip: 100+8=108
        val anchor = IntRect(left = 100, top = 80, right = 200, bottom = 100)
        val result = position(provider(anchor), IntSize(400, 800), IntSize(280, 100))
        assertEquals(108, result.y)
    }

    @Test
    fun `popup centred on anchor horizontally`() {
        // anchorCentreX=150, popupWidth=100 → left = 150-50 = 100; within [8, 292] → 100
        val anchor = IntRect(left = 100, top = 300, right = 200, bottom = 320)
        val result = position(provider(anchor), IntSize(400, 800), IntSize(100, 100))
        assertEquals(100, result.x)
    }

    @Test
    fun `popup clamped to left margin when anchor is near left edge`() {
        // anchorCentreX=10, popupWidth=200 → centreX = 10-100 = -90, clamped to 8
        val anchor = IntRect(left = 5, top = 300, right = 15, bottom = 320)
        val result = position(provider(anchor, margin = 8), IntSize(400, 800), IntSize(200, 100))
        assertEquals(8, result.x)
    }

    @Test
    fun `popup clamped to right margin when anchor is near right edge`() {
        // anchorCentreX=395, popupWidth=200 → centreX=295, maxLeft=max(8,400-200-8)=192 → 192
        val anchor = IntRect(left = 390, top = 300, right = 400, bottom = 320)
        val result = position(provider(anchor, margin = 8), IntSize(400, 800), IntSize(200, 100))
        assertEquals(192, result.x)
    }

    @Test
    fun `popup max-left guard prevents crash on very narrow window`() {
        // Window width 100, popup width 150, margin 8 → maxLeft = max(8, 100-150-8)=max(8,-58)=8
        // centreX = 50-75 = -25, clamped to 8 (not crash from min>max)
        val anchor = IntRect(left = 40, top = 300, right = 60, bottom = 320)
        val result = position(provider(anchor, margin = 8), IntSize(100, 800), IntSize(150, 100))
        assertEquals(8, result.x)
    }
}
