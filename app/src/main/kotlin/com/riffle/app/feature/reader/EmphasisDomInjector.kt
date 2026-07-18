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
          var STYLE_ID = 'riffle-em-styles';
          if (!document.getElementById(STYLE_ID)) {
            var style = document.createElement('style');
            style.id = STYLE_ID;
            style.textContent =
              'span[data-riffle-em~="bold"] { font-weight: bold !important; }' +
              'span[data-riffle-em~="italic"] { font-style: italic !important; }';
            document.head.appendChild(style);
          }
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
          function walk(match) {
            var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
            var node;
            while (node = walker.nextNode()) {
              var text = node.textContent;
              var idx = text.indexOf(match.snippet);
              if (idx < 0) continue;
              if (match.before && match.before.length > 0) {
                // Disambiguate by requiring the textBefore's suffix to line up with the chars
                // preceding idx in the same node. Cross-node disambiguation not covered here —
                // multiline before-context matches are skipped and the first single-node match wins.
                var start = Math.max(0, idx - match.before.length);
                var contextBefore = text.substring(start, idx);
                if (contextBefore && !match.before.endsWith(contextBefore)) continue;
              }
              try {
                var range = document.createRange();
                range.setStart(node, idx);
                range.setEnd(node, idx + match.snippet.length);
                var span = document.createElement('span');
                span.setAttribute('data-riffle-em', match.styles.join(' '));
                range.surroundContents(span);
              } catch (err) {
                // surroundContents throws for ranges spanning element boundaries. The overlay
                // decoration path (Readium underline / Riffle strike / bold/italic tint) still
                // fires as a fallback so the annotation isn't invisible on complex markup.
              }
              return; // one match per annotation
            }
          }
          for (var j = 0; j < annotations.length; j++) walk(annotations[j]);
        })();
    """.trimIndent()
}
