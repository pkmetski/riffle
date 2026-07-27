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
        assertTrue("moves the vertical scroll via se.scrollTop tween", js.contains("se.scrollTop=startV"))
        assertTrue("leaves an already-visible target alone", js.contains("r.bottom<=window.innerHeight"))
    }

    // Vertical (scroll) mode's cross-reference / return-to-position tap must animate the scroll
    // with the SAME feel as Continuous mode's NestedScrollView.smoothScrollTo — a fixed ~250 ms
    // ease-out, not Chromium's distance-proportional `behavior:'smooth'`. The distance-based curve
    // reads as sluggish on longer jumps and doesn't match the continuous default; both modes need
    // to feel identical on figure/back taps (the "make vertical as snappy as continuous" ask).
    @Test
    fun `scrollToColumnJs vertical branch uses fixed-duration JS animation not behavior smooth`() {
        val js = ColumnSnap.scrollToColumnJs("c04-fig-0001")
        val verticalBranch = js.substringAfter("scrollHeight > window.innerHeight")
            .substringBefore("var iw=window.innerWidth")
        assertTrue(
            "vertical branch must drive the animation itself via requestAnimationFrame — in $verticalBranch",
            verticalBranch.contains("requestAnimationFrame") && verticalBranch.contains("_dur=250"),
        )
        assertTrue(
            "vertical branch must NOT delegate to Chromium's distance-proportional behavior:'smooth' — its curve doesn't match continuous mode's",
            !verticalBranch.contains("behavior: 'smooth'") && !verticalBranch.contains("behavior:'smooth'"),
        )
    }

    // Vertical mode return-to-position card and cross-resource internal links (which route through
    // snapAfterGoTo → snapToTargetColumnJs) must animate, not hard-jump. Readium's `frag.go(locator)`
    // already teleported the page to the target; without a vertical branch here, the entire snap
    // JS is a no-op in scroll mode (it only manipulates scrollLeft) and the user sees the abrupt
    // land the "back button feels abrupt in vertical" complaint pinned. The vertical branch has to
    // (a) pre-scroll half a viewport short of the target under the nav cover, then (b) animate
    // the tail with the same fixed-duration curve `scrollToColumnJs` uses, so both call sites feel
    // identical.
    @Test
    fun `snapToTargetColumnJs animates in vertical mode from stashed origin on same-doc jumps`() {
        val js = ColumnSnap.snapToTargetColumnJs("figure-4-1")
        val verticalBranch = js.substringAfter("if(se && se.scrollHeight > window.innerHeight + 4)")
            .substringBefore("var gen=(window.__riffleSnapGen")
        assertTrue(
            "vertical branch must be present in $js",
            verticalBranch.contains("targetV=") && verticalBranch.contains("startV="),
        )
        // Same-doc branch: reads the pre-go scrollTop from window.__riffleOriginY. Without this,
        // a return-to-position tap flashes as "goes back a bit and then returns" — the visible
        // pre-land the user reported, because same-doc navigation has no nav cover to hide it.
        assertTrue(
            "same-doc branch must start the animation from the stashed pre-go scrollTop",
            verticalBranch.contains("window.__riffleOriginY") &&
                verticalBranch.contains("startV=window.__riffleOriginY"),
        )
        // Fragment-stripped href compare: Readium's `go(locator)` on a same-doc jump often
        // appends the target `#anchor` to `location.href` (browser-standard hash update). If the
        // compare used bare `location.href` on both sides, EVERY same-doc jump would fail the
        // same-doc check and default to the pre-land branch — the exact pre-land flash the
        // stash+animate mechanism was written to eliminate.
        assertTrue(
            "same-doc href compare must strip the fragment on both sides",
            verticalBranch.contains("location.href.split('#')[0]"),
        )
        // Cross-doc fallback: origin stash absent (new document), pre-land half a viewport short
        // under the nav cover so the tail rides visibly on cover-reveal.
        assertTrue(
            "cross-doc branch must fall back to pre-landing half a viewport short",
            verticalBranch.contains("Math.floor(window.innerHeight/2)") &&
                verticalBranch.contains("se.scrollTop=startV"),
        )
        // Stash must be consumed so a later background sync doesn't reuse a stale origin from an
        // unrelated navigation.
        assertTrue(
            "vertical branch must consume the stash after reading it",
            verticalBranch.contains("window.__riffleOriginY=null") &&
                verticalBranch.contains("window.__riffleOriginHref=null"),
        )
        assertTrue(
            "vertical branch uses the shared fixed-duration ease-out animation",
            verticalBranch.contains("requestAnimationFrame") && verticalBranch.contains("_dur=250"),
        )
        assertTrue(
            "vertical branch skips the paginated rAF column-snap loop — no-op work in scroll mode",
            verticalBranch.contains("return;"),
        )
    }

    // The stash JS is the other half of the same-doc-origin contract: it must snapshot BOTH the
    // scrollTop and the current href on `window` before Readium's `go(locator)` teleports the
    // page. If the href isn't stashed, a cross-doc jump would read a stale __riffleOriginY on the
    // new document (undefined, harmless) — but if a future refactor keeps the stash across
    // resources, the href check is what still keeps cross-doc correct. Contract-pin both fields.
    @Test
    fun `STASH_VERTICAL_ORIGIN_JS captures scrollTop and fragment-stripped href on window`() {
        val js = ColumnSnap.STASH_VERTICAL_ORIGIN_JS
        assertTrue("stashes scrollTop", js.contains("window.__riffleOriginY=se.scrollTop"))
        // Strips the fragment so a hash-only change on the SAME document (Readium's `go(locator)`
        // appending `#anchor` to `location.href`) still compares equal in
        // `snapToTargetColumnJs`'s same-doc branch. Without this strip, every same-doc back-nav
        // hits the pre-land branch and flashes as "goes back a bit and then returns."
        assertTrue(
            "stashes fragment-stripped location.href",
            js.contains("window.__riffleOriginHref=location.href.split('#')[0]"),
        )
    }

    // The animated=false variant is what SentencePlaybackController's cadence follow needs: every
    // narrated sentence tick calls snapCadenceSpan → scrollToColumnJs, and a 250 ms tween per
    // tick would (a) drift because the supersede counter cancels in-flight tweens before they
    // land and (b) desync from the audio. Verify the two variants emit different vertical
    // behaviours so a future refactor can't silently make cadence follow animated.
    @Test
    fun `scrollToColumnJs animated=false uses instant scrollTop write in vertical mode`() {
        val instant = ColumnSnap.scrollToColumnJs("cd-1", animated = false)
        val animated = ColumnSnap.scrollToColumnJs("cd-1", animated = true)
        val instantVertical = instant.substringAfter("scrollHeight > window.innerHeight")
            .substringBefore("var iw=window.innerWidth")
        val animatedVertical = animated.substringAfter("scrollHeight > window.innerHeight")
            .substringBefore("var iw=window.innerWidth")
        assertTrue(
            "animated=false must write scrollTop synchronously (no rAF tween)",
            instantVertical.contains("se.scrollTop=targetV") &&
                !instantVertical.contains("requestAnimationFrame"),
        )
        assertTrue(
            "animated=true must use the shared rAF tween",
            animatedVertical.contains("requestAnimationFrame") && animatedVertical.contains("_dur=250"),
        )
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
    // landAtStartWhenNoTarget=false without progression: with no DOM target it ROUNDS the current scroll
    // to the column grid instead of snapping to column 0. This is the contract background-sync uses when
    // go() already positioned within the same chapter and only a grid-alignment round is needed.
    @Test
    fun `snapToTargetColumnJs rounds the current scroll to the grid when no target and not landing at start`() {
        val js = ColumnSnap.snapToTargetColumnJs(null, landAtStartWhenNoTarget = false)
        assertTrue("id is null", js.contains("var id=null"))
        assertTrue("rounds the current scroll to the grid", js.contains("se.scrollLeft=Math.round(se.scrollLeft/iw)*iw"))
        assertTrue("does NOT yank to column 0", !js.contains("se.scrollLeft=0;"))
    }

    // landAtStartWhenNoTarget=false WITH progression: used for annotation navigation where there is no named
    // DOM element in the CFI. After a cross-chapter jump, typography-injection reflow resets scrollLeft to 0
    // before the rAF loop exits, so rounding scrollLeft snaps to column 0 (first page). By recomputing from
    // progression * scrollWidth each tick the snap lands on the right column even after reflow, because
    // scrollWidth re-settles to the post-reflow value and progression * new_scrollWidth is reflow-stable.
    @Test
    fun `snapToTargetColumnJs uses progression times scrollWidth when no target, not landing at start, and progression provided`() {
        val js = ColumnSnap.snapToTargetColumnJs(null, landAtStartWhenNoTarget = false, locatorProgression = 0.42)
        assertTrue("id is null", js.contains("var id=null"))
        // The noTargetSnap branch (else{...} after if(id){...}) must use progression * scrollWidth.
        assertTrue("uses progression * scrollWidth", js.contains("else{se.scrollLeft=Math.round(0.42*se.scrollWidth/iw)*iw;}"))
        assertTrue("does NOT yank to column 0", !js.contains("se.scrollLeft=0;"))
    }

    // progression is ignored when landAtStartWhenNoTarget=true — chapter-level jumps always floor to 0.
    @Test
    fun `snapToTargetColumnJs ignores progression and floors to column 0 when landAtStartWhenNoTarget is true`() {
        val js = ColumnSnap.snapToTargetColumnJs(null, landAtStartWhenNoTarget = true, locatorProgression = 0.42)
        assertTrue("floors to 0", js.contains("se.scrollLeft=0;"))
        assertTrue("does NOT use progression", !js.contains("0.42"))
    }

    // Annotation focus must set _skipV=true so the vertical smooth-tail block (if scrollHeight >
    // innerHeight) is bypassed. The snap JS runs before CSS multicol is applied; at that moment
    // scrollHeight > innerHeight, so without _skipV the vertical branch fires and returns early —
    // the rAF paginated loop never runs, and Readium resets scrollLeft to 0. With _skipV=true the
    // rAF loop runs, waits for scrollWidth to stabilize after multicol, then snaps to the right column.
    @Test
    fun `snapToTargetColumnJs sets skipV true for annotation focus to bypass vertical early-return`() {
        val js = ColumnSnap.snapToTargetColumnJs(null, landAtStartWhenNoTarget = false, locatorProgression = 0.42)
        assertTrue("_skipV is true", js.contains("var _skipV=true;"))
        assertTrue("vertical check uses !_skipV guard", js.contains("if(!_skipV && se && se.scrollHeight > window.innerHeight + 4)"))
    }

    // Non-annotation navigation (TOC, resume, search) must NOT set _skipV — those jumps use the
    // vertical smooth-tail for the same-doc animation and the snap-to-0 for chapter starts.
    @Test
    fun `snapToTargetColumnJs sets skipV false for TOC and resume navigation`() {
        val js = ColumnSnap.snapToTargetColumnJs("ch01", landAtStartWhenNoTarget = true, locatorProgression = null)
        assertTrue("_skipV is false", js.contains("var _skipV=false;"))
        assertTrue("vertical check uses !_skipV guard", js.contains("if(!_skipV && se && se.scrollHeight > window.innerHeight + 4)"))
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

    // Regression pin: the figure walker inside SELECTION_SPAN_TRACKER_JS must scan the range's scope
    // with querySelectorAll + range.intersectsNode, NOT document.createTreeWalker + an acceptNode
    // callback. The TreeWalker form never yielded any enclosed <img> in Chromium's paginated Readium
    // WebView — highlights spanning an equation image landed in the DB with empty embeddedFigures
    // and the FigureBorderDecoration then produced no border rule for the figure. Flipping this
    // back to TreeWalker reintroduces the bug.
    @Test
    fun `SELECTION_SPAN_TRACKER_JS walks enclosed figures with querySelectorAll + intersectsNode`() {
        val js = SELECTION_SPAN_TRACKER_JS
        assertTrue(
            "queries figure candidates in the range's scope",
            js.contains("querySelectorAll('img, svg, picture, figure')"),
        )
        assertTrue(
            "filters candidates by range.intersectsNode",
            js.contains("rng2.intersectsNode(fnode)"),
        )
        assertTrue(
            "does NOT use TreeWalker (the failed approach)",
            !js.contains("createTreeWalker"),
        )
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

    // Regression for #428: chapter injections can fire before document.body is populated
    // (observed on a running session after a sandboxed WebView process restart). Every
    // `document.createTreeWalker(document.body, ...)` call site must early-return so the
    // injection doesn't throw "parameter 1 is not of type 'Node'" and wedge the reader.
    @Test
    fun `resolveSelectionSentenceJs guards createTreeWalker against a null document body (issue 428)`() {
        val js = resolveSelectionSentenceJs(listOf("s1" to "Hello world hello world"))
        val guardIdx = js.indexOf("if(!document.body) return \"\";")
        val walkerIdx = js.indexOf("document.createTreeWalker(document.body")
        assertTrue("guard present", guardIdx >= 0)
        assertTrue("walker present", walkerIdx >= 0)
        assertTrue("guard sits BEFORE the walker call", guardIdx < walkerIdx)
    }

    @Test
    fun `firstVisibleSentenceJs guards createTreeWalker against a null document body (issue 428)`() {
        val js = firstVisibleSentenceJs(listOf("Hello world hello world"))
        val guardIdx = js.indexOf("if(!document.body) return \"\";")
        val walkerIdx = js.indexOf("document.createTreeWalker(document.body")
        assertTrue("guard present", guardIdx >= 0)
        assertTrue("walker present", walkerIdx >= 0)
        assertTrue("guard sits BEFORE the walker call", guardIdx < walkerIdx)
    }

    @Test
    fun `autoFollowSnapJs guards createTreeWalker against a null document body (issue 428)`() {
        val js = ColumnSnap.autoFollowSnapJs("Hello world.")
        val guardIdx = js.indexOf("if(!document.body) return \"off\";")
        val walkerIdx = js.indexOf("document.createTreeWalker(document.body")
        assertTrue("guard present", guardIdx >= 0)
        assertTrue("walker present", walkerIdx >= 0)
        assertTrue("guard sits BEFORE the walker call", guardIdx < walkerIdx)
    }

    // Regression for the "selection menu jumps to the top of the screen for big selections" bug:
    // in paginated mode, a selection extended across a CSS column-break makes
    // Range.getBoundingClientRect() return a garbage union rect (negative top / cross-column
    // right). Feeding that to onGetContentRect anchors the FloatingToolbar above the top of the
    // screen. SELECTION_SPAN_TRACKER_JS must prefer the first per-line rect from getClientRects()
    // that lies inside the visual viewport when reporting the anchor to RiffleSelBridge.onRect,
    // and only fall back to the bounding rect when nothing is in-view.
    @Test
    fun `SELECTION_SPAN_TRACKER_JS anchors onRect to the first in-viewport client rect not the union bounding rect`() {
        val js = SELECTION_SPAN_TRACKER_JS
        val onRectIdx = js.indexOf("RiffleSelBridge.onRect(")
        assertTrue("bridge call present", onRectIdx >= 0)
        // The picker must scan getClientRects() and select an in-viewport rect (top>=0 &&
        // top<vh && left>=0 && left<vw). Assertions target that logic; deleting or loosening it
        // brings the bug back.
        assertTrue("scans per-line client rects", js.contains("rng.getClientRects()"))
        assertTrue("filters by top in [0, vh)", js.contains("r.top >= 0 && r.top < vh"))
        assertTrue("filters by left in [0, vw)", js.contains("r.left >= 0 && r.left < vw"))
        assertTrue("bounding rect kept only as fallback", js.contains("var use = anchor || br"))
        // The call must pass the chosen `use` rect — not `br` directly — so a garbage bounding
        // rect can never reach the bridge when an in-viewport line rect exists.
        val onRectCall = js.substring(onRectIdx, js.indexOf(")", onRectIdx) + 1)
        assertTrue(
            "onRect uses the picked anchor rect, not the bounding rect directly, in: $onRectCall",
            onRectCall.contains("use.left") && onRectCall.contains("use.top") &&
                onRectCall.contains("use.right") && onRectCall.contains("use.bottom"),
        )
    }

    @Test
    fun `measureNarratedColumnsJs guards createTreeWalker against a null document body (issue 428)`() {
        // measureNarratedColumnsJs inlines narratedColumnsPreludeJs (private), so this exercises the
        // shared prelude's guard from the public surface.
        val js = ColumnSnap.measureNarratedColumnsJs("Hello world.")
        val guardIdx = js.indexOf("if(!document.body) return \"off\";")
        val walkerIdx = js.indexOf("document.createTreeWalker(document.body")
        assertTrue("guard present", guardIdx >= 0)
        assertTrue("walker present", walkerIdx >= 0)
        assertTrue("guard sits BEFORE the walker call", guardIdx < walkerIdx)
    }

    /**
     * Regression test for the erratic text-selection handle drag in paginated mode: the scroll
     * listener in SETTLE_SNAP_INSTALL_JS used a fixed 120ms debounce, which produced a visible
     * page snap while the user was still dragging a selection handle across a column boundary.
     *
     * Fix: when window.getSelection() is non-collapsed (active text selection), use 32ms (~2
     * frames) so the snap fires fast enough to be imperceptible. The 120ms delay is kept for all
     * other scroll events (page turns, column nav) where a visible settle is expected behaviour.
     *
     * This assertion flips red if someone removes the selection check or raises the delay back to
     * 120ms unconditionally.
     */
    @Test
    fun `SETTLE_SNAP_INSTALL_JS uses short debounce during active text selection to prevent visible handle-drag jump`() {
        val js = ColumnSnap.SETTLE_SNAP_INSTALL_JS
        // The selection-aware branch must be present.
        assertTrue(
            "SETTLE_SNAP_INSTALL_JS must check window.getSelection().isCollapsed",
            js.contains("getSelection") && js.contains("isCollapsed"),
        )
        // 32ms short-path must appear in the JS.
        assertTrue(
            "SETTLE_SNAP_INSTALL_JS must set delay=32 during non-collapsed selection",
            js.contains("delay=32"),
        )
        // The default 120ms path must still exist for non-selection scrolls.
        assertTrue(
            "SETTLE_SNAP_INSTALL_JS must keep delay=120 for normal scroll events",
            js.contains("delay = 120") || js.contains("delay=120"),
        )
        // The short delay must appear AFTER the default so it only overrides during selection.
        val defaultDelayIdx = js.indexOf("delay = 120").let { if (it < 0) js.indexOf("delay=120") else it }
        val shortDelayIdx = js.indexOf("delay=32")
        assertTrue(
            "delay=32 override must appear AFTER the default delay=120 assignment",
            shortDelayIdx > defaultDelayIdx,
        )
    }

    // Regression for the "elided view drops publisher formatting when selection is entirely inside a
    // formatting ancestor" bug. walkInline walks Range.cloneContents() descendants, but when the
    // user selects text that sits entirely inside <em>italic text</em>, cloneContents() returns a
    // bare text node — the <em> is an ANCESTOR of the range, not a descendant. The fix walks up
    // from commonAncestorContainer to <body> collecting any INLINE_ALLOW tag ancestors and wraps
    // the plain text in them. The following pins ensure this path is present and correct.
    @Test
    fun `SELECTION_SPAN_TRACKER_JS adds ancestor-checking block after walkInline for formatting ancestors`() {
        val js = SELECTION_SPAN_TRACKER_JS
        val walkInlineIdx = js.indexOf("snippetHtml = walkInline(frag)")
        assertTrue("walkInline call present", walkInlineIdx >= 0)
        // Ancestor check is gated on snippetHtml equalling the raw escaped text — meaning
        // walkInline found no formatting descendants.
        val condIdx = js.indexOf("if (snippetHtml === escInline(text))", walkInlineIdx)
        assertTrue("ancestor check gate is present after walkInline", condIdx > walkInlineIdx)
        // The walk goes up via commonAncestorContainer (the canonical range ancestor API).
        assertTrue(
            "ancestor walk starts from commonAncestorContainer",
            js.indexOf("rng.commonAncestorContainer", condIdx) > condIdx,
        )
        // Ascends to (but not including) the body element.
        assertTrue(
            "ancestor walk stops at body",
            js.indexOf("el.tagName.toLowerCase() !== 'body'", condIdx) > condIdx,
        )
        // Uses the same INLINE_ALLOW allowlist as walkInline so descendants and ancestors are
        // treated symmetrically — no separate ANCESTOR_ALLOW filter.
        val inlineAllowInAncestorBlock = js.indexOf("if (INLINE_ALLOW[wt])", condIdx)
        assertTrue(
            "ancestor walk filters by INLINE_ALLOW (same allowlist as descendant walk)",
            inlineAllowInAncestorBlock > condIdx,
        )
    }

    @Test
    fun `SELECTION_SPAN_TRACKER_JS wraps plain text in ancestors innermost-first`() {
        val js = SELECTION_SPAN_TRACKER_JS
        val condIdx = js.indexOf("if (snippetHtml === escInline(text))")
        assertTrue("ancestor check gate present", condIdx >= 0)
        // wrapTags[0] is the innermost ancestor; the loop wraps from innermost to outermost so the
        // output nesting matches the original DOM order.
        assertTrue(
            "wrapping starts with escInline(text) as the innermost content",
            js.indexOf("var wrapped = escInline(text)", condIdx) > condIdx,
        )
        assertTrue(
            "loop builds up from wrapTags by index (innermost first)",
            js.indexOf("'<' + wrapTags[wi] + '>' + wrapped + '</' + wrapTags[wi] + '>'", condIdx) > condIdx,
        )
    }

    @Test
    fun `SELECTION_SPAN_TRACKER_JS suppresses plain-text-only snippetHtml before bridging`() {
        val js = SELECTION_SPAN_TRACKER_JS
        // After the ancestor walk, if no formatting ancestor was found the snippet would still
        // equal escInline(text). The script must clear it to '' so the bridge sends nothing and
        // textSnippetHtml stays null (triggering the plain render path, not a no-op formatted one).
        val condIdx = js.indexOf("if (snippetHtml === escInline(text))")
        val clearIdx = js.indexOf("if (snippetHtml === escInline(text)) snippetHtml = '';")
        assertTrue("ancestor check gate is present", condIdx >= 0)
        assertTrue("clearing sentinel is present", clearIdx >= 0)
        assertTrue("clearing comes AFTER the ancestor check", clearIdx > condIdx)
    }

    @Test
    fun `SELECTION_SPAN_TRACKER_JS stashes snippetHtml in __riffleSelData and bridges it via onSnippetHtml`() {
        val js = SELECTION_SPAN_TRACKER_JS
        // The html field in __riffleSelData feeds the continuous-mode read path.
        assertTrue("html field written to __riffleSelData stash", js.contains("html: snippetHtml"))
        // onSnippetHtml bridges the value to paginated mode (Readium never reads __riffleSelData).
        assertTrue(
            "onSnippetHtml bridge call is present",
            js.contains("RiffleSelBridge.onSnippetHtml(snippetHtml)"),
        )
        // Bridge is guarded by a presence check (bridge not registered in continuous ChapterWebView).
        val bridgeIdx = js.indexOf("RiffleSelBridge.onSnippetHtml(snippetHtml)")
        assertTrue(
            "bridge call is guarded by RiffleSelBridge.onSnippetHtml existence check",
            js.substring(maxOf(0, bridgeIdx - 200), bridgeIdx).contains("RiffleSelBridge.onSnippetHtml"),
        )
    }
}
