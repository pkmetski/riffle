package com.riffle.app.feature.reader

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
internal val SELECTION_SPAN_TRACKER_JS = """
    (function () {
      if (window.__riffleSelTrackerInstalled) return;
      window.__riffleSelTrackerInstalled = true;
      document.addEventListener('selectionchange', function () {
        var s = window.getSelection();
        if (!s || s.rangeCount === 0 || s.isCollapsed) return;
        var n = s.getRangeAt(0).startContainer;
        if (n && n.nodeType === 3) n = n.parentElement;
        var id = '';
        while (n && n !== document.body) {
          if (n.id) { id = n.id; break; }
          n = n.parentElement;
        }
        window.__riffleSelSpan = id;
      });
    })();
""".trimIndent()

/**
 * The page-follow probe run on each narrated-sentence change (auto-follow). It locates the narrated
 * sentence by its [text] (the same text the highlight is anchored to) rather than by span id —
 * Readium strips the media-overlay sentence spans from the served HTML, so `getElementById` would
 * always miss and the page would snap on EVERY sentence (the jarring per-sentence flip). Finding the
 * sentence's first text node gives its on-screen rect without needing the span. Then per layout:
 *
 *  - Scroll mode (document overflows the viewport): scrolls *vertically* to centre the sentence —
 *    the karaoke follow — and returns "on".
 *  - Paginated mode (page is exactly viewport-sized): snaps scrollLeft to the COLUMN BOUNDARY that
 *    contains the sentence's start, then returns "on". We snap here rather than report "off" and let
 *    the Kotlin side go(): go() resolves the locator by text/cssSelector and lands flush to the
 *    element's box — a little inside its column — so the page rests between two columns; and a binary
 *    "is it roughly visible?" gate (the old ±tolerance check) never re-aligns a page that's already
 *    drifted between two columns, nor follows promptly when a sentence starts just past the edge.
 *    Snapping scrollLeft to floor(x / innerWidth) * innerWidth lands on the clean page grid every
 *    sentence. This holds because the reader is sized so innerWidth == Readium's page-snap pitch (see
 *    [alignedReaderWidthDp]), so the boundary we pick is exactly where Readium would rest anyway —
 *    here we locate the sentence by text (the span id is stripped) rather than by element.
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
      var absX=r.left + se.scrollLeft;
      se.scrollLeft=Math.floor(absX / iw) * iw;
      return "on";
    })()
    """.trimIndent()
}
