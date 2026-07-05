package com.riffle.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs

class FadingScrollbarMetricsTest {

    private fun assertClose(expected: Float, actual: Float, tol: Float = 0.001f) {
        assert(abs(expected - actual) <= tol) {
            "expected ~$expected but was $actual (tol=$tol)"
        }
    }

    // --- computeListScrollMetrics ---

    @Test
    fun `list metrics null when content fits in viewport`() {
        assertNull(
            computeListScrollMetrics(
                total = 5,
                viewport = 1000,
                visibleCount = 5,
                visibleSizeSum = 5 * 100L,
                firstVisibleIndex = 0,
                firstVisibleScrollOffset = 0,
                firstVisibleItemSize = 100,
            ),
        )
    }

    @Test
    fun `list metrics null on empty visible`() {
        assertNull(
            computeListScrollMetrics(
                total = 20, viewport = 500, visibleCount = 0, visibleSizeSum = 0L,
                firstVisibleIndex = 0, firstVisibleScrollOffset = 0, firstVisibleItemSize = 0,
            ),
        )
    }

    @Test
    fun `list metrics extent is viewport-over-content`() {
        // 20 items of 100px each = 2000 content, viewport 500 -> extent = 0.25
        val m = computeListScrollMetrics(
            total = 20, viewport = 500, visibleCount = 5, visibleSizeSum = 500L,
            firstVisibleIndex = 0, firstVisibleScrollOffset = 0, firstVisibleItemSize = 100,
        )!!
        assertClose(0.25f, m.extentFraction)
        assertClose(0f, m.offsetFraction)
    }

    @Test
    fun `list metrics offset advances with scroll offset within an item`() {
        val m = computeListScrollMetrics(
            total = 20, viewport = 500, visibleCount = 5, visibleSizeSum = 500L,
            firstVisibleIndex = 4, firstVisibleScrollOffset = 50, firstVisibleItemSize = 100,
        )!!
        // (4 + 50/100) / 20 = 4.5/20 = 0.225
        assertClose(0.225f, m.offsetFraction)
    }

    @Test
    fun `list metrics clamps offset to leave room for the thumb`() {
        val m = computeListScrollMetrics(
            total = 20, viewport = 500, visibleCount = 1, visibleSizeSum = 100L,
            firstVisibleIndex = 100, firstVisibleScrollOffset = 9999, firstVisibleItemSize = 100,
        )!!
        // Extent = 0.25 (500/2000). Max allowed offset = 1 - 0.25 = 0.75.
        assertClose(0.75f, m.offsetFraction)
    }

    @Test
    fun `list metrics offset stays continuous when avg item size shifts mid-scroll`() {
        // Regression: annotations list has 2-line bookmarks and 6-line highlights sharing a
        // LazyColumn. As tall highlights enter/leave the viewport the running avg shifts, and
        // the old avg-scaled offset would jitter mid-scroll. Now that offset is derived from
        // the first visible item's own size, two frames scrolling forward within the same item
        // must produce monotonically-increasing offsets even if avg changed between them.
        val itemSize = 300
        val a = computeListScrollMetrics(
            total = 40, viewport = 900,
            // Frame 1: viewport shows one 300px item and two 100px items -> avg = 166.
            visibleCount = 3, visibleSizeSum = 300L + 100L + 100L,
            firstVisibleIndex = 5, firstVisibleScrollOffset = 60,
            firstVisibleItemSize = itemSize,
        )!!
        val b = computeListScrollMetrics(
            total = 40, viewport = 900,
            // Frame 2: another 300px item scrolled in, avg jumps -> avg = 233.
            visibleCount = 3, visibleSizeSum = 300L + 300L + 100L,
            firstVisibleIndex = 5, firstVisibleScrollOffset = 90,
            firstVisibleItemSize = itemSize,
        )!!
        assert(b.offsetFraction > a.offsetFraction) {
            "offset regressed on forward scroll: a=${a.offsetFraction}, b=${b.offsetFraction}"
        }
        // Continuity across the item boundary: scrolled fully through item 5, handoff to item 6.
        val boundaryA = computeListScrollMetrics(
            total = 40, viewport = 900,
            visibleCount = 3, visibleSizeSum = 300L + 100L + 100L,
            firstVisibleIndex = 5, firstVisibleScrollOffset = itemSize,
            firstVisibleItemSize = itemSize,
        )!!
        val boundaryB = computeListScrollMetrics(
            total = 40, viewport = 900,
            visibleCount = 3, visibleSizeSum = 100L + 100L + 100L,
            firstVisibleIndex = 6, firstVisibleScrollOffset = 0,
            firstVisibleItemSize = 100,
        )!!
        assertClose(boundaryA.offsetFraction, boundaryB.offsetFraction, tol = 0.0001f)
    }

    // --- computeGridScrollMetrics ---

    @Test
    fun `grid metrics uses max items in a visible row as columns`() {
        // Simulate a grid with a full-span header (row 0, 1 item) plus a cover row (row 1, 3 items).
        // total = 1 header + 30 covers = 31, viewport = 800.
        // maxCols should be 3, totalRows = ceil(31/3) = 11, avgRowHeight = (40 + 200) / 2 = 120,
        // content = 120 * 11 = 1320, extent = 800 / 1320 ~= 0.606.
        val m = computeGridScrollMetrics(
            total = 31, viewport = 800,
            maxItemsInAnyVisibleRow = 3,
            rowHeightSum = 40L + 200L,
            visibleRowCount = 2,
            firstVisibleRow = 0,
            firstItemOffsetY = 0,
        )!!
        assertClose(800f / 1320f, m.extentFraction)
        assertClose(0f, m.offsetFraction)
    }

    @Test
    fun `grid metrics does NOT collapse columns to 1 when only spanning header is on first row`() {
        // Reproduces the primary bug: header row=0 count=1, covers row=1 count=3.
        // Without the max-across-rows fix, columns would have collapsed to 1 and blown up totalRows.
        val m = computeGridScrollMetrics(
            total = 31, viewport = 800,
            maxItemsInAnyVisibleRow = 3, // this is what the caller now feeds
            rowHeightSum = 40L + 200L,
            visibleRowCount = 2,
            firstVisibleRow = 0,
            firstItemOffsetY = 0,
        )!!
        // Sanity: with correct columns=3, extent > 0.5. With buggy columns=1, extent would be ~0.06.
        assert(m.extentFraction > 0.5f) { "extent collapsed: ${m.extentFraction}" }
    }

    @Test
    fun `grid metrics null when content fits`() {
        // 3 columns, 6 items -> 2 rows of 200px = 400, viewport 800.
        assertNull(
            computeGridScrollMetrics(
                total = 6, viewport = 800,
                maxItemsInAnyVisibleRow = 3, rowHeightSum = 400L, visibleRowCount = 2,
                firstVisibleRow = 0, firstItemOffsetY = 0,
            ),
        )
    }

    @Test
    fun `grid metrics offset includes negative firstItem offset`() {
        // Scrolled such that row 2 is the first visible with offsetY = -30.
        // avgRowHeight = 200, totalRows = ceil(30/3)=10, content = 2000, viewport=800 -> extent=0.4.
        // offsetPx = 2*200 - (-30) = 430, offset = 430/2000 = 0.215.
        val m = computeGridScrollMetrics(
            total = 30, viewport = 800,
            maxItemsInAnyVisibleRow = 3, rowHeightSum = 200L * 4, visibleRowCount = 4,
            firstVisibleRow = 2, firstItemOffsetY = -30,
        )!!
        assertClose(0.4f, m.extentFraction)
        assertClose(0.215f, m.offsetFraction)
    }

    @Test
    fun `grid metrics null on empty visible`() {
        assertNull(
            computeGridScrollMetrics(
                total = 10, viewport = 500,
                maxItemsInAnyVisibleRow = 0, rowHeightSum = 0L, visibleRowCount = 0,
                firstVisibleRow = 0, firstItemOffsetY = 0,
            ),
        )
    }
}
