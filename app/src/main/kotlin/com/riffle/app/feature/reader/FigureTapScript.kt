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
 * Also wires a long-press (500ms) listener that reuses the same `findFigure` hit-test (extracted
 * as `detectFigureAt(x, y)` below) and posts a richer payload — including resolved caption and
 * serialized SVG source via [FigureCaptionWalker] — through `RiffleFigureBridge.onFigureLongPress`
 * (Task 5 adds the Kotlin side of that `@JavascriptInterface` method). Long-press is wired
 * unconditionally alongside tap so it rides along in all three reader modes (paginated, vertical,
 * continuous) the same way tap does — see [ChapterWebView] and [DefaultRendererBridge] call sites.
 *
 * Finally, exposes `window.riffleFiguresInsideRange(cfiRange)` so Kotlin (Task 7) can scan a
 * highlight's CFI range for enclosed figures via `evaluateJavascript`. The CFI-string-to-DOM-range
 * resolution isn't implemented yet — no existing JS helper in this codebase resolves an EPUB CFI to
 * DOM nodes (Readium's own CFI resolution lives on the Kotlin/navigator side, not injected JS), so
 * this is a stub that Task 7 completes once it decides how to obtain start/end DOM nodes for a CFI
 * range client-side.
 *
 * The bridge argument [bridgeName] is the `@JavascriptInterface`-registered object name — either
 * `RiffleChapter` (continuous — extended with `onFigureTap`) or `RiffleFigureBridge` (paged).
 */
internal object FigureTapScript {

    /** Global name of the paged/vertical figure-tap JS interface. */
    const val PAGED_BRIDGE_NAME: String = "RiffleFigureBridge"

    /**
     * Global name of the continuous-mode figure-tap JS interface. This is [ChapterWebView]'s
     * existing per-chapter bridge object; continuous mode reuses it rather than registering a
     * second one. Kept as a constant here so both the JS install site and the JS message-send
     * sites resolve to the same literal — a rename can't leave one path silently no-op.
     */
    const val CONTINUOUS_BRIDGE_NAME: String = "RiffleChapter"

    fun installScript(bridgeName: String): String = """
        ${FigureCaptionWalker.CAPTION_RESOLVER_JS}
        ${FigureCaptionWalker.SVG_SERIALIZER_JS}
        ${FigureCaptionWalker.FIGURES_IN_RANGE_JS}
        (function() {
            if (document.__riffleFigureTapWired) return;
            document.__riffleFigureTapWired = true;
            var MAX_SVG_BYTES = 256 * 1024;
            // Suppress Android WebView's built-in image callout (Save / Copy Image) on figures.
            // Without this, the native context menu wins the long-press race, canceling the
            // touch sequence before our 500ms timer resolves — long-press then does nothing.
            try {
                var style = document.createElement('style');
                style.textContent = 'img,svg,picture,figure{-webkit-touch-callout:none;-webkit-user-select:none;user-select:none;}';
                (document.head || document.documentElement).appendChild(style);
            } catch (e) {}
            function findFigure(target) {
                // Walk up to body FIRST looking for an anchor-with-href ancestor OR a synthesised
                // Highlights-view figure block. If either is found before we find a figure
                // candidate, the tap is not the reader-mode "open figure zoom" gesture and we
                // return null so the appropriate downstream handler runs:
                //   - <a href>: existing footnote / cross-reference / external-link router.
                //   - <figure class="riffle-fig">: the accent-bar tap span's own onclick, which
                //     dispatches to the annotation editor via the riffle:// URL scheme. Without
                //     this skip, the capture-phase click here would swallow the tap and open the
                //     figure-zoom overlay instead — the "tap accent bar → zoom instead of edit"
                //     bug (fix 2026-07-10).
                var scan = target;
                while (scan && scan.nodeType === 1 && scan !== document.body) {
                    var stag = scan.tagName ? scan.tagName.toLowerCase() : '';
                    if (stag === 'a' && scan.getAttribute && scan.getAttribute('href')) return null;
                    if (stag === 'figure' && scan.classList && scan.classList.contains('riffle-fig')) return null;
                    scan = scan.parentNode;
                }
                // Now walk up to find the actual figure candidate.
                var el = target;
                while (el && el.nodeType === 1 && el !== document.body) {
                    var tag = el.tagName ? el.tagName.toLowerCase() : '';
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
            // Shared hit-test used by both the click (tap) and touchstart (long-press) paths, so a
            // future change to figure/anchor detection can't drift between the two.
            function detectFigureAt(x, y) {
                var el = document.elementFromPoint(x, y);
                if (!el) return null;
                return findFigure(el);
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
            var longPressTimer = null;
            var longPressTarget = null;
            var longPressStartX = 0;
            var longPressStartY = 0;
            var LONG_PRESS_MOVE_THRESHOLD = 12; // CSS px — matches Android's default touchSlop
            document.addEventListener('touchstart', function(e) {
                var t = e.touches && e.touches[0];
                if (!t) return;
                var el = detectFigureAt(t.clientX, t.clientY);
                if (!el) return;
                longPressTarget = el;
                longPressStartX = t.clientX;
                longPressStartY = t.clientY;
                longPressTimer = setTimeout(function() {
                    if (!longPressTarget) return;
                    // Immediate visual signal that the long-press was detected — before any Kotlin
                    // work runs. If the user sees this flash and no annotation appears, the JS→Kotlin
                    // path is the problem; if they don't see this flash, the touch never reached us.
                    try {
                        var prev = longPressTarget.style.outline;
                        var prevOffset = longPressTarget.style.outlineOffset;
                        longPressTarget.style.outline = '3px solid #2196f3';
                        longPressTarget.style.outlineOffset = '2px';
                        setTimeout(function(t, o, oo) { return function() { t.style.outline = o; t.style.outlineOffset = oo; }; }(longPressTarget, prev, prevOffset), 300);
                    } catch (e) {}
                    var tag = longPressTarget.tagName ? longPressTarget.tagName.toLowerCase() : '';
                    var r = longPressTarget.getBoundingClientRect();
                    // Rasterise the figure into a data URI so the panel row + Highlights-mode view
                    // can display it without needing to reload the source Publication container.
                    // Both raster <img> and inline <svg> go through a canvas — for SVG the
                    // element is serialized to an SVG data URI first and re-drawn on the canvas.
                    // Skip silently if the WebView blocks toDataURL on cross-origin content.
                    var imageBytes = null;
                    try {
                        var srcW = tag === 'svg' ? Math.round(r.width) : (longPressTarget.naturalWidth || Math.round(r.width));
                        var srcH = tag === 'svg' ? Math.round(r.height) : (longPressTarget.naturalHeight || Math.round(r.height));
                        if (srcW > 0 && srcH > 0) {
                            var cvs = document.createElement('canvas');
                            var maxSide = 800;
                            var scale = Math.min(1, maxSide / Math.max(srcW, srcH));
                            cvs.width = Math.round(srcW * scale);
                            cvs.height = Math.round(srcH * scale);
                            var ctx = cvs.getContext('2d');
                            ctx.fillStyle = '#ffffff';
                            ctx.fillRect(0, 0, cvs.width, cvs.height);
                            if (tag === 'img' || tag === 'picture') {
                                var imgEl = tag === 'picture' ? longPressTarget.querySelector('img') : longPressTarget;
                                if (imgEl) ctx.drawImage(imgEl, 0, 0, cvs.width, cvs.height);
                                imageBytes = cvs.toDataURL('image/jpeg', 0.85);
                            }
                        }
                    } catch (e5) {}
                    var payload = {
                        kind: tag,
                        caption: resolveCaption(longPressTarget),
                        href: tag === 'svg' ? null : (longPressTarget.currentSrc || longPressTarget.getAttribute('src') || null),
                        svg: tag === 'svg' ? serializeSvg(longPressTarget) : null,
                        elementId: longPressTarget.id || null,
                        rectX: Math.round(r.left),
                        rectY: Math.round(r.top),
                        rectW: Math.round(r.width),
                        rectH: Math.round(r.height),
                        imageBytes: imageBytes,
                    };
                    try {
                        window.$bridgeName.onFigureLongPress(JSON.stringify(payload));
                    } catch (err) {}
                    longPressTarget = null;
                }, 500);
            }, true);
            document.addEventListener('touchmove', function(e) {
                if (!longPressTimer) return;
                var t = e.touches && e.touches[0];
                if (!t) return;
                var dx = t.clientX - longPressStartX;
                var dy = t.clientY - longPressStartY;
                if (dx * dx + dy * dy > LONG_PRESS_MOVE_THRESHOLD * LONG_PRESS_MOVE_THRESHOLD) {
                    clearTimeout(longPressTimer);
                    longPressTimer = null;
                    longPressTarget = null;
                }
            }, true);
            document.addEventListener('touchend', function() {
                if (longPressTimer) { clearTimeout(longPressTimer); longPressTimer = null; }
                longPressTarget = null;
            }, true);
            // Cancel the native long-press callout on figures — belt to the CSS's braces above,
            // for WebView builds where the CSS property alone is respected too late.
            document.addEventListener('contextmenu', function(e) {
                if (findFigure(e.target)) e.preventDefault();
            }, true);
        })();
        window.riffleFiguresInsideRange = window.riffleFiguresInsideRange || function(cfiRange) {
            // TODO(Task 7): resolve cfiRange to start/end DOM nodes. No existing JS helper in this
            // codebase performs CFI-string-to-DOM-node resolution today (Readium's CFI handling is
            // Kotlin/navigator-side); this stub returns an empty result until Task 7 wires a
            // resolver. Once start/end nodes are available, call figuresInRange(start, end).
            return JSON.stringify([]);
        };
    """.trimIndent()
}
