package com.riffle.app.feature.reader

import androidx.compose.ui.graphics.toArgb
import com.riffle.core.domain.FormattingPreferences
import com.riffle.core.domain.ReaderFontFamily
import com.riffle.core.domain.ReaderTheme

/**
 * Produces the CSS injection for Continuous-mode chapters.
 *
 * Continuous mode renders each chapter in its own [ChapterWebView] from the raw EPUB HTML, so it
 * does NOT go through Readium's navigator. To keep Continuous mode pixel-identical to Scroll and
 * Paginated mode, we inject the *exact same ReadiumCSS* Readium itself injects:
 *
 *   1. `<link>` to `ReadiumCSS-before.css` at the start of `<head>` (+ a `ReadiumCSS-default.css`
 *      link when the chapter has no author styles, matching Readium's CSS06 ordering rule).
 *   2. `<link>` to `ReadiumCSS-after.css` before `</head>`, plus our bundled `@font-face` rules.
 *   3. A `style="--USER__…: … !important; …"` attribute on `<html>` carrying the user-settings
 *      CSS variables and the `readium-*-on` flags — computed exactly as Readium's
 *      `EpubSettings`/`UserProperties.toCss()` does for the same preferences.
 *
 * Because the actual styling is then performed by the very same ReadiumCSS stylesheets that Scroll
 * mode uses, heading sizes (type scale), font switching, line-height compensation, page margins,
 * justification and theme colours all match Scroll mode by construction — there is no hand-rolled
 * CSS left to drift. The CSS files and bundled fonts are served from Android assets by
 * [ChapterWebView.shouldInterceptRequest] under the `readium/readium-css/` and `readium/fonts/`
 * paths on the `readium_package` virtual host.
 */
internal object ContinuousStyleInjector {

    /** Virtual-host base for the bundled ReadiumCSS stylesheets (served from assets). */
    private const val CSS_BASE = "https://readium_package/readium/readium-css"

    /** Virtual-host base for the app's bundled font files (served from assets). */
    private const val FONT_BASE = "https://readium_package/readium/fonts"

    /** ReaderThemePalette.DARK_DIM_TEXT as a ReadiumCSS hex colour (matches FormattingPreferencesMapper). */
    private val DARK_DIM_TEXT_HEX: String = String.format("#%06X", 0xFFFFFF and DARK_DIM_TEXT.toArgb())

    /**
     * Builds the value of the `style` attribute injected onto `<html>` — the `--USER__*` CSS
     * variables plus `readium-*-on` flags. Mirrors Readium's `EpubSettings.update()` →
     * `UserProperties.toCss()` for our [FormattingPreferencesMapper] mapping, so Continuous mode
     * resolves identically to Scroll/Paginated mode.
     *
     * Continuous mode is always scrolled (`readium-scroll-on`) and always runs in advanced mode
     * (`readium-advanced-on`, i.e. publisherStyles=false) — the same as our Scroll-mode config.
     */
    fun buildHtmlStyleAttr(prefs: FormattingPreferences): String {
        val props = LinkedHashMap<String, String>()

        // View: always scrolled in Continuous mode.
        props["--USER__view"] = "readium-scroll-on"

        // Page margins (Readium passes the raw Double via putCss → Double.toString()).
        props["--USER__pageMargins"] = prefs.margins.toDouble().toString()

        // Appearance + DarkDim text colour (mirrors FormattingPreferencesMapper).
        when (prefs.theme) {
            ReaderTheme.Dark, ReaderTheme.DarkDim -> props["--USER__appearance"] = "readium-night-on"
            ReaderTheme.Sepia -> props["--USER__appearance"] = "readium-sepia-on"
            ReaderTheme.Light, ReaderTheme.Auto -> { /* no appearance flag */ }
        }
        if (prefs.theme == ReaderTheme.DarkDim) {
            props["--USER__textColor"] = DARK_DIM_TEXT_HEX
        }

        // Font family: Serif maps to null (no override → publisher/system font, exactly like
        // Readium). Other families set --USER__fontFamily + the readium-font-on flag.
        val fontStack = fontFamilyStack(prefs.fontFamily)
        if (fontStack != null) {
            props["--USER__fontOverride"] = "readium-font-on"
            props["--USER__fontFamily"] = fontStack.joinToString(", ") { "\"$it\"" }
        }

        // Font size as a percentage (Length.Percent → value*100, max 2 fraction digits).
        props["--USER__fontSize"] = percentCss(prefs.fontSize)

        // Advanced settings: Continuous always runs with publisherStyles off (advanced on).
        props["--USER__advancedSettings"] = "readium-advanced-on"

        // Text alignment mirrors FormattingPreferencesMapper: justify, else start.
        props["--USER__textAlign"] = if (prefs.justifyText) "justify" else "start"

        // Line height only when the user moved it off the default (mirrors the mapper's null-gating).
        if (prefs.lineSpacing != FormattingPreferences.DEFAULT_LINE_SPACING) {
            props["--USER__lineHeight"] = prefs.lineSpacing.toDouble().toString()
        }

        return props.entries.joinToString(" ") { "${it.key}: ${it.value} !important;" }
    }

    /**
     * The font stack for [family], or null when no override should be emitted (Serif → publisher
     * font). Mirrors Readium's `resolveFontStack`: our app registers Literata/Merriweather/
     * OpenDyslexic with no alternates, so each resolves to a single-entry stack — identical to
     * what Scroll mode produces.
     */
    private fun fontFamilyStack(family: ReaderFontFamily): List<String>? = when (family) {
        ReaderFontFamily.Serif -> null
        ReaderFontFamily.SansSerif -> listOf("sans-serif")
        ReaderFontFamily.Monospace -> listOf("monospace")
        ReaderFontFamily.Literata -> listOf("Literata")
        ReaderFontFamily.Merriweather -> listOf("Merriweather")
        ReaderFontFamily.OpenDyslexic -> listOf("OpenDyslexic")
    }

    /** Formats [value] (e.g. 1.0, 1.5) as a ReadiumCSS percentage ("100%", "150%"). */
    private fun percentCss(value: Float): String {
        val pct = value * 100.0
        val rounded = Math.round(pct * 100.0) / 100.0
        return if (rounded % 1.0 == 0.0) "${rounded.toInt()}%" else "$rounded%"
    }

    /**
     * `@font-face` declarations for the app's bundled fonts, served from assets at [FONT_BASE].
     * Injected unconditionally (like Readium's `fontsInjectableCss`) so the chosen
     * --USER__fontFamily can resolve whichever bundled font the user selects.
     */
    private fun bundledFontFaces(): String = buildString {
        append("@font-face{font-family:Literata;font-style:normal;font-weight:400;")
        append("src:url('$FONT_BASE/Literata-Regular.ttf') format('truetype');}\n")
        append("@font-face{font-family:Merriweather;font-style:normal;font-weight:400;")
        append("src:url('$FONT_BASE/Merriweather-Regular.ttf') format('truetype');}\n")
        append("@font-face{font-family:OpenDyslexic;font-style:normal;font-weight:400;")
        append("src:url('$FONT_BASE/OpenDyslexic-Regular.otf') format('opentype');}\n")
    }

    /**
     * Injects ReadiumCSS + the user-settings `style` attribute into a raw EPUB chapter [html],
     * returning the styled HTML. Mirrors Readium's `ReadiumCss.injectHtml`.
     */
    fun injectInto(html: String, prefs: FormattingPreferences): String {
        var out = html

        // 1. Before-CSS (+ default-CSS when the chapter has no author styles) at <head> start.
        val hasAuthorStyles = html.contains("<link ", ignoreCase = true) ||
            html.contains(" style=", ignoreCase = true) ||
            Regex("<style", RegexOption.IGNORE_CASE).containsMatchIn(html)
        val headOpen = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(out)
        val beforeBlock = buildString {
            append("\n<link rel=\"stylesheet\" type=\"text/css\" href=\"$CSS_BASE/ReadiumCSS-before.css\"/>\n")
            // Match Readium's overflow fix so scroll layout behaves identically.
            append("<style>:root[style], :root { overflow: visible !important; }")
            append(":root[style] > body, :root > body { overflow: visible !important; }</style>\n")
            if (!hasAuthorStyles) {
                append("<link rel=\"stylesheet\" type=\"text/css\" href=\"$CSS_BASE/ReadiumCSS-default.css\"/>\n")
            }
        }
        out = if (headOpen != null) {
            out.substring(0, headOpen.range.last + 1) + beforeBlock + out.substring(headOpen.range.last + 1)
        } else {
            beforeBlock + out
        }

        // 2. After-CSS + bundled @font-face before </head>.
        val afterBlock = buildString {
            append("\n<link rel=\"stylesheet\" type=\"text/css\" href=\"$CSS_BASE/ReadiumCSS-after.css\"/>\n")
            append("<style type=\"text/css\">\n")
            append(bundledFontFaces())
            append("</style>\n")
        }
        val headCloseIdx = out.indexOf("</head>", ignoreCase = true)
        out = if (headCloseIdx >= 0) {
            out.substring(0, headCloseIdx) + afterBlock + out.substring(headCloseIdx)
        } else {
            out + afterBlock
        }

        // 3. The --USER__ style attribute on <html>.
        val styleAttr = buildHtmlStyleAttr(prefs).replace("\"", "&quot;")
        val htmlTag = Regex("<html[^>]*", RegexOption.IGNORE_CASE).find(out)
        if (htmlTag != null) {
            val insertAt = htmlTag.range.last + 1
            out = out.substring(0, insertAt) + " style=\"$styleAttr\"" + out.substring(insertAt)
        }
        return out
    }

    /**
     * JS that re-applies the user-settings `style` attribute on `<html>` after a live preference
     * change (no reload), then re-measures. ReadiumCSS reads the `--USER__*` variables and
     * `readium-*-on` flags straight from this attribute via its `[style*=…]` selectors, so setting
     * the whole attribute string is enough to re-style without reloading the chapter.
     */
    fun buildStyleInjectionJs(prefs: FormattingPreferences): String {
        val styleAttr = buildHtmlStyleAttr(prefs)
            .replace("\\", "\\\\")
            .replace("'", "\\'")
        return """
            (function() {
                document.documentElement.setAttribute('style', '$styleAttr');
            })();
        """.trimIndent()
    }


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
                // padding ReadiumCSS adds in scroll mode. We deliberately do NOT use
                // documentElement.scrollHeight here: the root scrolling element's scrollHeight is
                // clamped to max(content, viewport) by spec, so a chapter shorter than the viewport
                // (e.g. a chapter-title divider page with only a heading) would measure a full
                // screen tall and insert a large blank gap after it in the continuous stack.
                // de.offsetHeight / b.offsetHeight are border-box heights and are NOT viewport-
                // clamped, so they (and the body-bottom measure) give the real height for both
                // short and tall chapters.
                var bodyBottom = b ? (b.getBoundingClientRect().bottom + (window.pageYOffset || 0)) : 0;
                var rootPadBottom = 0;
                if (de) {
                    var pb = parseFloat(getComputedStyle(de).paddingBottom);
                    if (pb === pb) rootPadBottom = pb; // NaN-guard without isNaN()
                }
                var cssH = Math.max(
                    bodyBottom + rootPadBottom,
                    de ? de.offsetHeight : 0,
                    b ? b.offsetHeight : 0,
                    b ? b.scrollHeight : 0
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

    val CLEAR_HIGHLIGHT_JS = """
        (function() {
            var m = document.getElementById('_riffle_hl');
            if (m) m.outerHTML = m.innerHTML;
        })();
    """.trimIndent()

    /**
     * JS that highlights [text] via `window.find` + DOM `<mark>` injection, replacing
     * any existing mark with id `_riffle_hl`. Pass an empty string to clear.
     * Single quotes and backslashes in [text] are escaped before embedding in JS.
     */
    fun highlightTextJs(text: String): String {
        if (text.isBlank()) return CLEAR_HIGHLIGHT_JS
        val safe = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
        return """
            (function() {
                var existing = document.getElementById('_riffle_hl');
                if (existing) { existing.outerHTML = existing.innerHTML; }
                if (!window.find('$safe', false, false, false, false, false, false)) return;
                var sel = window.getSelection();
                if (!sel || sel.rangeCount === 0) return;
                var range = sel.getRangeAt(0);
                var mark = document.createElement('mark');
                mark.id = '_riffle_hl';
                mark.style.cssText = 'background:#7DD3FC;color:inherit;';
                try { range.surroundContents(mark); } catch(e) {}
                sel.removeAllRanges();
            })();
        """.trimIndent()
    }
}
