@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.riffle.app.feature.reader

import org.json.JSONArray

/**
 * JavaScript snippets EpubReaderScreen injects into every reflowable page (see the
 * paginationListener's onPageLoaded). Kept together — and out of the screen file — alongside the
 * other injected-script homes ([TypographyOverride], [FootnoteAnchorBridge]). All are idempotent so
 * repeated firing during reflow is harmless.
 */

// Two polyfills that Readium's reflowable engine needs but the older Android WebView (API ≤ 27,
// Chrome ≤ 55) is missing:
//
//   * `ClientRect.toJSON()` — Readium 3.0.0's tap hit-test calls `getBoundingClientRect().toJSON()`;
//     without it every tap on an activable decoration (e.g. the readaloud "now speaking" highlight)
//     throws before Android.onTap fires and the immersive-mode toggle silently stops working.
//
//   * `document.body.append(...)` — Readium 3.2.0+'s decoration renderer creates the group container
//     via `document.body.append(el)` (ChildNode.append, only in Chrome 54+). Without it EVERY
//     `applyDecorations` call throws before injecting any `<div id="r2-decoration-…">`, so persisted
//     annotation highlights AND the readaloud sentence marker are entirely invisible on those
//     WebViews. Polyfill delegates to `appendChild` so Readium's DOM-append path succeeds.
//
// Both are guarded so they're no-ops on engines that already ship them.
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
      // ChildNode.append / ParentNode.append polyfill. Readium 3.2.0+ calls document.body.append(el)
      // from its decoration renderer; older WebViews (Chrome < 54) only have appendChild.
      try {
        function installAppend(proto) {
          if (proto && typeof proto.append !== 'function') {
            Object.defineProperty(proto, 'append', {
              configurable: true, writable: true, enumerable: false,
              value: function () {
                var doc = this.ownerDocument || document;
                for (var i = 0; i < arguments.length; i++) {
                  var node = arguments[i];
                  this.appendChild(typeof node === 'string' ? doc.createTextNode(node) : node);
                }
              }
            });
          }
        }
        [
          typeof Element !== 'undefined' ? Element.prototype : null,
          typeof Document !== 'undefined' ? Document.prototype : null,
          typeof DocumentFragment !== 'undefined' ? DocumentFragment.prototype : null,
        ].forEach(installAppend);
      } catch (e) {}
      // Object.entries / Object.values polyfill. Readium 3.2.0+'s decoration positioner iterates
      // per-decoration state via Object.entries(state); Chrome < 54 lacks these and every
      // decoration positioning pass throws BEFORE the child boxes are appended into the group
      // container. That's why the group div appears with data-group="annotations" but no children.
      try {
        if (typeof Object.entries !== 'function') {
          Object.entries = function (obj) {
            var keys = Object.keys(Object(obj));
            var out = new Array(keys.length);
            for (var i = 0; i < keys.length; i++) out[i] = [keys[i], obj[keys[i]]];
            return out;
          };
        }
        if (typeof Object.values !== 'function') {
          Object.values = function (obj) {
            var keys = Object.keys(Object(obj));
            var out = new Array(keys.length);
            for (var i = 0; i < keys.length; i++) out[i] = obj[keys[i]];
            return out;
          };
        }
      } catch (e) {}
      // ResizeObserver stub. Chrome < 64 lacks it; Readium 3.3.0's reflowable engine constructs
      // one at init to re-position decorations on viewport size changes. A no-op stub is enough
      // to keep init from throwing — on this WebView the viewport is stable per page, so nothing
      // else depends on the observer firing. Decorations get re-placed by our reflow/pageLoad
      // reapply loop.
      try {
        if (typeof window.ResizeObserver !== 'function') {
          window.ResizeObserver = function ResizeObserverPolyfill(_cb) {
            this.observe = function () {};
            this.unobserve = function () {};
            this.disconnect = function () {};
          };
        }
      } catch (e) {}
      // Array.prototype.flat / flatMap polyfill. Readium 3.2.0+'s text-anchoring pipeline uses
      // `.flatMap` when resolving a TextQuoteSelector to a DOM Range; without it the resolver
      // throws inside Readium's decoration positioner and every decoration lands with an empty
      // `range: {}`, which reads as "found nothing" downstream — the div is registered but never
      // sized/placed, so no highlight box appears on the page.
      try {
        if (typeof Array.prototype.flat !== 'function') {
          Object.defineProperty(Array.prototype, 'flat', {
            configurable: true, writable: true, enumerable: false,
            value: function (depth) {
              depth = depth === undefined ? 1 : Math.floor(Number(depth)) || 0;
              var out = [];
              (function step(arr, d) {
                for (var i = 0; i < arr.length; i++) {
                  var v = arr[i];
                  if (d > 0 && Array.isArray(v)) step(v, d - 1);
                  else out.push(v);
                }
              })(this, depth);
              return out;
            }
          });
        }
        if (typeof Array.prototype.flatMap !== 'function') {
          Object.defineProperty(Array.prototype, 'flatMap', {
            configurable: true, writable: true, enumerable: false,
            value: function (cb, thisArg) {
              return this.map(cb, thisArg).flat(1);
            }
          });
        }
      } catch (e) {}
      // Object.fromEntries + String.prototype.padStart — smaller cousins of the above; Readium
      // may reach for them elsewhere and a missing built-in throws before subsequent code runs.
      try {
        if (typeof Object.fromEntries !== 'function') {
          Object.fromEntries = function (iter) {
            var out = {};
            var arr = Array.isArray(iter) ? iter : Array.from(iter);
            for (var i = 0; i < arr.length; i++) {
              var pair = arr[i];
              out[pair[0]] = pair[1];
            }
            return out;
          };
        }
        if (typeof String.prototype.padStart !== 'function') {
          Object.defineProperty(String.prototype, 'padStart', {
            configurable: true, writable: true, enumerable: false,
            value: function (targetLength, padString) {
              targetLength = targetLength >> 0;
              padString = String(padString !== undefined ? padString : ' ');
              if (this.length >= targetLength || padString.length === 0) return String(this);
              var pad = '';
              var need = targetLength - this.length;
              while (pad.length < need) pad += padString;
              return pad.slice(0, need) + String(this);
            }
          });
        }
      } catch (e) {}
    })();
""".trimIndent()

/**
 * Re-registers the decoration templates AFTER polyfills are installed. Readium calls
 * `readium.registerDecorationTemplates(...)` once at resource-load time — which is BEFORE our
 * polyfill install runs, so on old-WebView Chromium (missing Object.entries) the first registration
 * throws inside the CSS-injection loop and neither the internal template map nor the `<style>` tag
 * lands. Subsequent applyDecorations calls don't re-register — they only send the diff — so once
 * that first registration is lost, no decoration ever renders. This script pushes a fresh
 * registration through the (now-polyfilled) engine so `.riffle-highlight-tint` CSS makes it into
 * the page and the internal template map is populated in time for the next applyDecorations diff.
 */
internal fun readiumDecorationTemplatesRegisterJs(): String {
    val templatesJson = riffleDecorationTemplates().toJSON().toString().replace("\\n", " ")
    return "try { readium.registerDecorationTemplates($templatesJson); } catch (e) {}"
}

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
/**
 * Reads the current text selection for the continuous-mode action-mode menu handlers ("Highlight",
 * "Copy", "Share", "Play from here"). Returns a JSON payload with `text`, within-chapter
 * progression `p`, the range's viewport rect (`l`/`t`/`r`/`b`, CSS px), and 60-char before/after
 * context (`bef`/`aft`).
 *
 * Prefers the pre-stashed payload written by [SELECTION_SPAN_TRACKER_JS] on 'selectionchange' —
 * the framework collapses the live DOM selection between the user's menu tap and this async
 * `evaluateJavascript` in Chromium WebView on Android 13+, so a live `window.getSelection()` read
 * here often returns empty. That was the "highlight not saved in continuous mode" regression:
 * the stash captures the same fields at last-live selection and survives the collapse.
 *
 * Falls back to a live read for pre-tracker paths (tests / rotation edge cases). Kept in one
 * place so the read/stash pair can't drift out of sync — the tracker writes the same field names
 * this reader consumes.
 */
internal val CONTINUOUS_SELECTION_READ_JS = """
    (function() {
        var stash = window.__riffleSelData;
        if (stash && stash.text) {
            return JSON.stringify({
                text: stash.text, p: stash.p,
                l: stash.l, t: stash.t, r: stash.r, b: stash.b,
                bef: stash.bef || '', aft: stash.aft || ''
            });
        }
        var sel = window.getSelection ? window.getSelection() : null;
        var text = sel ? sel.toString() : '';
        var prog = 0.0;
        var l = 0, t = 0, r = 0, b = 0;
        var bef = '', aft = '';
        if (sel && sel.rangeCount > 0) {
            var range = sel.getRangeAt(0);
            var rect = range.getBoundingClientRect();
            var docH = Math.max(
                document.documentElement ? document.documentElement.offsetHeight : 0,
                document.body ? document.body.offsetHeight : 0, 1
            );
            prog = Math.max(0, Math.min(1, rect.top / docH));
            l = rect.left; t = rect.top; r = rect.right; b = rect.bottom;
            var body = document.body;
            if (body) {
                try {
                    var beforeR = document.createRange();
                    beforeR.selectNodeContents(body);
                    beforeR.setEnd(range.startContainer, range.startOffset);
                    bef = beforeR.toString().slice(-60);
                } catch (e) { bef = ''; }
                try {
                    var afterR = document.createRange();
                    afterR.selectNodeContents(body);
                    afterR.setStart(range.endContainer, range.endOffset);
                    aft = afterR.toString().slice(0, 60);
                } catch (e) { aft = ''; }
            }
        }
        return JSON.stringify({text: text, p: prog, l: l, t: t, r: r, b: b, bef: bef, aft: aft});
    })()
""".trimIndent()

internal val SELECTION_SPAN_TRACKER_JS = """
    (function () {
      if (window.__riffleSelTrackerInstalled) return;
      window.__riffleSelTrackerInstalled = true;
      document.addEventListener('selectionchange', function () {
        var s = window.getSelection();
        // Ignore transient collapse/clear events dispatched right before the framework fires the
        // action-mode menu handler — they would otherwise wipe __riffleSelData between the user's
        // menu tap and our async evaluateJavascript reading it. Keep the LAST non-empty stash.
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
        try {
          var br = rng.getBoundingClientRect();
          if (window.RiffleSelBridge) {
            window.RiffleSelBridge.onRect(br.left, br.top, br.right, br.bottom);
          }
        } catch (e) {}
        // Stash the full selection payload (text + progression + range rect + before/after context)
        // needed by the continuous-mode Highlight / Copy / Share menu handlers. The live DOM
        // selection is gone by the time the action-mode menu handler runs (framework collapses it
        // in-between), so those handlers read from this stash instead of window.getSelection().
        try {
          var text = s.toString();
          if (text) {
            var br2 = rng.getBoundingClientRect();
            var docH = Math.max(
              document.documentElement ? document.documentElement.offsetHeight : 0,
              document.body ? document.body.offsetHeight : 0, 1
            );
            var bef = '', aft = '';
            var body = document.body;
            if (body) {
              try {
                var beforeR = document.createRange();
                beforeR.selectNodeContents(body);
                beforeR.setEnd(rng.startContainer, rng.startOffset);
                bef = beforeR.toString().slice(-60);
              } catch (e) { bef = ''; }
              try {
                var afterR = document.createRange();
                afterR.selectNodeContents(body);
                afterR.setStart(rng.endContainer, rng.endOffset);
                aft = afterR.toString().slice(0, 60);
              } catch (e) { aft = ''; }
            }
            window.__riffleSelData = {
              text: text,
              p: Math.max(0, Math.min(1, br2.top / docH)),
              l: br2.left, t: br2.top, r: br2.right, b: br2.bottom,
              bef: bef, aft: aft
            };
          }
        } catch (e) {}
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
