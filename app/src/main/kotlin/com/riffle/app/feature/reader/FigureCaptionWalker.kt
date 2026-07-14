package com.riffle.app.feature.reader

/**
 * JavaScript helpers for resolving figure captions and walking a DOM range for embedded figures
 * (`<img>`, inline `<svg>`, `<picture>`, single-image `<figure>`).
 *
 * These are pure string constants meant to be concatenated into a larger injected script — see
 * [FigureTapScript] (Task 4, per-tap payload) and the highlight-creation script (Task 7, capturing
 * figures that fall inside a new highlight's range). Concatenation must be safe: no `<script>`
 * tags, and each constant is a self-contained `function` declaration (no trailing bare statements)
 * so callers can splice multiple of these together followed by their own call sites.
 *
 * Caption fallback order: `<figcaption>` (nearest ancestor `<figure>`) → `alt` attribute →
 * `aria-label` attribute → nearest following block whose text starts with "Figure N", "Fig. N",
 * "Table N", or "Chart N" (bounded 3-hop ancestor walk — covers LaTeX/Kotobee/Vellum exports
 * with obfuscated class names and no `<figure>` wrapper) → empty string.
 *
 * The text-prefix heuristic sits AFTER `alt`/`aria-label` because those attributes are per-image
 * (accurate), whereas the heuristic is proximity-based (can be fooled by a nearby "Table 3
 * summarizes…" prose block). When an image carries a meaningful alt attribute, that wins.
 */
internal object FigureCaptionWalker {

    /**
     * `function resolveCaption(el)` — returns the best-effort caption text for [el], or `""` if
     * none of the fallbacks resolve.
     */
    val CAPTION_RESOLVER_JS: String = """
        function resolveFigcaptionElement(el) {
            if (!el) return null;
            var fig = el.closest ? el.closest('figure, [role="figure"]') : null;
            if (!fig) return null;
            var cap = fig.querySelector('figcaption');
            if (cap && (cap.textContent || '').trim()) return cap;
            return null;
        }
        function resolveTextPrefixElement(el) {
            if (!el) return null;
            var CAPTION_PREFIX_RX = /^\s*(Figure|Fig\.?|Table|Chart)\s+\d/i;
            var cur = el;
            for (var hops = 0; hops < 3; hops++) {
                var parent = cur.parentElement;
                if (!parent) break;
                var blocks = parent.querySelectorAll('p, div');
                for (var i = 0; i < blocks.length; i++) {
                    var b = blocks[i];
                    if (b === el || b.contains(el)) continue;
                    var pos = el.compareDocumentPosition(b);
                    if (!(pos & 4)) continue;
                    if (CAPTION_PREFIX_RX.test((b.textContent || '').trim())) return b;
                }
                cur = parent;
            }
            return null;
        }
        function resolveCaption(el) {
            if (!el) return "";
            // Order matters: figcaption (per-figure semantic) → alt (per-image attribute) →
            // aria-label (accessibility) → text-prefix block (proximity heuristic — last so a
            // real alt="Photo of author" beats a nearby "Table 3 summarizes…" prose block).
            var cap = resolveFigcaptionElement(el);
            if (cap) return (cap.textContent || '').trim();
            var alt = el.getAttribute && el.getAttribute('alt');
            if (alt) return alt;
            var aria = el.getAttribute && el.getAttribute('aria-label');
            if (aria) return aria;
            var prefix = resolveTextPrefixElement(el);
            if (prefix) return (prefix.textContent || '').trim();
            return "";
        }
        function riffleCollectTextAround(capEl, direction, maxChars) {
            // Walk the document by text-node order, starting from capEl. direction === -1 = walk
            // backwards; +1 = forwards. Collects up to maxChars of text OUTSIDE capEl. Uses a fresh
            // TreeWalker over document.body so caption-boundary probes match how Kotlin locates the
            // snippet in the flattened body text via jsoup.
            if (!capEl || !document.body || !document.body.contains(capEl)) return "";
            var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
            var texts = []; var current = null;
            while ((current = walker.nextNode())) {
                if (capEl.contains(current)) continue;
                var pos = capEl.compareDocumentPosition(current);
                var isBefore = (pos & 2) !== 0; // capEl follows current → current is before
                var isAfter = (pos & 4) !== 0;  // current follows capEl
                if (direction < 0 && isBefore) texts.push(current.data || '');
                else if (direction > 0 && isAfter) texts.push(current.data || '');
            }
            var joined = texts.join('').replace(/\s+/g, ' ');
            if (direction < 0) return joined.slice(-maxChars);
            return joined.slice(0, maxChars);
        }
        function resolveCaptionRange(el) {
            // Non-null iff the caption resolves to a DOM element (figcaption or a text-prefix p/div).
            // Returns null for alt/aria-label captions — those aren't visible ranges we can highlight.
            // For range resolution the figcaption path wins over text-prefix (semantic > proximity);
            // resolveCaption's alt/aria-label paths are skipped here because they carry no range.
            var cap = resolveFigcaptionElement(el) || resolveTextPrefixElement(el);
            if (!cap) return null;
            var text = (cap.textContent || '').replace(/\s+/g, ' ').trim();
            if (!text) return null;
            return {
                text: text,
                textBefore: riffleCollectTextAround(cap, -1, 40),
                textAfter: riffleCollectTextAround(cap, 1, 40),
            };
        }
    """.trimIndent()

    /**
     * `function serializeSvg(svg)` — returns the serialized outer markup of [svg], or `null` if
     * serialization fails or [svg] is falsy.
     */
    val SVG_SERIALIZER_JS: String = """
        function serializeSvg(svg) {
            if (!svg) return null;
            try { return new XMLSerializer().serializeToString(svg); } catch (e) { return null; }
        }
    """.trimIndent()

    /**
     * Includes [CAPTION_RESOLVER_JS] and [SVG_SERIALIZER_JS] plus `function figuresInRange(
     * startNode, endNode)`, which walks the DOM range `[startNode, endNode]` (inclusive) via
     * `TreeWalker`, collecting `img` / `svg` / `picture` / `figure` elements in document order,
     * deduped by resolved target element, and returns
     * `[{ href|null, svg|null, caption, order }]`.
     */
    val FIGURES_IN_RANGE_JS: String = """
        $CAPTION_RESOLVER_JS
        $SVG_SERIALIZER_JS
        function figuresInRange(startNode, endNode) {
            var range = document.createRange();
            range.setStartBefore(startNode);
            range.setEndAfter(endNode);
            var walker = document.createTreeWalker(range.commonAncestorContainer, NodeFilter.SHOW_ELEMENT, {
                acceptNode: function(n) {
                    var tag = (n.tagName || "").toLowerCase();
                    if (tag !== 'img' && tag !== 'svg' && tag !== 'picture' && tag !== 'figure') return NodeFilter.FILTER_SKIP;
                    if (!range.intersectsNode(n)) return NodeFilter.FILTER_REJECT;
                    return NodeFilter.FILTER_ACCEPT;
                }
            });
            var out = []; var seen = new Set(); var order = 0; var node;
            while ((node = walker.nextNode())) {
                var target = node.tagName.toLowerCase() === 'figure'
                    ? (node.querySelector('img') || node.querySelector('svg') || node.querySelector('picture'))
                    : node;
                if (!target || seen.has(target)) continue;
                seen.add(target);
                var tag = target.tagName.toLowerCase();
                var entry = { caption: resolveCaption(target), order: order++ };
                if (tag === 'svg') { entry.svg = serializeSvg(target); entry.href = null; }
                else if (tag === 'picture') {
                    var img = target.querySelector('img');
                    entry.href = img ? img.getAttribute('src') : null; entry.svg = null;
                } else { entry.href = target.getAttribute('src'); entry.svg = null; }
                out.push(entry);
            }
            return out;
        }
    """.trimIndent()
}
