package com.riffle.app.feature.reader

import com.riffle.core.domain.EmphasisStyle
import org.json.JSONArray
import org.json.JSONObject

/**
 * ADR 0046: DOM-level emphasis renderer. Bold and italic can't render via Readium's overlay
 * decorations (overlays don't reflow text), so this injects JavaScript into the current
 * chapter's WebView that WRAPS emphasis ranges in a `<span data-riffle-em="...">` with inline
 * `font-weight` / `font-style`. Underline and strike still ride the overlay decoration path —
 * they're geometrically overlays anyway (a horizontal line) and it's cheaper to keep them there.
 *
 * The script:
 *  1. Removes any prior `[data-riffle-em]` wrappers (idempotent re-apply after toggle).
 *  2. Walks the text nodes under `document.body`, finds each annotation's `textSnippet` (with
 *     `textBefore` as a disambiguator when the snippet repeats), and calls
 *     `range.surroundContents` on the resulting Range. `surroundContents` fails when the range
 *     straddles element boundaries; those are silently skipped and fall back to the overlay
 *     decoration only (worst case: the range paints as amber/purple tint without reflow).
 *  3. Injects a `<style>` block once per document (guarded by an id) so text within the wrapped
 *     span picks up `font-weight`/`font-style`. The style rules use `!important` to override
 *     inline publisher CSS.
 *
 * Only bold and italic are handled here. Underline and strike are still painted by
 * [ReadiumHighlightRenderer.applyEmphasisCompanions] via Readium/custom decorations, so a
 * `{bold, underline}` annotation gets bold from this injector AND the underline from the
 * overlay simultaneously.
 */
internal object EmphasisDomInjector {

    /** Payload sent per annotation from Kotlin → JS. Kept minimal on purpose: any change to this
     *  shape needs a matching edit in [WRAP_SCRIPT_TEMPLATE]. */
    data class EmphasisRange(
        val id: String,
        val textSnippet: String,
        val textBefore: String,
        val styles: Set<EmphasisStyle>,
    )

    /** Build the wrap script for the current chapter's annotations. Callers should only include
     *  annotations that carry BOLD or ITALIC; empty input still yields a valid cleanup-only
     *  script that removes any leftover wrappers from a previous apply. */
    fun script(annotations: List<EmphasisRange>): String {
        val payload = JSONArray().apply {
            for (a in annotations) {
                put(
                    JSONObject()
                        .put("id", a.id)
                        .put("snippet", a.textSnippet)
                        .put("before", a.textBefore)
                        .put(
                            "styles",
                            JSONArray().apply { for (s in a.styles) put(s.token) },
                        ),
                )
            }
        }.toString()
        return WRAP_SCRIPT_TEMPLATE.replace("__ANNOTATIONS__", payload)
    }

    // Language=JavaScript
    private val WRAP_SCRIPT_TEMPLATE = """
        (function() {
          // Clear previous wrappers so we can re-apply cleanly on toggle.
          var existing = document.querySelectorAll('span[data-riffle-em]');
          for (var i = 0; i < existing.length; i++) {
            var e = existing[i];
            var parent = e.parentNode;
            while (e.firstChild) parent.insertBefore(e.firstChild, e);
            parent.removeChild(e);
            parent.normalize();
          }
          var annotations = __ANNOTATIONS__;
          if (!annotations.length) return;
          function styleSpan(styles) {
            var span = document.createElement('span');
            span.setAttribute('data-riffle-em', styles.join(' '));
            // Inline `style.setProperty(..., 'important')` beats every publisher stylesheet
            // (including `!important` on descendants of :root).
            if (styles.indexOf('bold') !== -1) span.style.setProperty('font-weight', 'bold', 'important');
            if (styles.indexOf('italic') !== -1) span.style.setProperty('font-style', 'italic', 'important');
            return span;
          }
          function collectTextNodesInRange(range) {
            var startNode = range.startContainer;
            var endNode = range.endContainer;
            var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
            var nodes = [];
            var include = false;
            var n;
            while (n = walker.nextNode()) {
              if (n === startNode) include = true;
              if (include) nodes.push(n);
              if (n === endNode) break;
            }
            return nodes;
          }
          function wrapRange(range, styles) {
            var startNode = range.startContainer;
            var endNode = range.endContainer;
            var startOff = range.startOffset;
            var endOff = range.endOffset;
            // Fast path: single text node — surroundContents is safe and preserves structure.
            if (startNode === endNode) {
              try {
                range.surroundContents(styleSpan(styles));
                return true;
              } catch (e) {}
            }
            // Multi-node path: walk each text node the range covers and wrap its own portion.
            // Handles `<code>` and other inline elements interrupting the snippet — the
            // wash's range crosses element boundaries so surroundContents throws.
            var nodes = collectTextNodesInRange(range);
            for (var i = 0; i < nodes.length; i++) {
              var node = nodes[i];
              var s = (node === startNode) ? startOff : 0;
              var e = (node === endNode) ? endOff : node.textContent.length;
              if (s >= e) continue;
              // Split so `middle` is exactly [s, e].
              var middle = (s > 0) ? node.splitText(s) : node;
              if (e - s < middle.textContent.length) middle.splitText(e - s);
              var span = styleSpan(styles);
              middle.parentNode.insertBefore(span, middle);
              span.appendChild(middle);
            }
            return true;
          }
          // Build the concatenated body text once so we can find snippets that cross element
          // boundaries — cross-`<code>` ranges (a common publisher pattern) never match a
          // single text-node search. At block-level or `<br>` boundaries between consecutive
          // text nodes we splice in a SYNTHETIC space so `page.full` carries the whitespace
          // Readium's selection captured (a selection spanning `<p>foo</p><p>bar</p>` arrives as
          // "foo\nbar" — without the synthetic space, `page.full` is "foobar" and the match
          // fails). Synthetic pieces carry `synthetic: true` so `rangeFromPageOffsets` knows
          // to snap start/end to the adjacent real text node instead of into a phantom offset.
          function blockAncestor(node) {
            var el = node.parentNode;
            while (el && el !== document.body && el.nodeType === 1) {
              var d = '';
              try { d = window.getComputedStyle(el).display; } catch (e) {}
              if (d === 'block' || d === 'list-item' || d === 'table-cell' ||
                  d === 'table-row' || d === 'flex' || d === 'grid') return el;
              el = el.parentNode;
            }
            return document.body;
          }
          function hasBrBetween(a, b) {
            try {
              var range = document.createRange();
              range.setStartAfter(a);
              range.setEndBefore(b);
              var frag = range.cloneContents();
              return !!(frag.querySelector && frag.querySelector('br'));
            } catch (e) { return false; }
          }
          function isBoundaryBetween(a, b) {
            if (!a) return false;
            if (blockAncestor(a) !== blockAncestor(b)) return true;
            return hasBrBetween(a, b);
          }
          function collectPageText() {
            var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
            var pieces = [];
            var full = '';
            var lastNode = null;
            var n;
            while (n = walker.nextNode()) {
              if (lastNode && isBoundaryBetween(lastNode, n)) {
                // One synthetic space per boundary, only if the running text doesn't already
                // end in whitespace (the previous real piece may have carried a trailing space).
                if (full.length > 0 && !/\s/.test(full.charAt(full.length - 1))) {
                  pieces.push({ node: lastNode, start: full.length, text: ' ', synthetic: true });
                  full += ' ';
                }
              }
              pieces.push({ node: n, start: full.length, text: n.textContent, synthetic: false });
              full += n.textContent;
              lastNode = n;
            }
            return { pieces: pieces, full: full };
          }
          function rangeFromPageOffsets(page, startAbs, endAbs) {
            var range = document.createRange();
            var startSet = false, endSet = false;
            for (var i = 0; i < page.pieces.length; i++) {
              var p = page.pieces[i];
              var pEnd = p.start + p.text.length;
              if (!startSet && startAbs >= p.start && startAbs <= pEnd) {
                if (p.synthetic) {
                  // Match starts inside a synthetic block-gap space — snap forward to the
                  // beginning of the next real text piece.
                  for (var j = i + 1; j < page.pieces.length; j++) {
                    if (!page.pieces[j].synthetic) {
                      range.setStart(page.pieces[j].node, 0);
                      startSet = true;
                      break;
                    }
                  }
                } else {
                  range.setStart(p.node, startAbs - p.start);
                  startSet = true;
                }
              }
              if (!endSet && endAbs >= p.start && endAbs <= pEnd) {
                if (p.synthetic) {
                  // Match ends inside a synthetic gap — snap back to the end of the previous
                  // real text piece.
                  for (var k = i - 1; k >= 0; k--) {
                    if (!page.pieces[k].synthetic) {
                      range.setEnd(page.pieces[k].node, page.pieces[k].node.textContent.length);
                      endSet = true;
                      break;
                    }
                  }
                } else {
                  range.setEnd(p.node, endAbs - p.start);
                  endSet = true;
                }
              }
              if (startSet && endSet) break;
            }
            return (startSet && endSet) ? range : null;
          }
          // Escape regex metacharacters, then collapse any whitespace run into `\s+` so a
          // snippet whose "\n" comes from a paragraph boundary matches page.full's synthetic
          // space (and vice versa) without needing exact whitespace-character equivalence.
          function toWsTolerantRegex(str) {
            var escaped = str.replace(/[.*+?^${'$'}{}()|[\]\\]/g, '\\${'$'}&');
            return escaped.replace(/\s+/g, '\\s+');
          }
          var page = null;
          function walk(match) {
            if (!page) page = collectPageText();
            var re = new RegExp(toWsTolerantRegex(match.snippet), 'g');
            var beforeRe = (match.before && match.before.length > 0)
              ? new RegExp(toWsTolerantRegex(match.before) + '${'$'}') : null;
            re.lastIndex = 0;
            while (true) {
              var m = re.exec(page.full);
              if (!m) return;
              var idx = m.index;
              var matchLen = m[0].length;
              if (beforeRe) {
                var ctx = page.full.substring(0, idx);
                if (!beforeRe.test(ctx)) {
                  re.lastIndex = idx + 1;
                  continue;
                }
              }
              var range = rangeFromPageOffsets(page, idx, idx + matchLen);
              if (range) {
                wrapRange(range, match.styles);
                // Invalidate the cached page — splitText mutated the DOM.
                page = null;
              }
              return;
            }
          }
          for (var j = 0; j < annotations.length; j++) walk(annotations[j]);
        })();
    """.trimIndent()
}
