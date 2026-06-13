package com.riffle.app.feature.reader

import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderWebViewScriptsTest {

    // scrollToColumnJs floors scrollLeft to the column the element starts in, so a tapped figure
    // cross-reference lands flush on the grid rather than a gutter inside its column.
    @Test
    fun `scrollToColumnJs floors to the element's column and quotes the id`() {
        val js = ColumnSnap.scrollToColumnJs("c04-fig-0001")
        assertTrue("looks up the target by id", js.contains("getElementById(\"c04-fig-0001\")"))
        assertTrue("measures the element position", js.contains("getBoundingClientRect"))
        assertTrue("reads the live column pitch", js.contains("window.innerWidth"))
        assertTrue("FLOORS to the column boundary", js.contains("Math.floor(abs/iw)*iw"))
    }

    // Dotted ids (O'Reilly-style "ftn.ch01fn01") must survive verbatim — JSONObject.quote keeps them
    // a plain string literal so getElementById (not a CSS selector) matches them.
    @Test
    fun `scrollToColumnJs preserves dotted ids verbatim`() {
        assertTrue(ColumnSnap.scrollToColumnJs("ftn.ch01fn01").contains("getElementById(\"ftn.ch01fn01\")"))
    }

    // scrollToColumnJs reports whether the snap changed columns, so the caller can offer a "return"
    // affordance only when the cross-reference was actually off the visible page ('moved') and suppress
    // it for an on-page target ('same') or a missing id ('absent').
    @Test
    fun `scrollToColumnJs reports moved versus same versus absent`() {
        val js = ColumnSnap.scrollToColumnJs("c04-fig-0001")
        assertTrue("absent when the id isn't found", js.contains("return 'absent'"))
        assertTrue("captures the pre-snap scroll", js.contains("var before=se.scrollLeft"))
        assertTrue("reports moved/same by the column delta", js.contains("'moved':'same'"))
    }

    // In scroll (vertical) mode there is no column grid, so the cross-reference snap must scroll
    // VERTICALLY to the element instead of flooring scrollLeft (a no-op there) — otherwise a figure
    // link does nothing. An element already fully on screen reports 'same'.
    @Test
    fun `scrollToColumnJs scrolls vertically in scroll mode`() {
        val js = ColumnSnap.scrollToColumnJs("c04-fig-0001")
        assertTrue("detects scroll mode by content height", js.contains("scrollHeight > window.innerHeight"))
        assertTrue("moves the vertical scroll", js.contains("se.scrollTop="))
        assertTrue("leaves an already-visible target alone", js.contains("r.bottom<=window.innerHeight"))
    }

    // snapToTargetColumnJs anchors a go()-based TOC/search jump to the column the TARGET occupies,
    // re-applying it across the async typography reflow until scrollWidth settles — the fix for the
    // "TOC lands a page before/after" bug where a one-shot snap locked onto the pre-reflow column.
    @Test
    fun `snapToTargetColumnJs floors to the target's column and waits for reflow to settle`() {
        val js = ColumnSnap.snapToTargetColumnJs("creating_a_summary")
        assertTrue("captures the target id", js.contains("var id=\"creating_a_summary\""))
        assertTrue("looks up the target by id", js.contains("getElementById(id)"))
        assertTrue("FLOORS to the target's column", js.contains("Math.floor(("))
        assertTrue("reads the live column pitch", js.contains("window.innerWidth"))
        assertTrue("re-applies across frames", js.contains("requestAnimationFrame"))
        assertTrue("waits for scrollWidth to hold steady", js.contains("scrollWidth"))
        assertTrue("bounded by a safety cap", js.contains("frames++>72"))
        assertTrue("a newer jump supersedes it", js.contains("__riffleSnapGen"))
    }

    // A bare-href jump (no fragment) targets the resource start, so it floors to column 0 rather than
    // hunting for an element id.
    @Test
    fun `snapToTargetColumnJs targets the resource start when there is no fragment`() {
        val js = ColumnSnap.snapToTargetColumnJs(null)
        assertTrue("id is null", js.contains("var id=null"))
        assertTrue("snaps to column 0", js.contains("se.scrollLeft=0"))
    }

    // Dotted ids (O'Reilly-style "ftn.ch01fn01") must survive verbatim so getElementById matches them.
    @Test
    fun `snapToTargetColumnJs preserves dotted ids verbatim`() {
        assertTrue(ColumnSnap.snapToTargetColumnJs("ftn.ch01fn01").contains("var id=\"ftn.ch01fn01\""))
    }

    // A no-fragment jump that must PRESERVE where go() landed (search hits, resume/peer sync) passes
    // landAtStartWhenNoTarget=false: with no DOM target it ROUNDS the current scroll to the column grid
    // instead of snapping to column 0. This is the contract search navigation relies on so a search hit
    // (located by its occurrence-specific progression via go()) lands flush on its own page rather than
    // being yanked to the chapter top.
    @Test
    fun `snapToTargetColumnJs rounds the current scroll to the grid when no target and not landing at start`() {
        val js = ColumnSnap.snapToTargetColumnJs(null, landAtStartWhenNoTarget = false)
        assertTrue("id is null", js.contains("var id=null"))
        assertTrue("rounds the current scroll to the grid", js.contains("se.scrollLeft=Math.round(se.scrollLeft/iw)*iw"))
        assertTrue("does NOT yank to column 0", !js.contains("se.scrollLeft=0;"))
    }
}
