package com.riffle.app.feature.reader

/**
 * JavaScript that hit-tests taps against `<img>`, inline `<svg>`, `<picture>`, and single-image
 * `<figure>` elements, then posts the tap through the `RiffleChapter` (continuous) or
 * `RiffleFigureBridge` (paged/vertical) JS interfaces.
 *
 * Design notes:
 *
 *  - Runs on `click` in the CAPTURE phase, BEFORE the existing tap-to-toggle-immersive router
 *    (see [ContinuousScriptInjector.TAP_LISTENER_JS]) and BEFORE the same-doc anchor listener,
 *    so `event.stopPropagation() + preventDefault()` here means those handlers never fire.
 *  - Excludes images inside `<a href>` when the anchor is a link (any href): those must remain
 *    clickable as footnotes / cross-references / external links. Only bare figures open the
 *    viewer.
 *  - `<svg>` targets: we capture `outerHTML` so the overlay can render the vector as-is without
 *    re-serialising every child manually. Bounded at 256KB to avoid pathological cases.
 *  - `<img>` targets: we send `src` (which is a resolved absolute URL in the WebView context — the
 *    overlay strips the `readium_package://` prefix if present to build the Publication href).
 *    Falls back to `currentSrc` for `<picture>`. Data URIs are passed through verbatim.
 *  - Idempotent via `document.__riffleFigureTapWired`.
 *
 * The bridge argument [bridgeName] is the `@JavascriptInterface`-registered object name — either
 * `RiffleChapter` (continuous — extended with `onFigureTap`) or `RiffleFigureBridge` (paged).
 */
internal object FigureTapScript {

    /** Global name of the paged/vertical figure-tap JS interface. */
    const val PAGED_BRIDGE_NAME: String = "RiffleFigureBridge"

    fun installScript(bridgeName: String): String = """
        (function() {
            if (document.__riffleFigureTapWired) return;
            document.__riffleFigureTapWired = true;
            var MAX_SVG_BYTES = 256 * 1024;
            function findFigure(target) {
                var el = target;
                // Walk up looking for the first candidate. Stop at body — don't zoom whole chapter.
                while (el && el.nodeType === 1 && el !== document.body) {
                    var tag = el.tagName ? el.tagName.toLowerCase() : '';
                    // If we hit an anchor with an href on the way up, this image is a link.
                    // Let the existing anchor router handle the tap.
                    if (tag === 'a' && el.getAttribute && el.getAttribute('href')) return null;
                    if (tag === 'img' || tag === 'svg' || tag === 'picture') return el;
                    if (tag === 'figure') {
                        // Find single image-like child; skip if the figure contains other content.
                        var kids = el.querySelectorAll ? el.querySelectorAll('img, svg, picture') : [];
                        if (kids && kids.length === 1) return kids[0];
                        return null;
                    }
                    el = el.parentNode;
                }
                return null;
            }
            function payloadFor(el) {
                var tag = el.tagName ? el.tagName.toLowerCase() : '';
                var r = el.getBoundingClientRect();
                if (tag === 'img') {
                    var src = el.currentSrc || el.src;
                    if (!src) return null;
                    var w = el.naturalWidth || Math.round(r.width);
                    var h = el.naturalHeight || Math.round(r.height);
                    return { kind: 'img', href: src, w: w, h: h };
                }
                if (tag === 'picture') {
                    var img = el.querySelector('img');
                    if (!img) return null;
                    var src2 = img.currentSrc || img.src;
                    if (!src2) return null;
                    var w2 = img.naturalWidth || Math.round(r.width);
                    var h2 = img.naturalHeight || Math.round(r.height);
                    return { kind: 'img', href: src2, w: w2, h: h2 };
                }
                if (tag === 'svg') {
                    var html = el.outerHTML || '';
                    if (!html || html.length > MAX_SVG_BYTES) return null;
                    var w3 = Math.round(r.width);
                    var h3 = Math.round(r.height);
                    if (w3 <= 0 || h3 <= 0) return null;
                    return { kind: 'svg', svg: html, w: w3, h: h3 };
                }
                return null;
            }
            document.addEventListener('click', function(e) {
                var fig = findFigure(e.target);
                if (!fig) return;
                var p = payloadFor(fig);
                if (!p) return;
                try {
                    window.$bridgeName.onFigureTap(JSON.stringify(p));
                    e.preventDefault();
                    e.stopPropagation();
                } catch (err) {}
            }, true);
        })();
    """.trimIndent()
}
