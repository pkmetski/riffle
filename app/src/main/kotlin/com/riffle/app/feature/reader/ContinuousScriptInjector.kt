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
 *  3. [SAME_DOC_ANCHOR_LISTENER_JS] — intercepts same-document anchor clicks before WebView's
 *     default in-page scroll and routes them through `window.RiffleChapter.onFootnoteAnchorTap`
 *     (footnote popup) or `window.RiffleChapter.onCrossReferenceTap` (figure / heading nav).
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
            // Exposed so the parent can ask for a re-measure (e.g. at the load event after a
            // DOM-ready measurement) without re-running this installer.
            window.__riffleReport = report;
            if (window.__riffleDomReadyMeasure) {
                // DOM-ready measurement: images have not decoded yet. ReadiumCSS sizes images
                // with `width: auto; height: auto`, which discards the <img width/height>
                // presentational size, so an undecoded image occupies the 300 px default box
                // and the chapter grows as each image lands (measured +4% on a 16-image
                // chapter — re-landing the viewport on every step). Reserve each sized,
                // not-yet-complete image's box at its expected final size (attribute width
                // clamped to its container, attribute aspect ratio) and drop the inline size
                // once it loads, so the final layout is exactly the author's.
                try {
                    var imgs = document.images;
                    for (var k = 0; k < imgs.length; k++) {
                        var im = imgs[k];
                        if (im.complete) continue;
                        var aw = parseInt(im.getAttribute('width'), 10);
                        var ah = parseInt(im.getAttribute('height'), 10);
                        if (!(aw > 0 && ah > 0)) continue;
                        if (im.style.width || im.style.height) continue;
                        // Width: the attribute width in CSS px, which the author's / ReadiumCSS
                        // max-width rules then clamp exactly as they will clamp the decoded
                        // image's intrinsic width. Height: derived from the RESOLVED width and
                        // the attribute aspect ratio, unrounded, so it matches the final
                        // `height: auto` value to the layout's own sub-pixel snapping.
                        im.style.width = aw + 'px';
                        var rw = im.getBoundingClientRect().width;
                        if (!(rw > 0)) rw = aw;
                        im.style.height = (rw * ah / aw) + 'px';
                        var release = function(e) {
                            var t = e.target;
                            t.style.width = '';
                            t.style.height = '';
                        };
                        im.addEventListener('load', release, { once: true });
                        im.addEventListener('error', release, { once: true });
                    }
                } catch (e) {}
            }
            // Force a layout before consulting document.fonts: at DOMContentLoaded no layout has
            // run yet, so no @font-face load has been requested and fonts.status still reads
            // 'loaded' — the wait below would be skipped and the first report taken with
            // fallback metrics.
            void (document.documentElement && document.documentElement.offsetHeight);
            if (window.__riffleDomReadyMeasure && document.fonts && document.fonts.ready &&
                document.fonts.status !== 'loaded') {
                // DOM-ready measurement: the bundled @font-face files are still loading, and a
                // height taken with fallback metrics would be corrected a few frames later —
                // a visible jump on an already-revealed page. Fonts come from local assets,
                // so this wait is short; the ResizeObserver below still covers later reflow.
                document.fonts.ready.then(function() { requestAnimationFrame(report); });
            } else {
                report();
            }
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
            // Snapshot selection state at touchstart so a tap-to-dismiss doesn't toggle immersive.
            // Reading the selection inside the click handler is racy: on Chromium the WebView
            // clears the selection on touchend before dispatching click, so the check would find
            // no selection and toggle. Snapshotting at touchstart is race-free.
            document.addEventListener('touchstart', function() {
                var s = window.getSelection();
                document.__riffleHadSelAtDown =
                    !!(s && s.rangeCount > 0 && !s.isCollapsed);
            }, true);
            document.addEventListener('click', function(e) {
                // Consume-once: snapshot and clear the had-selection flag at the START of the click,
                // regardless of what the target is. Clearing at the interactive-element early-return
                // (below) alone would leak a stale `true` when a synthetic click reaches the listener
                // without a preceding touchstart; consuming here guarantees the flag never survives
                // past the click that follows the touchstart which set it.
                var hadSel = document.__riffleHadSelAtDown;
                document.__riffleHadSelAtDown = false;
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
                // If a selection was live at touchstart, this tap only dismisses the selection popup.
                if (hadSel) return;
                window.RiffleChapter.onTap();
            }, false);
        })();
    """.trimIndent()

    /**
     * JS that intercepts taps on same-document anchors (`<a href="#id">`) in the capture phase,
     * before the WebView performs its default in-page scroll, and routes them into native so
     * Continuous mode owns the navigation:
     *  - `RiffleChapter.onFootnoteAnchorTap(id)` — footnote-style targets show the popup.
     *  - `RiffleChapter.onCrossReferenceTap(id)` — everything else (figures, section headings,
     *    other cross-references) triggers a native scroll of the parent viewport plus a
     *    return-to-position card.
     *
     * The default in-page scroll is ALWAYS suppressed: allowing it to run in Continuous mode
     * moves the child WebView's own `scrollY` (its content is taller than its measured viewport),
     * shifting the visible band inside the chunk while the parent's stacked-chapter geometry
     * assumes each WebView shows its content at scrollY=0. That desync makes it look like the
     * outer scroll can't reach past the shifted band. Mirrors the paged-mode [FootnoteAnchorBridge]
     * for both branches, but per-WebView (each stacked chapter resolves against its own document)
     * rather than via the single shared bridge.
     */
    val SAME_DOC_ANCHOR_LISTENER_JS = """
        (function() {
            if (document.__riffleSameDocAnchorWired) return;
            document.__riffleSameDocAnchorWired = true;
            document.addEventListener('click', function(e) {
                var t = e.target;
                while (t && t.nodeType === 1 && (!t.tagName || t.tagName.toLowerCase() !== 'a')) t = t.parentNode;
                if (!t || !t.tagName || t.tagName.toLowerCase() !== 'a') return;
                var href = t.getAttribute('href');
                if (!href) return;
                // Same-doc classification. Two accepted shapes:
                //  1. bare '#id' — cheapest to detect, skip URL parsing on the hot path.
                //  2. path-prefixed same-chapter reference ('part0007.xhtml#a2C8' clicked from
                //     part0007.xhtml — a common EPUB convention for cross-references). Resolve
                //     against document.location and compare pathname; without this the guard used
                //     to skip these and the WebView's default fragment scroll ran, desyncing
                //     child scrollY from the parent's stacked-chapter geometry AND giving no
                //     hook to show the return card.
                // Truly cross-resource links (a different chapter's pathname) fall through and
                // are handled by shouldOverrideUrlLoading -> ChapterWebView.onInternalLink.
                var id;
                if (href.charAt(0) === '#') {
                    id = href.substring(1);
                } else {
                    var resolved;
                    try { resolved = new URL(href, document.location.href); }
                    catch (e) { return; }
                    var sameDoc = resolved.origin === document.location.origin &&
                        resolved.pathname === document.location.pathname;
                    if (!sameDoc) return;
                    id = resolved.hash ? resolved.hash.substring(1) : '';
                    // resolved.hash preserves percent-encoding (e.g. '#figure%201'); decode so
                    // native's document.getElementById() matches the raw id in the DOM
                    // ('figure 1'). getAttribute-driven bare '#id' hrefs are already raw.
                    try { id = decodeURIComponent(id); } catch (e) {}
                }
                if (!id) return;
                try {
                    if (window.RiffleChapter.onFootnoteAnchorTap(id)) {
                        e.preventDefault();
                        e.stopPropagation();
                        return;
                    }
                    // Not a footnote — treat as a cross-reference (figure, heading, etc.). Native
                    // scrolls the outer viewport to the target and captures a return anchor. We
                    // always preventDefault so the WebView's own in-page scroll never runs; see
                    // this file's KDoc for why that scroll breaks parent scroll continuity.
                    window.RiffleChapter.onCrossReferenceTap(id);
                    e.preventDefault();
                    e.stopPropagation();
                } catch (err) {}
            }, true);
        })();
    """.trimIndent()
}
