package com.riffle.app.feature.reader

import org.json.JSONArray

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
        sentences.map { (id, text) ->
            // Drop punctuation-only "sentences" (Storyteller emits fragments like `…”` as their own
            // narrated span). Their 1–2 char key matches that same punctuation INSIDE a real sentence —
            // e.g. the `…”` in The Martian ch16's "…to save lives, I'd…" He thought…" — and, sitting later
            // than the real sentence's start, wrongly wins. Require a few letters/digits to be a usable
            // locator; the JS skips empty keys. (Cross-chapter false matches are handled upstream by
            // scoping the list to the chapter being read — see scopeSentencesToChapter.)
            val key = text.trim().take(12)
            val usable = if (key.count(Char::isLetterOrDigit) >= 3) key else ""
            JSONArray(listOf(id, usable))
        },
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
