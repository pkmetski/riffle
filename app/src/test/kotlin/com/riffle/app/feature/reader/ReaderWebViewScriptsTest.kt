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

    // Vertical (scroll) mode cross-reference tap must land the anchor at the VIEWPORT MIDPOINT, not
    // 8 px from the top. Landing at the top puts a caption placed BELOW its figure image at Y=0 —
    // pushing the image (the thing the reader wanted) above the viewport. Continuous mode's
    // ContinuousPositionTracker.anchorLandingScrollY already lands at midpoint (`- viewportHeight/2`);
    // this pins that vertical does the same.
    @Test
    fun `scrollToColumnJs vertical branch lands the anchor at viewport midpoint`() {
        val js = ColumnSnap.scrollToColumnJs("c04-fig-0001")
        // The vertical branch is the block gated on scrollHeight > innerHeight.
        val verticalBranch = js.substringAfter("scrollHeight > window.innerHeight")
            .substringBefore("var iw=window.innerWidth")
        assertTrue(
            "vertical branch must subtract half a viewport, not a fixed 8-px margin, in $verticalBranch",
            verticalBranch.contains("Math.floor(window.innerHeight/2)") &&
                !Regex("""se\.scrollTop\s*=\s*Math\.max\(0,\s*r\.top\s*\+\s*se\.scrollTop\s*-\s*8\s*\)""").containsMatchIn(verticalBranch),
        )
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

    // Regression for the "highlight not saved in continuous mode" bug: Chromium WebView collapses
    // the live DOM selection between the user's action-mode menu tap and our async
    // evaluateJavascript, so a live window.getSelection() read in the menu handler returns empty
    // and the highlight is dropped before it can reach the ViewModel. The fix stashes the full
    // selection payload (text + progression + range rect + before/after context) on every
    // 'selectionchange' event into window.__riffleSelData; the menu handler's read
    // (CONTINUOUS_SELECTION_READ_JS) prefers the stash over the live selection. Both halves must
    // agree on the field names and both must be present, otherwise the fix collapses back to the
    // live-read-only race that shipped the bug.
    @Test
    fun `SELECTION_SPAN_TRACKER_JS stashes the full selection payload on selectionchange`() {
        val js = SELECTION_SPAN_TRACKER_JS
        assertTrue("hooks the selectionchange event", js.contains("addEventListener('selectionchange'"))
        assertTrue("writes __riffleSelData", js.contains("window.__riffleSelData ="))
        // Every field CONTINUOUS_SELECTION_READ_JS reads from the stash must be written here.
        assertTrue("stashes selected text", js.contains("text: text"))
        assertTrue("stashes within-chapter progression as `p`", js.contains("p: Math.max(0, Math.min(1, br2.top / docH))"))
        assertTrue("stashes range rect (l/t/r/b)", js.contains("l: br2.left, t: br2.top, r: br2.right, b: br2.bottom"))
        assertTrue("stashes before/after context (bef/aft)", js.contains("bef: bef, aft: aft"))
    }

    @Test
    fun `CONTINUOUS_SELECTION_READ_JS prefers the pre-stashed selection over the live DOM selection`() {
        val js = CONTINUOUS_SELECTION_READ_JS
        // The stash check must run BEFORE the live window.getSelection() fallback, and must gate
        // on stash.text so an empty stash (e.g. after removeAllRanges) doesn't shadow a live read.
        val stashIdx = js.indexOf("window.__riffleSelData")
        val liveIdx = js.indexOf("window.getSelection")
        assertTrue("stash is read", stashIdx >= 0)
        assertTrue("live getSelection is present as a fallback", liveIdx >= 0)
        assertTrue("stash is preferred over live selection", stashIdx < liveIdx)
        assertTrue("stash is gated on non-empty text", js.contains("if (stash && stash.text)"))
        // The returned JSON shape must match what withSelectionTextAndProgression parses.
        assertTrue("returns text field", js.contains("text: stash.text"))
        assertTrue("returns progression as p", js.contains("p: stash.p"))
        assertTrue("returns range rect (l/t/r/b)", js.contains("l: stash.l, t: stash.t, r: stash.r, b: stash.b"))
        assertTrue("returns before/after context (bef/aft)", js.contains("bef: stash.bef") && js.contains("aft: stash.aft"))
    }

    // Regression pin: touchstart snapshot in SELECTION_SPAN_TRACKER_JS is what lets the paged
    // InputListener.onTap swallow a tap-to-dismiss instead of toggling immersive. If this listener
    // stops firing (removed, moved out of the capture phase, or renamed away from onActiveAtDown),
    // the tap-to-dismiss bug reappears — the assertions here flip red on any of those regressions.
    @Test
    fun `SELECTION_SPAN_TRACKER_JS snapshots selection state at touchstart via RiffleSelBridge`() {
        val js = SELECTION_SPAN_TRACKER_JS
        val touchStartIdx = js.indexOf("addEventListener('touchstart'")
        val selectionChangeIdx = js.indexOf("addEventListener('selectionchange'")
        assertTrue("touchstart listener is installed", touchStartIdx >= 0)
        assertTrue("selectionchange listener is still installed", selectionChangeIdx >= 0)
        assertTrue(
            "touchstart is registered on the capture phase so it beats descendants",
            js.substring(touchStartIdx).contains("}, true)"),
        )
        assertTrue(
            "snapshots the selection state at touchstart",
            js.contains("!!(s && s.rangeCount > 0 && !s.isCollapsed)"),
        )
        assertTrue(
            "reports the snapshot via the RiffleSelBridge.onActiveAtDown method",
            js.contains("RiffleSelBridge.onActiveAtDown(active)"),
        )
    }

    // Regression pin: continuous mode's TAP_LISTENER_JS must skip the immersive toggle when a
    // selection was live at touchstart. Symmetric to the paged mode's onActiveAtDown gate.
    @Test
    fun `TAP_LISTENER_JS suppresses onTap when a selection was live at touchstart`() {
        val js = ContinuousScriptInjector.TAP_LISTENER_JS
        val touchStartIdx = js.indexOf("addEventListener('touchstart'")
        val clickIdx = js.indexOf("addEventListener('click'")
        val onTapIdx = js.indexOf("RiffleChapter.onTap()")
        assertTrue("touchstart listener is installed", touchStartIdx >= 0)
        assertTrue("click listener is still installed", clickIdx >= 0)
        assertTrue("touchstart is registered before click", touchStartIdx < clickIdx)
        assertTrue(
            "snapshots the selection state at touchstart",
            js.contains("!!(s && s.rangeCount > 0 && !s.isCollapsed)"),
        )
        val snapshotIdx = js.indexOf("document.__riffleHadSelAtDown =")
        assertTrue("touchstart writes to the doc-level flag", snapshotIdx in 0 until onTapIdx)
        // Consume-once: the click handler snapshots hadSel and clears the flag at the top,
        // before the interactive-element early-return, so a synthetic click without a
        // preceding touchstart can't leave a stale `true` behind to swallow the next tap.
        val consumeIdx = js.indexOf("var hadSel = document.__riffleHadSelAtDown;")
        val clearIdx = js.indexOf("document.__riffleHadSelAtDown = false;")
        assertTrue("click snapshots the flag first", consumeIdx in 0 until onTapIdx)
        assertTrue("click clears the flag immediately after snapshot", clearIdx in consumeIdx..onTapIdx)
        val gateIdx = js.indexOf("if (hadSel) return;")
        assertTrue("click skips onTap when a selection was live at touchstart", gateIdx in 0 until onTapIdx)
    }
}
