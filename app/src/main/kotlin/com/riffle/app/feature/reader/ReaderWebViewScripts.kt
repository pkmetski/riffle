package com.riffle.app.feature.reader

import org.json.JSONArray
import org.json.JSONObject

/**
 * JavaScript snippets EpubReaderScreen injects into every reflowable page (see the
 * paginationListener's onPageLoaded). Kept together — and out of the screen file — alongside the
 * other injected-script homes ([TypographyOverride], [FootnoteAnchorBridge]). All are idempotent so
 * repeated firing during reflow is harmless.
 */

// Readium 3.0.0's reflowable tap handler (`ut` in readium-reflowable.js) hit-tests decorations by
// calling `element.getBoundingClientRect().toJSON()`. On Android's pre-Chromium-61 WebView (API ≤ 27,
// e.g. Android 7.1.1) getBoundingClientRect() returns a ClientRect with no toJSON(), so that call
// throws *before* Readium forwards the gesture to Android.onTap — silently swallowing every tap
// whenever an activable decoration is present. In practice that's the readaloud sentence highlight:
// while (or after) readaloud runs, the user can no longer tap to enter/exit immersive mode. Adding
// the missing toJSON() restores tap delivery. Guarded so it's a no-op where the engine already has it.
internal val RECT_TO_JSON_POLYFILL_JS = """
    (function () {
      try {
        var protos = [];
        if (window.DOMRect && DOMRect.prototype) protos.push(DOMRect.prototype);
        if (window.ClientRect && ClientRect.prototype) protos.push(ClientRect.prototype);
        try { protos.push(Object.getPrototypeOf(document.documentElement.getBoundingClientRect())); } catch (e) {}
        protos.forEach(function (p) {
          if (p && typeof p.toJSON !== 'function') {
            p.toJSON = function () {
              return {
                x: this.x, y: this.y, width: this.width, height: this.height,
                top: this.top, right: this.right, bottom: this.bottom, left: this.left
              };
            };
          }
        });
      } catch (e) {}
    })();
""".trimIndent()

// Tracks the narrated-sentence span under the user's text selection so "Play from here" can resolve
// the right SMIL clip. Storyteller wraps each sentence in <span id="cNNN-sM">; on every non-collapsed
// selectionchange we walk up from the selection start to the nearest element with an id and stash it
// in window.__riffleSelSpan (or "" when nothing in the ancestry has one). The stashed value survives
// the action-mode teardown, so the menu handler can read it after the DOM selection is gone. We always
// overwrite on a fresh selection — never leave a previous selection's id behind — so a selection with
// no sentence-span ancestor cleanly falls back to the chapter position rather than reusing a stale id.
// Idempotent via the installed flag.
//
// NOTE: this DOM-id path only works on pages whose sentence spans survive into the served HTML
// (pure-Storyteller rendering). When the reader renders the ABS EPUB, Readium STRIPS those id-only
// spans (see [autoFollowSnapJs] / [ReadaloudTextQuotes]), so the walk never finds a sentence id and
// "Play from here" would fall back to the chapter's first clip — i.e. restart the chapter. The menu
// handler therefore prefers [resolveSelectionSentenceJs] (position based, span-stripping proof) and
// only uses this stash as a fallback.
//
// The tracker also stashes the selection START's viewport rect in window.__riffleSelRect, which
// [resolveSelectionSentenceJs] reads to resolve the narrated sentence by POSITION. We capture it here,
// on selectionchange, because the live DOM selection is gone by the time the action-mode menu handler
// runs. getClientRects()[0] is the rect of the selection's first glyph (its start).
internal val SELECTION_SPAN_TRACKER_JS = """
    (function () {
      if (window.__riffleSelTrackerInstalled) return;
      window.__riffleSelTrackerInstalled = true;
      document.addEventListener('selectionchange', function () {
        var s = window.getSelection();
        if (!s || s.rangeCount === 0 || s.isCollapsed) return;
        var rng = s.getRangeAt(0);
        var n = rng.startContainer;
        if (n && n.nodeType === 3) n = n.parentElement;
        var id = '';
        while (n && n !== document.body) {
          if (n.id) { id = n.id; break; }
          n = n.parentElement;
        }
        window.__riffleSelSpan = id;
        try {
          var rects = rng.getClientRects();
          var rr = (rects && rects.length) ? rects[0] : rng.getBoundingClientRect();
          window.__riffleSelRect = { top: rr.top, left: rr.left, bottom: rr.bottom };
        } catch (e) { window.__riffleSelRect = null; }
      });
    })();
""".trimIndent()

/**
 * Resolves a text selection to the narrated-sentence span id the user tapped into — by GEOMETRY, not by
 * matching sentence text. This is what "Play from here" uses to start narration at the selected sentence.
 *
 * Why not by a DOM id: Readium strips Storyteller's `<span id="cNNN-sM">` wrappers from the ABS EPUB it
 * serves (see [autoFollowSnapJs] / [ReadaloudTextQuotes]), so there is no sentence id under the selection
 * to read — that path falls back to the chapter's first clip (the "restarts the chapter" bug).
 *
 * Why not by matching whole sentence text: the served (ABS) prose and the bundle's sentence text diverge
 * just enough — smart quotes, dashes, inline markup, edition differences — that whole-sentence matching
 * misses unpredictably, so the resolver either mis-seats to an earlier sentence or finds nothing at all.
 *
 * Geometry is robust. We reuse the exact primitive the production highlight-follow relies on
 * ([autoFollowSnapJs] / [firstVisibleSentenceJs]): match each sentence's SHORT start prefix WITHIN A
 * SINGLE text node. The prefix is 12 chars — the same length [autoFollowSnapJs] uses — short enough to
 * sit before any inline markup the sentence's opening words run into (e.g. "Once we got " precedes the
 * italic "<em>Hermes</em>"); a longer prefix would straddle that node boundary and never match. We only
 * need to *locate* the start, not match the whole sentence, so the text-fidelity gaps don't matter. For each
 * located start we take its on-screen rect and keep the one that is latest in reading order
 * (top-to-bottom; left within a line) AT OR BEFORE the selection's start rect — i.e. the sentence the tap
 * landed in. Only starts on the current page/column are considered, so off-page columns can't interfere.
 * The selection rect is read from window.__riffleSelRect (stashed by [SELECTION_SPAN_TRACKER_JS] while the
 * selection was live). Returns "" when nothing resolves; the caller then falls back to the span-id stash,
 * then the chapter position.
 *
 * [sentences] is the book's narrated sentences as (span id → text), in reading order; sentences from other
 * chapters aren't in this document's DOM, so their prefixes simply aren't found.
 */
internal fun resolveSelectionSentenceJs(sentences: List<Pair<String, String>>): String {
    val sents = JSONArray(
        sentences.map { (id, text) -> JSONArray(listOf(id, text.trim().take(12))) },
    ).toString()
    return """
    (function(){
      var sel=window.__riffleSelRect;
      if(!sel) return "";
      var sents=$sents;
      var iw=window.innerWidth, ih=window.innerHeight;
      var LH=Math.max(8, sel.bottom - sel.top);
      var w=document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false), n;
      var bestId="", bestTop=-1e9, bestLeft=-1e9;
      while(n=w.nextNode()){
        var nv=n.nodeValue;
        for(var k=0;k<sents.length;k++){
          var key=sents[k][1];
          if(!key) continue;
          var i=nv.indexOf(key);
          if(i<0) continue;
          var g=document.createRange(); g.setStart(n,i); g.setEnd(n, Math.min(nv.length, i+1));
          var r=g.getBoundingClientRect();
          // Only sentence-starts on the current page/column.
          if(!(r.left>=0 && r.left<iw && r.bottom>0 && r.top<ih)) continue;
          // At or before the selection start in reading order (above it, or earlier on the same line).
          var before=(r.top < sel.top - LH*0.5) || (Math.abs(r.top - sel.top) <= LH*0.5 && r.left <= sel.left + 1);
          if(!before) continue;
          // Keep the latest such start (greatest top, then greatest left) = the sentence the tap is in.
          if(r.top > bestTop + LH*0.5 || (Math.abs(r.top - bestTop) <= LH*0.5 && r.left > bestLeft)){
            bestTop=r.top; bestLeft=r.left; bestId=sents[k][0];
          }
        }
      }
      return bestId;
    })()
    """.trimIndent()
}

/**
 * The page-follow probe run on each narrated-sentence change (auto-follow). It locates the narrated
 * sentence by its [text] (the same text the highlight is anchored to) rather than by span id —
 * Readium strips the media-overlay sentence spans from the served HTML, so `getElementById` would
 * always miss and the page would snap on EVERY sentence (the jarring per-sentence flip). Finding the
 * sentence's first text node gives its on-screen rect without needing the span. Then per layout:
 *
 *  - Scroll mode (document overflows the viewport): scrolls *vertically* to centre the sentence —
 *    the karaoke follow — and returns "on".
 *  - Paginated mode (page is exactly viewport-sized): KEEP-VISIBLE follow. While the sentence's start
 *    is on the current page it returns "on" and leaves the page untouched, so starting playback — and
 *    the player-open reflow that re-runs this probe — never yanks a visible line onto a fresh column
 *    boundary. Only once the sentence's start is off the current page does it snap scrollLeft to the
 *    COLUMN BOUNDARY containing that start (floor(x / innerWidth) * innerWidth) and return "on". We snap
 *    (rather than report "off" and let the Kotlin side go()) because go() lands flush to the element's
 *    box — a little inside its column — so the page would rest between two columns. The floor snap lands
 *    on the clean page grid; this holds because the reader is sized so innerWidth == Readium's page-snap
 *    pitch (see [alignedReaderWidthDp]). The sentence is located by text (the span id is stripped).
 *
 * Returns "off" only when the text isn't on the current resource (sentence in another chapter) → the
 * Kotlin side's go() jumps chapters, as before.
 */
internal fun autoFollowSnapJs(text: String): String {
    // A short, near-unique prefix of the sentence; matched within a single text node (sentence starts
    // almost never split across nodes). Empty text disables the lookup → "off" (caller's go() fallback).
    val probe = text.trim().take(24)
    return """
    (function(){
      var needle=${JSONObject.quote(probe)};
      if(!needle) return "off";
      var key=needle.slice(0,12);
      var w=document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false), n, r=null;
      while(n=w.nextNode()){
        var i=n.nodeValue.indexOf(key);
        if(i>=0){ var g=document.createRange(); g.setStart(n,i); g.setEnd(n, Math.min(n.nodeValue.length, i+1)); r=g.getBoundingClientRect(); break; }
      }
      if(!r) return "off";
      var se=document.scrollingElement||document.documentElement;
      if(se && se.scrollHeight > window.innerHeight + 4){
        var delta=Math.round((r.top+r.bottom)/2 - window.innerHeight/2);
        if(Math.abs(delta) > 8) window.scrollBy(0, delta);
        return "on";
      }
      var iw=window.innerWidth;
      // Paged keep-visible follow: while the narrated sentence is visible on the current page, leave the
      // page exactly where it is — starting playback (and the player-open reflow that re-runs this probe)
      // must not yank a visible line onto a fresh column boundary. The sentence's START may have wrapped
      // in from the PREVIOUS column (r.left < 0) while its body shows on this page — the column-spanning
      // case — so a start anywhere from the previous column up to the current page's right edge counts as
      // on-page. Only once narration moves FORWARD to a later column (start at/after the next column's
      // left edge, r.left >= iw) do we flip to (snap onto) that column. Scroll mode keeps centring.
      if(r.left >= -iw && r.left < iw) return "on";
      var absX=r.left + se.scrollLeft;
      se.scrollLeft=Math.floor(absX / iw) * iw;
      return "on";
    })()
    """.trimIndent()
}

// Scrolls the current resource so the column CONTAINING [fragmentId] sits flush at the viewport's
// left edge — for an in-document cross-reference tap ("Figure 4.1"). go(cssSelector) lands flush to
// the element's box (a little inside its column → a sliver of the neighbour shows); flooring
// scrollLeft to a multiple of innerWidth lands on the column boundary. Holds because the reader is
// sized so innerWidth == Readium's page-snap pitch (see [alignedReaderWidthDp]). Same floor-to-grid
// math as [autoFollowSnapJs]'s paginated branch, but located by id rather than by sentence text.
internal fun scrollToColumnJs(fragmentId: String): String =
    "(function(){var el=document.getElementById(${JSONObject.quote(fragmentId)});" +
        "if(!el)return;var se=document.scrollingElement;var iw=window.innerWidth;" +
        "var abs=el.getBoundingClientRect().left+se.scrollLeft;" +
        "se.scrollLeft=Math.floor(abs/iw)*iw;})()"

// Lands a go()-based jump (TOC/search/resume) on the column grid, and KEEPS it there until the
// freshly-loaded chapter's typography reflow settles. Run once after go().
//
// Why this isn't a one-shot round-to-nearest: on a cross-resource jump the new chapter loads, then
// the injected typography override (see onPageLoaded) reflows it ASYNCHRONOUSLY — growing
// scrollWidth and pushing the target into a different column a few hundred ms later. A fixed-delay
// snap that rounds the current scroll position locks onto the PRE-reflow column and never corrects,
// leaving the page a page or more before/after the target (the "TOC is hit-and-miss" bug). Instead
// we drive a requestAnimationFrame loop that, every frame, re-locates the target by its [fragmentId]
// and FLOORS scrollLeft to the column the target currently occupies — so it tracks the target as the
// reflow moves it — and stops only once scrollWidth has held steady for a few frames (reflow done) or
// a safety cap elapses. Anchoring to the element (not the current scroll) also makes it robust to
// where exactly go() landed. Same floor-to-grid math and innerWidth==page-pitch assumption as
// [scrollToColumnJs] (see [alignedReaderWidthDp]).
//
// [fragmentId] is the target's element id (a TOC/search locator fragment). When null/empty the jump
// targets a resource start, so we floor to column 0. If the id can't be found (e.g. Readium stripped
// the element) we fall back to rounding the current position — best-effort alignment without yanking
// the page to the top.
internal fun snapToTargetColumnJs(fragmentId: String?): String {
    val idLiteral = if (fragmentId.isNullOrEmpty()) "null" else JSONObject.quote(fragmentId)
    return "(function(){var id=$idLiteral;" +
        "var se=document.scrollingElement;" +
        "var gen=(window.__riffleSnapGen=(window.__riffleSnapGen||0)+1);" +
        "var lastW=-1,stable=0,frames=0;" +
        "function snap(){var iw=window.innerWidth;" +
        "if(id){var el=document.getElementById(id);" +
        "if(el){se.scrollLeft=Math.floor((el.getBoundingClientRect().left+se.scrollLeft)/iw)*iw;}" +
        "else{se.scrollLeft=Math.round(se.scrollLeft/iw)*iw;}}" +
        "else{se.scrollLeft=0;}}" +
        "function tick(){if(gen!==window.__riffleSnapGen)return;" + // a newer jump superseded us
        "var w=se.scrollWidth;if(w===lastW)stable++;else{stable=0;lastW=w;}" +
        "snap();" +
        "if((stable>=3&&frames>=2)||frames++>72){snap();return;}" + // settled, or ~1.2s safety cap
        "requestAnimationFrame(tick);}" +
        "tick();})()"
}

// Captures a short text prefix of the line at the TOP of the current paginated page — the first text
// node in reading order whose first character sits on the current page. Read before the readaloud
// reserve re-paginates the columns, so [reflowAnchorSnapJs] can pin that same line back afterwards:
// the reserve shrinks/grows every column's height, which re-wraps the whole text chain from column 1,
// so without re-anchoring the page lands on different content (the "line jumps when the player opens"
// bug). Returns "" when nothing on-page is found.
internal fun reflowAnchorCaptureJs(): String =
    """
    (function(){
      var iw=window.innerWidth, ih=window.innerHeight;
      var w=document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false), n;
      while(n=w.nextNode()){
        var t=n.nodeValue; if(t.replace(/\s/g,'').length < 4) continue;
        var g=document.createRange(); g.setStart(n,0); g.setEnd(n, Math.min(t.length,1));
        var r=g.getBoundingClientRect();
        if(r.left >= 0 && r.left < iw && r.bottom > 0 && r.top < ih) return t.replace(/^\s+/, '').slice(0,24);
      }
      return "";
    })()
    """.trimIndent()

// Re-pins the page to [anchorPrefix] (captured by [reflowAnchorCaptureJs]) while the readaloud reserve
// re-paginates: a requestAnimationFrame loop that re-locates the prefix each frame and floors
// scrollLeft to the column holding it, until scrollWidth holds steady (reflow done) or a safety cap.
// Same floor-to-grid math and generation guard as [snapToTargetColumnJs] — and it shares
// window.__riffleSnapGen, so a TOC/search jump and this re-anchor supersede each other instead of
// fighting. No-op for an empty prefix.
internal fun reflowAnchorSnapJs(anchorPrefix: String): String {
    if (anchorPrefix.isEmpty()) return "(function(){})()"
    val needle = JSONObject.quote(anchorPrefix)
    return "(function(){var needle=$needle;" +
        "var se=document.scrollingElement;" +
        "var gen=(window.__riffleSnapGen=(window.__riffleSnapGen||0)+1);" +
        "var lastW=-1,stable=0,frames=0;" +
        "function locate(){var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null,false),n;" +
        "while(n=w.nextNode()){var i=n.nodeValue.indexOf(needle);" +
        "if(i>=0){var g=document.createRange();g.setStart(n,i);g.setEnd(n,Math.min(n.nodeValue.length,i+1));return g.getBoundingClientRect();}}return null;}" +
        "function snap(){var iw=window.innerWidth;var r=locate();if(r)se.scrollLeft=Math.floor((r.left+se.scrollLeft)/iw)*iw;}" +
        "function tick(){if(gen!==window.__riffleSnapGen)return;" +
        "var w=se.scrollWidth;if(w===lastW)stable++;else{stable=0;lastW=w;}" +
        "snap();" +
        "if((stable>=3&&frames>=2)||frames++>72){snap();return;}" +
        "requestAnimationFrame(tick);}" +
        "tick();})()"
}

/**
 * Finds the first narrated sentence visible on the current page. [highlights] are the sentence texts
 * in reading order (whole book); only the current chapter's sentences exist in this document's DOM,
 * so the walk naturally ignores the rest. Returns the index into [highlights] of the first sentence
 * whose start is on the current page, or "" when none is found.
 *
 * Unlike [autoFollowSnapJs] this never scrolls — it only reports visibility — so it can probe the
 * page-top without dragging it. "Visible" uses the same containment test as autoFollow's paginated
 * branch (within a tolerance) and, in scroll mode, any vertical overlap with the viewport.
 */
internal fun firstVisibleSentenceJs(highlights: List<String>): String {
    // Same key shape as autoFollowSnapJs: a short near-unique prefix matched within one text node.
    val keys = JSONArray(highlights.map { it.trim().take(12) }).toString()
    // Walk text nodes in document order (== reading order) and return the first key whose start is on
    // the current page. No "already-matched" short-circuit: a key's prefix can coincidentally appear
    // in an earlier off-page node, and skipping it there would lose its real on-page occurrence.
    return """
    (function(){
      var keys=$keys;
      var w=document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false), n;
      var TOL=24;
      while(n=w.nextNode()){
        for(var k=0;k<keys.length;k++){
          var key=keys[k];
          if(!key) continue;
          var i=n.nodeValue.indexOf(key);
          if(i<0) continue;
          var g=document.createRange(); g.setStart(n,i); g.setEnd(n, Math.min(n.nodeValue.length, i+1));
          var r=g.getBoundingClientRect();
          if(r.left >= -TOL && r.right <= window.innerWidth+TOL && r.top < window.innerHeight && r.bottom > 0) return String(k);
        }
      }
      return "";
    })()
    """.trimIndent()
}
