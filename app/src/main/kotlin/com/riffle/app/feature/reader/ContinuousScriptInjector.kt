package com.riffle.app.feature.reader

/**
 * Page-infrastructure JavaScript injected into every continuous-mode [ChapterWebView] after load.
 *
 * These three scripts are wired once per page by [ChapterWebView.injectStylesAndMeasure] and
 * are independent of user preferences — they scaffold the plumbing that connects the JS world
 * back to native:
 *
 *  1. [HEIGHT_MEASUREMENT_JS] — reports content height (and re-reports on reflow) via
 *     `window.RiffleChapter.onHeightMeasured` so the parent can size the WebView exactly.
 *  2. [TAP_LISTENER_JS] — forwards background taps to `window.RiffleChapter.onTap` to toggle
 *     reader chrome, matching the Readium navigator's `InputListener.onTap` behaviour.
 *  3. [FOOTNOTE_LISTENER_JS] — intercepts same-document anchor clicks before WebView's default
 *     in-page scroll and routes them through `window.RiffleChapter.onFootnoteAnchorTap`.
 *
 * Decoration JS (readaloud highlights, search, annotations) lives in [ContinuousStyleInjector]
 * because it is applied dynamically (not once at load time) and is tightly coupled to the
 * decoration-specific data types and callers in [ContinuousReaderView].
 */
internal object ContinuousScriptInjector {

    /**
     * JS that reports the chapter's CSS-pixel content height to
     * `window.RiffleChapter.onHeightMeasured`, and KEEPS reporting whenever the height changes.
     *
     * A single measurement is fragile: if it lands before the final reflow (late web-font swap,
     * image decode, ReadiumCSS type-scale settling) the WebView's height is locked too short,
     * which clips the last line of the chapter and makes content jump when the sliding window
     * shifts using the stale height. Instead we:
     *
     *  - Measure the true content extent — the body's bottom edge plus the `:root` bottom padding
     *    ReadiumCSS adds in scroll mode — maxed with the body/root *offset* heights. We avoid
     *    `documentElement.scrollHeight`: the root scrolling element's scrollHeight is clamped to
     *    `max(content, viewport)`, so a chapter shorter than the viewport (a chapter-title divider
     *    page) would measure a full screen tall and insert a large blank gap after it.
     *  - Convert CSS px → device px via `window.devicePixelRatio` before reporting. The parent sizes
     *    `WebView.layoutParams.height` (device px) directly from this value, so without the
     *    conversion a chapter measured at e.g. 2602 CSS px on a 2.625-density screen would get a
     *    2602-device-px view — only ~38% of its true height — clipping the rest of the chapter.
     *  - Re-report on every later layout change via a [ResizeObserver] (plus a couple of delayed
     *    safety re-measures), de-duplicated so a stable height reports exactly once. The parent
     *    [ContinuousReaderView] compensates scroll for height changes above the viewport, so late
     *    growth never shifts the line being read.
     */
    val HEIGHT_MEASUREMENT_JS = """
        (function() {
            if (window.__riffleMeasureWired) return;
            window.__riffleMeasureWired = true;
            function currentHeight() {
                var de = document.documentElement;
                var b = document.body;
                // True content extent = body's bottom edge in document space + the :root bottom
                // padding ReadiumCSS adds in scroll mode. We deliberately do NOT use any
                // scrollHeight here: ContinuousStyleInjector forces `overflow: visible` on the
                // root element, which makes the body the effective scroll container in Chrome's
                // rendering model. Both documentElement.scrollHeight and body.scrollHeight are
                // therefore clamped to max(content, viewport), so a chapter shorter than the
                // viewport (e.g. a chapter-title divider page with only a heading) would measure
                // a full screen tall and insert a large blank gap after it in the continuous
                // stack. de.offsetHeight / b.offsetHeight are border-box heights and are NOT
                // viewport-clamped, so they (and the body-bottom measure) give the real height
                // for both short and tall chapters.
                var bodyBottom = b ? (b.getBoundingClientRect().bottom + (window.pageYOffset || 0)) : 0;
                var rootPadBottom = 0;
                if (de) {
                    var pb = parseFloat(getComputedStyle(de).paddingBottom);
                    if (pb === pb) rootPadBottom = pb; // NaN-guard without isNaN()
                }
                var cssH = Math.max(
                    bodyBottom + rootPadBottom,
                    de ? de.offsetHeight : 0,
                    b ? b.offsetHeight : 0
                );
                // Report device px so the parent can use it as WebView.layoutParams.height directly.
                return Math.ceil(cssH * (window.devicePixelRatio || 1));
            }
            var last = -1;
            function report() {
                var h = currentHeight();
                if (h > 0 && h !== last) {
                    last = h;
                    // Pass this page's load token so the parent can reject stale reports from a
                    // previous chapter still settling in a recycled WebView (see ChapterWebView).
                    window.RiffleChapter.onHeightMeasured(h, (window.__riffleToken | 0));
                }
            }
            report();
            if (window.ResizeObserver) {
                var ro = new ResizeObserver(function() { report(); });
                ro.observe(document.documentElement);
                if (document.body) ro.observe(document.body);
            }
            if (document.fonts && document.fonts.ready) {
                document.fonts.ready.then(function() { requestAnimationFrame(report); });
            }
            setTimeout(report, 300);
            setTimeout(report, 1000);
        })();
    """.trimIndent()

    /**
     * JS that forwards single taps on the document body to `window.RiffleChapter.onTap` so the
     * Continuous-mode reader chrome (top/bottom bars) can toggle on tap, matching the standard
     * Readium navigator's [InputListener.onTap] behaviour. The listener is idempotent.
     */
    val TAP_LISTENER_JS = """
        (function() {
            if (document.__riffleTapWired) return;
            document.__riffleTapWired = true;
            document.addEventListener('click', function(e) {
                // Only a tap on the background toggles the reader chrome. A tap on a link (footnote,
                // cross-reference, external) or other interactive control must NOT also toggle the
                // bars — otherwise following an internal link in Continuous mode flips out of
                // immersive mode at the same time (the link's own handler navigates separately).
                var t = e.target;
                while (t && t.nodeType === 1) {
                    var tag = t.tagName ? t.tagName.toLowerCase() : '';
                    if (tag === 'a' || tag === 'button' || tag === 'input' ||
                        tag === 'select' || tag === 'textarea' || tag === 'label') return;
                    t = t.parentNode;
                }
                window.RiffleChapter.onTap();
            }, false);
        })();
    """.trimIndent()

    /**
     * JS that intercepts taps on same-document anchors (`<a href="#id">`) in the capture phase,
     * before the WebView performs its default in-page scroll, and asks the native side whether the
     * target is a footnote via `window.RiffleChapter.onFootnoteAnchorTap(id)`. When it is (returns
     * true) the default scroll is suppressed so the reader shows the footnote popup instead. In
     * Continuous mode the WebView's own scrolling is disabled, so without this a footnote tap would
     * do nothing at all. Mirrors the paged-mode [FootnoteAnchorBridge], but per-WebView (each stacked
     * chapter resolves against its own document) rather than via the single shared bridge.
     */
    val FOOTNOTE_LISTENER_JS = """
        (function() {
            if (document.__riffleFootnoteWired) return;
            document.__riffleFootnoteWired = true;
            document.addEventListener('click', function(e) {
                var t = e.target;
                while (t && t.nodeType === 1 && (!t.tagName || t.tagName.toLowerCase() !== 'a')) t = t.parentNode;
                if (!t || !t.tagName || t.tagName.toLowerCase() !== 'a') return;
                var href = t.getAttribute('href');
                if (!href || href.charAt(0) !== '#') return;
                var id = href.substring(1);
                if (!id) return;
                try {
                    if (window.RiffleChapter.onFootnoteAnchorTap(id)) {
                        e.preventDefault();
                        e.stopPropagation();
                    }
                } catch (err) {}
            }, true);
        })();
    """.trimIndent()
}
