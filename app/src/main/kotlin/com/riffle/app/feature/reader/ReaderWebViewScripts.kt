package com.riffle.app.feature.reader

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
