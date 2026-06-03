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
 * The page-follow probe run on each narrated-sentence change (auto-follow). Given the narrated
 * sentence's element id [fragId], it reports whether the page already shows the sentence ("on") or
 * must snap to it ("off"), and keeps it on screen per layout:
 *
 *  - Scroll mode (document overflows the viewport): scrolls *vertically* to centre the sentence —
 *    the karaoke follow — and returns "on".
 *  - Paginated mode (page is exactly viewport-sized): can't scroll, so it returns "on" only when the
 *    sentence is fully horizontally contained (within a tolerance), else "off" so the Kotlin side
 *    snaps to its page via go().
 *
 * Crucially it only ever scrolls the Y axis (`scrollBy(0, …)`) — never X — so the page cannot drift
 * sideways while narrating.
 */
internal fun autoFollowSnapJs(fragId: String): String = """
    (function(){
      var e=document.getElementById(${JSONObject.quote(fragId)});
      if(!e) return "off";
      var r=e.getBoundingClientRect();
      var se=document.scrollingElement||document.documentElement;
      if(se && se.scrollHeight > window.innerHeight + 4){
        var delta=Math.round((r.top+r.bottom)/2 - window.innerHeight/2);
        if(Math.abs(delta) > 8) window.scrollBy(0, delta);
        return "on";
      }
      var TOL=24;
      return (r.left >= -TOL && r.right <= window.innerWidth+TOL && r.top < window.innerHeight && r.bottom > 0) ? "on" : "off";
    })()
""".trimIndent()
