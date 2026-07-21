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

    // Regex patterns used in injectInto() — hoisted so they are compiled once rather than on
    // every chapter load (shouldInterceptRequest is on the WebCore hot path).
    private val REGEX_STYLE_TAG = Regex("<style", RegexOption.IGNORE_CASE)
    private val REGEX_HEAD_OPEN = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)
    private val REGEX_HTML_OPEN = Regex("<html[^>]*", RegexOption.IGNORE_CASE)

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

        // Font family: Original maps to null (no override → publisher font, exactly like
        // Readium's null mapping). Other families set --USER__fontFamily + the readium-font-on
        // flag; the generic "Serif" resolves to the CSS `serif` system font.
        val fontStack = fontFamilyStack(prefs.fontFamily)
        if (fontStack != null) {
            props["--USER__fontOverride"] = "readium-font-on"
            props["--USER__fontFamily"] = fontStack.joinToString(", ") { "\"$it\"" }
        }

        // Font size as a percentage (Length.Percent → value*100, max 2 fraction digits).
        props["--USER__fontSize"] = percentCss(prefs.fontSize)

        // Advanced settings: Continuous always runs with publisherStyles off (advanced on).
        props["--USER__advancedSettings"] = "readium-advanced-on"

        // Text alignment: set only when justify is on. When off, omit --USER__textAlign entirely
        // so the publisher's own text-align is preserved — matching FormattingPreferencesMapper's
        // contract (null when justifyText=false) and the Scroll/Paginated mode behaviour where the
        // variable is left unset and the publisher's alignment shows through.
        if (prefs.justifyText) {
            props["--USER__textAlign"] = "justify"
        }

        // Line height only when the user moved it off the default (mirrors the mapper's null-gating).
        if (prefs.lineSpacing != FormattingPreferences.DEFAULT_LINE_SPACING) {
            props["--USER__lineHeight"] = prefs.lineSpacing.toDouble().toString()
        }

        return props.entries.joinToString(" ") { "${it.key}: ${it.value} !important;" }
    }

    /**
     * The font stack for [family], or null when no override should be emitted (Original →
     * publisher font). Mirrors Readium's `resolveFontStack`: our app registers Literata/
     * Merriweather/OpenDyslexic with no alternates, so each resolves to a single-entry stack
     * — identical to what Scroll mode produces. Generic "Serif" resolves to CSS `serif`.
     */
    private fun fontFamilyStack(family: ReaderFontFamily): List<String>? = when (family) {
        ReaderFontFamily.Original -> null
        ReaderFontFamily.Serif -> listOf("serif")
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
            REGEX_STYLE_TAG.containsMatchIn(html)
        val headOpen = REGEX_HEAD_OPEN.find(out)
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

        // 2. After-CSS + bundled @font-face + typography overrides before </head>.
        // The typography override CSS must come after ReadiumCSS-after.css because it needs to
        // beat after.css's `text-align: inherit !important` rule (specificity 0,2,4) for justify.
        // It uses the same gate selectors as the paginated-mode typographyOverrideInjectionJs()
        // path, so toggling justify via buildStyleInjectionJs() (which updates --USER__textAlign
        // on <html>) automatically activates/deactivates the override without reloading the page.
        val afterBlock = buildString {
            append("\n<link rel=\"stylesheet\" type=\"text/css\" href=\"$CSS_BASE/ReadiumCSS-after.css\"/>\n")
            append("<style type=\"text/css\">\n")
            append(bundledFontFaces())
            append("</style>\n")
            append("<style type=\"text/css\" id=\"riffle-typography-override\">\n")
            append(TYPOGRAPHY_OVERRIDE_CSS)
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
        val htmlTag = REGEX_HTML_OPEN.find(out)
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
    // Force Chromium to invalidate the composited raster tiles for this WebView after every live
    // preference change. WebSettingsCompat.setOffscreenPreRaster(true) (#413) keeps rasterised
    // tiles alive for off-screen chapters so the chapter-boundary blank-flash doesn't return; the
    // side-effect is that a bare `:root` style-attribute mutation — how every live theme /
    // typography change is applied — doesn't reliably invalidate those cached tiles. The CSSOM
    // updates, ReadiumCSS re-cascades, `getComputedStyle` returns the new colours, but the
    // composited image on screen still shows the pre-change theme: users see the OLD background
    // survive a Sepia → Dim switch, with the text vanishing entirely because the stale sepia
    // raster is now displaying dark-mode text against sepia bg with contrast the tile was never
    // rasterised for.
    //
    // Toggling `visibility: hidden` on `:root`, then restoring on the next animation frame, is a
    // paint-invalidating change that overrides the pre-raster tile cache. Restoration is
    // unconditional (always `''`, never a captured "previous value") so overlapping fast switches
    // can't leave the document stuck hidden — the earlier iteration captured the current visibility
    // as the previous, which under rapid switches was a mid-flight `hidden` from an in-progress
    // toggle, and RAF then restored to hidden and stuck-hid the WebView. A `setTimeout` belt-and-
    // suspenders guards against `requestAnimationFrame` being throttled or paused (Chromium pauses
    // RAF for backgrounded WebViews; off-screen stacked WebViews can hit that state).
    fun buildStyleInjectionJs(prefs: FormattingPreferences): String {
        val styleAttr = buildHtmlStyleAttr(prefs)
            .replace("\\", "\\\\")
            .replace("'", "\\'")
        return """
            (function() {
                document.documentElement.setAttribute('style', '$styleAttr');
                var de = document.documentElement;
                de.style.visibility = 'hidden';
                var restore = function() { de.style.visibility = ''; };
                requestAnimationFrame(restore);
                setTimeout(restore, 100);
            })();
        """.trimIndent()
    }


    val CLEAR_HIGHLIGHT_JS = """
        (function() {
            var m = document.getElementById('_riffle_hl');
            if (m) m.outerHTML = m.innerHTML;
        })();
    """.trimIndent()

    /**
     * Inline JS that wraps [range] in [mark] iff the range stays within a single block element.
     * Defines `window.__riffleSafeWrap` (idempotent). Use as a `try { surroundContents } catch` /
     * `extractContents()+insertNode()` replacement so cross-block ranges can never reparent block
     * elements as inline children of `<mark>` (which historically destroyed paragraphs whenever
     * `surroundContents()` threw on a multi-paragraph annotation selection). Returns true when the
     * mark was inserted, false when the range was rejected as cross-block.
     */
    private const val SAFE_WRAP_HELPER_JS = """
        if (!window.__riffleSafeWrap) window.__riffleSafeWrap = function(range, mark) {
            var sc = range.startContainer, ec = range.endContainer;
            function blockOf(n) {
                while (n && n.nodeType === 1) {
                    var d = window.getComputedStyle(n).display;
                    // Treat every block-like display value as a "block" for cross-block detection.
                    // Missing any one of these would let a range that spans e.g. two table rows
                    // (table-row / table-row-group) pass the check, after which extractContents
                    // would reparent the rows as inline children of <mark>. inline-block /
                    // inline-flex are intentionally excluded — they flow inline so wrapping one
                    // inside <mark> is safe.
                    if (d === 'block' || d === 'list-item' || d === 'flex' || d === 'grid' || d === 'flow-root' ||
                        d === 'table' || d === 'table-row' || d === 'table-row-group' ||
                        d === 'table-header-group' || d === 'table-footer-group' || d === 'table-cell' ||
                        d === 'table-caption') return n;
                    n = n.parentNode;
                }
                return n;
            }
            var sb = blockOf(sc.nodeType === 3 ? sc.parentNode : sc);
            var eb = blockOf(ec.nodeType === 3 ? ec.parentNode : ec);
            if (!sb || sb !== eb) return false;
            try {
                range.surroundContents(mark);
            } catch (e) {
                var frag = range.extractContents();
                mark.appendChild(frag);
                range.insertNode(mark);
            }
            return true;
        };
    """

    /** Removes all search inactive ([data-riffle-si]) and active ([data-riffle-sa]) marks. */
    val CLEAR_SEARCH_HIGHLIGHTS_JS = """
        (function() {
            document.querySelectorAll('[data-riffle-si],[data-riffle-sa]').forEach(function(m) {
                m.outerHTML = m.innerHTML;
            });
        })();
    """.trimIndent()

    /**
     * JS that marks all occurrences of each text in [inactiveTexts] with `data-riffle-si` and
     * [inactiveCssColor], then promotes the occurrence of [activeText] closest to [activeProgression]
     * (a 0–1 document fraction) to `data-riffle-sa` with [activeCssColor].
     *
     * Call this ONLY after clearing existing marks via [CLEAR_SEARCH_HIGHLIGHTS_JS] (via a
     * [WebView.evaluateJavascript] callback), because Chrome won't reliably find text in a DOM
     * that was just mutated synchronously in the same JS block.
     *
     * Pass [activeText] as null (and [activeProgression] < 0) when this chapter has no active result.
     */
    fun applySearchHighlightsJs(
        inactiveTexts: List<String>,
        inactiveCssColor: String,
        activeText: String?,
        activeProgression: Float,
        activeCssColor: String,
    ): String {
        val textsJson = buildString {
            append('[')
            inactiveTexts.forEachIndexed { i, text ->
                if (i > 0) append(',')
                val safe = text
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                append("'$safe'")
            }
            append(']')
        }
        val safeActive = activeText
            ?.replace("\\", "\\\\")?.replace("'", "\\'")?.replace("\n", "\\n")?.replace("\r", "\\r")
        val activeTextJs = if (safeActive != null) "'$safeActive'" else "null"
        // Colors are machine-generated CSS rgba() strings — no quote escaping needed.
        return """
            $SAFE_WRAP_HELPER_JS
            (function(texts,inactiveCss,activeT,activeProg,activeCss) {
                var sel = window.getSelection();
                var seen = {};
                texts.forEach(function(text) {
                    if (!text || seen[text]) return;
                    seen[text] = true;
                    try {
                        var r0 = document.createRange();
                        r0.setStart(document.body || document.documentElement, 0);
                        r0.collapse(true);
                        if (sel) { sel.removeAllRanges(); sel.addRange(r0); }
                    } catch(e) { return; }
                    var limit = 500;
                    // Track the previous match's flat-text position. window.find resumes from the
                    // current selection, but if the skip-advance somehow leaves the selection on the
                    // same hit (an extremely defensive guard for browser quirks), break out of the
                    // loop rather than burning the iteration budget on the same match.
                    var prevKey = null;
                    while (limit-- > 0) {
                        if (!window.find(text, false, false, false, false, false, false)) break;
                        sel = window.getSelection();
                        if (!sel || sel.rangeCount === 0) break;
                        var range = sel.getRangeAt(0);
                        var matchKey = (range.startContainer === document ? '' : range.startContainer.textContent && range.startContainer.textContent.length) + ':' + range.startOffset;
                        if (matchKey === prevKey) break;
                        prevKey = matchKey;
                        var cont = range.commonAncestorContainer;
                        if (cont.nodeType !== 1) cont = cont.parentNode;
                        if (cont && cont.hasAttribute &&
                            (cont.hasAttribute('data-riffle-si') || cont.hasAttribute('data-riffle-sa'))) {
                            var skip = document.createRange();
                            skip.setStartAfter(cont); skip.collapse(true);
                            sel.removeAllRanges(); sel.addRange(skip);
                            continue;
                        }
                        var mark = document.createElement('mark');
                        mark.setAttribute('data-riffle-si', '');
                        mark.style.cssText = 'background:' + inactiveCss + '$HIGHLIGHT_INLINE_STYLE_SUFFIX';
                        if (!window.__riffleSafeWrap(range, mark)) {
                            // Cross-block match — skip past the END of this hit (a collapsed
                            // selection at range.end* makes window.find resume strictly after the
                            // current hit). Combined with the matchKey guard above, this guarantees
                            // forward progress even if window.find's resume semantics misbehave.
                            var skipR = document.createRange();
                            skipR.setStart(range.endContainer, range.endOffset); skipR.collapse(true);
                            sel.removeAllRanges(); sel.addRange(skipR);
                            continue;
                        }
                        var advance = document.createRange();
                        advance.setStartAfter(mark); advance.collapse(true);
                        sel.removeAllRanges(); sel.addRange(advance);
                    }
                });
                if (activeT && activeProg >= 0) {
                    var docH = Math.max(
                        document.documentElement.scrollHeight,
                        document.body ? document.body.scrollHeight : 0, 1
                    );
                    var targetY = activeProg * docH;
                    var best = null, bestDist = Infinity;
                    document.querySelectorAll('[data-riffle-si]').forEach(function(m) {
                        if (m.textContent.toLowerCase().indexOf(activeT.toLowerCase()) < 0) return;
                        var rect = m.getBoundingClientRect();
                        var absY = rect.top + (window.pageYOffset || document.documentElement.scrollTop || 0);
                        var dist = Math.abs(absY - targetY);
                        if (dist < bestDist) { bestDist = dist; best = m; }
                    });
                    if (best) {
                        best.setAttribute('data-riffle-sa', '');
                        best.removeAttribute('data-riffle-si');
                        best.style.cssText = 'background:' + activeCss + '$HIGHLIGHT_INLINE_STYLE_SUFFIX';
                    }
                }
                if (sel) sel.removeAllRanges();
            })($textsJson,'$inactiveCssColor',$activeTextJs,${activeProgression},'$activeCssColor');
        """.trimIndent()
    }

    val CLEAR_ANNOTATION_HIGHLIGHTS_JS = """
        (function() {
            document.querySelectorAll('[data-riffle-note-glyph]').forEach(function(s) { s.remove(); });
            document.querySelectorAll('[data-riffle-ann]').forEach(function(m) { m.outerHTML = m.innerHTML; });
        })();
    """.trimIndent()

    /**
     * JS that applies [annotations] as `<mark>` elements (keyed by `data-riffle-ann`) and optional
     * note-glyph `<span>` elements (keyed by `data-riffle-note-glyph`).
     *
     * Smart DOM diffing avoids the clear-and-reapply pattern: existing marks are updated in-place
     * (colour only, no DOM structure change), marks for deleted annotations are removed, and only
     * newly-added annotations go through occurrence-locating. This prevents the race where clearing
     * existing marks via `outerHTML = innerHTML` modifies the DOM just before locating runs — a new
     * highlight appears immediately without requiring a page reload.
     *
     * Occurrence resolution: when the highlighted text repeats in the chapter, `window.find()`
     * would always pick the FIRST occurrence. Instead we walk text nodes, build a flat document
     * string + node/offset index, and pick the occurrence of `ann.t` whose surrounding context
     * matches the stored `ann.b` (before) / `ann.a` (after). Legacy annotations created before
     * context capture have empty b/a and fall back to first-match (existing behaviour preserved).
     *
     * For annotations with notes a small ◆ glyph span is injected after the mark. Tapping it calls
     * `window.RiffleChapter.onAnnotationNoteTap` so the host can open the note reader.
     */
    fun applyAnnotationHighlightsJs(annotations: List<AnnotationHighlight>): String {
        val json = buildString {
            append('[')
            annotations.forEachIndexed { i, ann ->
                if (i > 0) append(',')
                val safeId = ann.id.replace("\\", "\\\\").replace("'", "\\'")
                val safeText = ann.text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
                val safeBefore = ann.before.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
                val safeAfter = ann.after.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
                // cssColor is machine-generated (e.g. "rgba(56,189,248,0.50)") — no quote escaping needed.
                // s: suppressMarkClick — the emitted `<mark>` gets NO click listener when 1. Owned
                // by the accent-bar span in the synthesised HTML in that case (Highlights mode).
                // e: ADR 0046 emphasis tokens, comma-separated (empty string when no emphasis).
                // Machine-generated by [EmphasisStyle.encode] from a fixed enum vocabulary — no
                // quote escaping needed.
                val emphasisTokens = com.riffle.core.models.EmphasisStyle.encode(ann.emphasisStyles).orEmpty()
                append(
                    "{id:'$safeId',t:'$safeText',b:'$safeBefore',a:'$safeAfter'," +
                        "c:'${ann.cssColor}',n:${if (ann.hasNote) 1 else 0}," +
                        "s:${if (ann.suppressMarkClick) 1 else 0}," +
                        "e:'$emphasisTokens'}"
                )
            }
            append(']')
        }
        // Single quotes inside the SVG percent-encoded URI must be escaped for JS string embedding.
        val safeSvgUri = NOTE_GLYPH_SVG_DATA_URI.replace("'", "\\'")
        return """
            $SAFE_WRAP_HELPER_JS
            (function(anns) {
                var SVG_URI = '$safeSvgUri';
                // ADR 0046: build the extra inline CSS the emphasis set contributes to a mark's
                // style attribute. Kept as a suffix so the base 'background:... !important;
                // color:inherit;' declaration (pinned by regression tests) stays intact — a mark
                // with no emphasis produces the empty string. All declarations use `!important`
                // so publisher `<em>`/`<strong>` styles inside the range don't undo the toggle
                // (mirrors the paginated DOM-injector rule).
                var emphasisStyle = function(e) {
                    if (!e) return '';
                    var s = '';
                    if (e.indexOf('bold') !== -1) s += 'font-weight:bold !important;';
                    if (e.indexOf('italic') !== -1) s += 'font-style:italic !important;';
                    var deco = '';
                    if (e.indexOf('underline') !== -1) deco += ' underline';
                    if (e.indexOf('strike') !== -1) deco += ' line-through';
                    if (deco.length > 0) {
                        s += 'text-decoration:' + deco.substring(1) + ' !important;';
                    }
                    return s;
                };
                // makeGlyph: positions the NoteAlt icon 28px to the left of anchorEl's first line,
                // matching the paged-mode NoteGlyphStyle margin position.
                var makeGlyph = function(id, anchorEl) {
                    // Walk up to the nearest block ancestor so we can position within its coordinate space.
                    var blockEl = document.body || document.documentElement;
                    var p = anchorEl.parentNode;
                    while (p && p.nodeType === 1 && p !== document.documentElement) {
                        var d = window.getComputedStyle(p).display;
                        if (d === 'block' || d === 'list-item') { blockEl = p; break; }
                        p = p.parentNode;
                    }
                    if (window.getComputedStyle(blockEl).position === 'static') {
                        blockEl.style.position = 'relative';
                    }
                    var markRect = anchorEl.getBoundingClientRect();
                    var blockRect = blockEl.getBoundingClientRect();
                    var relTop = Math.max(0, markRect.top - blockRect.top + 2);
                    // Mirror paged-mode NoteGlyphStyle: position 28px to the LEFT of the mark's
                    // first-line left edge (not the block's left edge, which is the page margin).
                    var relLeft = markRect.left - blockRect.left - 28;
                    var s = document.createElement('span');
                    s.setAttribute('data-riffle-note-glyph', id);
                    s.style.cssText = 'position:absolute;left:' + relLeft + 'px;top:' + relTop + 'px;' +
                        'width:28px;height:28px;cursor:pointer;opacity:0.40;' +
                        '-webkit-mask-image:url("' + SVG_URI + '");' +
                        '-webkit-mask-size:contain;-webkit-mask-repeat:no-repeat;' +
                        'background-color:currentColor;' +
                        '-webkit-user-select:none;user-select:none;';
                    s.addEventListener('click', function(e) {
                        e.stopPropagation();
                        var r = s.getBoundingClientRect();
                        window.RiffleChapter.onAnnotationNoteTap(id, r.left, r.top, r.right, r.bottom);
                    });
                    blockEl.appendChild(s);
                };
                // Build a flat text index of the document (text nodes outside existing marks),
                // used to resolve the right occurrence when an annotation's text repeats. Built
                // lazily (only when we actually need to locate a new annotation).
                var flatIdx = null;
                var buildFlat = function() {
                    var fullText = '';
                    var nodes = [];
                    // #428 guard: chapter injections can fire before document.body is populated
                    // (e.g. right after a sandboxed WebView restart); createTreeWalker throws
                    // "parameter 1 is not of type 'Node'" and silently wedges highlight
                    // rendering for the rest of the session.
                    if (!document.body) return { fullText: '', nodes: [] };
                    var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                    var n;
                    while (n = w.nextNode()) {
                        // Skip text inside existing annotation marks so the b/a context (which was
                        // captured against the unmarked DOM at selection time) keeps matching.
                        if (n.parentNode && n.parentNode.closest && n.parentNode.closest('[data-riffle-ann]')) continue;
                        nodes.push({ node: n, start: fullText.length, len: n.nodeValue.length });
                        fullText += n.nodeValue;
                    }
                    return { fullText: fullText, nodes: nodes };
                };
                // Find `snippet` in flatIdx.fullText at or after `searchFrom`. If `before`/`after`
                // are non-empty, prefer the occurrence whose neighbouring text matches them; fall
                // through to the first occurrence at/after searchFrom otherwise.
                var findIdx = function(snippet, before, after, searchFrom) {
                    var text = flatIdx.fullText;
                    var firstIdx = -1, idx = searchFrom - 1;
                    while ((idx = text.indexOf(snippet, idx + 1)) !== -1) {
                        if (firstIdx < 0) firstIdx = idx;
                        if (!before && !after) return idx;
                        var beforeWin = before ? text.substring(Math.max(0, idx - before.length), idx) : '';
                        var afterWin = after ? text.substring(idx + snippet.length, idx + snippet.length + after.length) : '';
                        if ((!before || beforeWin === before) && (!after || afterWin === after)) return idx;
                    }
                    return firstIdx;
                };
                // Build a Range from a flat-text offset/length using flatIdx.nodes.
                var idxToRange = function(startIdx, length) {
                    var endIdx = startIdx + length;
                    var n0 = null, off0 = 0, n1 = null, off1 = 0;
                    for (var k = 0; k < flatIdx.nodes.length; k++) {
                        var nd = flatIdx.nodes[k];
                        if (n0 === null && startIdx < nd.start + nd.len) { n0 = nd.node; off0 = startIdx - nd.start; }
                        if (n1 === null && endIdx <= nd.start + nd.len) { n1 = nd.node; off1 = endIdx - nd.start; }
                        if (n0 && n1) break;
                    }
                    if (!n0 || !n1) return null;
                    var range = document.createRange();
                    try { range.setStart(n0, off0); range.setEnd(n1, off1); }
                    catch (e) { return null; }
                    return range;
                };
                // Resolve one annotation to a list of ranges — one range per block the snippet
                // crosses. A `Selection.toString()` snippet glues block content with "\n" / "\n\n";
                // the flat-text index has NO separators between blocks, so a multi-paragraph
                // snippet would never match as a single string. Split on `\n+` and locate each
                // chunk sequentially (anchored to after the previous chunk so we don't capture an
                // earlier duplicate). The first chunk uses ann.b for disambiguation, the last uses
                // ann.a; middle chunks rely on the sequential anchor.
                var locateRanges = function(ann) {
                    if (!flatIdx) flatIdx = buildFlat();
                    if (!ann.t) return [];
                    var chunks = ann.t.split(/\n+/).map(function(s) { return s.trim(); }).filter(function(s) { return s.length > 0; });
                    if (chunks.length === 0) return [];
                    var ranges = [];
                    var searchFrom = 0;
                    for (var i = 0; i < chunks.length; i++) {
                        var before = (i === 0) ? ann.b : '';
                        var after = (i === chunks.length - 1) ? ann.a : '';
                        var hit = findIdx(chunks[i], before, after, searchFrom);
                        if (hit < 0) continue;
                        var r = idxToRange(hit, chunks[i].length);
                        if (r) ranges.push(r);
                        searchFrom = hit + chunks[i].length;
                    }
                    return ranges;
                };
                // Remove marks/glyphs for annotations no longer in the list.
                var validIds = {};
                anns.forEach(function(a) { validIds[a.id] = true; });
                document.querySelectorAll('[data-riffle-ann]').forEach(function(m) {
                    if (!validIds[m.getAttribute('data-riffle-ann')]) {
                        var g = document.querySelector('[data-riffle-note-glyph="' + m.getAttribute('data-riffle-ann') + '"]');
                        if (g) g.remove();
                        m.outerHTML = m.innerHTML;
                    }
                });
                var sel = window.getSelection();
                anns.forEach(function(ann) {
                    // Multi-paragraph annotations can have several marks sharing the same id — one
                    // per block. Update colour in-place across all of them and skip relocation.
                    var existingAll = document.querySelectorAll('[data-riffle-ann="' + ann.id + '"]');
                    if (existingAll.length > 0) {
                        existingAll.forEach(function(m) { m.style.cssText = 'background:' + ann.c + '$HIGHLIGHT_INLINE_STYLE_SUFFIX' + emphasisStyle(ann.e); });
                        var eg = document.querySelector('[data-riffle-note-glyph="' + ann.id + '"]');
                        // Highlights mode (ann.s === 1) suppresses the note glyph: the glyph would
                        // overlap the accent-bar tap span in the left gutter and swallow taps that
                        // should open the highlight-actions popup. The note is already visible as
                        // an <aside> beneath the highlight in the synthesised HTML.
                        if (ann.n && !eg && !ann.s) {
                            makeGlyph(ann.id, existingAll[0]);
                        } else if ((!ann.n || ann.s) && eg) {
                            eg.remove();
                        }
                        return;
                    }
                    // New annotation — locate the correct occurrence(s). One range per block the
                    // snippet crosses. __riffleSafeWrap rejects any cross-block range (the data
                    // model produces single-block ranges by construction, but the guard prevents
                    // the historical extractContents+insertNode catastrophe if anything slips).
                    var ranges = locateRanges(ann);
                    if (ranges.length === 0) return;
                    if (sel) sel.removeAllRanges();
                    var firstMark = null;
                    ranges.forEach(function(range) {
                        var mark = document.createElement('mark');
                        mark.setAttribute('data-riffle-ann', ann.id);
                        mark.style.cssText = 'background:' + ann.c + '$HIGHLIGHT_INLINE_STYLE_SUFFIX' + emphasisStyle(ann.e);
                        if (!window.__riffleSafeWrap(range, mark)) return;
                        // The mark just split a text node — invalidate the flat index so the NEXT
                        // annotation's locateRanges() rebuilds against the updated DOM. The other
                        // ranges in THIS annotation already hold their own text-node refs (captured
                        // pre-wrap by idxToRange), and they live in different blocks, so the wrap
                        // here doesn't disturb their boundaries.
                        flatIdx = null;
                        if (!ann.s) {
                            (function(markEl, annId) {
                                markEl.addEventListener('click', function(e) {
                                    e.stopPropagation();
                                    var r = markEl.getBoundingClientRect();
                                    window.RiffleChapter.onAnnotationTap(annId, r.left, r.top, r.right, r.bottom);
                                });
                            })(mark, ann.id);
                        }
                        if (!firstMark) firstMark = mark;
                    });
                    // Highlights mode suppresses the glyph (see the in-place branch above).
                    if (firstMark && ann.n && !ann.s) makeGlyph(ann.id, firstMark);
                });
                if (sel) sel.removeAllRanges();
            })($json);
        """.trimIndent()
    }

    /**
     * JS that highlights [text] via `window.find` + DOM `<mark>` injection, replacing
     * any existing mark with id `_riffle_hl`. Pass an empty string to clear.
     * Single quotes and backslashes in [text] are escaped before embedding in JS.
     *
     * `surroundContents()` throws when the selection range crosses an inline element boundary
     * (e.g. `<em>`, `<span class="smallcaps">`). The fallback uses `extractContents()` +
     * `insertNode()` which handles multi-node ranges correctly.
     *
     * The sentinel trick: before removing the old mark we insert a temporary empty `<span>`
     * immediately after it. `outerHTML = innerHTML` removes the mark but leaves the sentinel
     * in place. We then collapse the selection to right after the sentinel and remove it.
     * This makes `window.find()` search forward from the end of the previous sentence rather
     * than from the document start (the unpredictable position the DOM mutation leaves the
     * selection at), so consecutive sentences that share a repeated word/phrase always resolve
     * to the next occurrence in reading order instead of the first one in the document.
     */
    /**
     * Highlight the DOM element with [fragmentId] directly, no text search. Preferred for
     * Cadence (whose `cd-N` ids are chapter-unique and known at paint time) — using
     * [highlightTextJs] instead makes `window.find` land on the FIRST occurrence of the
     * sentence text anywhere in the document, which for short/common phrases (or heading
     * text that recurs verbatim in the body) is not the span we want. The "highlight starts
     * from midscreen and not the closest chapter/subchapter" regression was exactly that:
     * resolver picked cd-N inside the visible heading, paint via `window.find(heading text)`
     * matched an earlier body occurrence, and the mark landed one paragraph away.
     *
     * Replaces any existing `_riffle_hl` mark. Silently no-ops if the id isn't in the DOM.
     */
    fun highlightIdJs(fragmentId: String, cssColor: String): String {
        if (fragmentId.isBlank()) return CLEAR_HIGHLIGHT_JS
        val safe = fragmentId.replace("\\", "\\\\").replace("'", "\\'")
        return """
            $SAFE_WRAP_HELPER_JS
            (function() {
                var existing = document.getElementById('_riffle_hl');
                if (existing && existing.parentNode) existing.outerHTML = existing.innerHTML;
                var el = document.getElementById('$safe');
                if (!el) return;
                var range = document.createRange();
                try { range.selectNodeContents(el); } catch (e) { return; }
                var mark = document.createElement('mark');
                mark.id = '_riffle_hl';
                mark.style.cssText = '${highlightInlineStyle("$cssColor")}';
                if (!window.__riffleSafeWrap(range, mark)) return;
                var sel = window.getSelection ? window.getSelection() : null;
                if (sel) sel.removeAllRanges();
            })();
        """.trimIndent()
    }

    fun highlightTextJs(text: String, cssColor: String): String {
        if (text.isBlank()) return CLEAR_HIGHLIGHT_JS
        val safe = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
        return """
            $SAFE_WRAP_HELPER_JS
            (function() {
                var existing = document.getElementById('_riffle_hl');
                var sentinel = null;
                if (existing && existing.parentNode) {
                    sentinel = document.createElement('span');
                    existing.parentNode.insertBefore(sentinel, existing.nextSibling);
                    existing.outerHTML = existing.innerHTML;
                }
                var sel = window.getSelection ? window.getSelection() : null;
                if (sel) sel.removeAllRanges();
                if (sentinel && sentinel.parentNode) {
                    try {
                        var r = document.createRange();
                        r.setStartAfter(sentinel);
                        r.collapse(true);
                        if (sel) sel.addRange(r);
                    } catch(e) {}
                    sentinel.parentNode.removeChild(sentinel);
                }
                if (!window.find('$safe', false, false, false, false, false, false)) return;
                sel = window.getSelection();
                if (!sel || sel.rangeCount === 0) return;
                var range = sel.getRangeAt(0);
                var mark = document.createElement('mark');
                mark.id = '_riffle_hl';
                mark.style.cssText = '${highlightInlineStyle("$cssColor")}';
                // Skip cross-block matches — extractContents would reparent block elements as
                // inline children of <mark>, breaking the surrounding paragraphs.
                if (!window.__riffleSafeWrap(range, mark)) { sel.removeAllRanges(); return; }
                sel.removeAllRanges();
            })();
        """.trimIndent()
    }
}
