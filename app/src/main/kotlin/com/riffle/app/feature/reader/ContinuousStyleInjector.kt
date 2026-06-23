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


    val CLEAR_HIGHLIGHT_JS = """
        (function() {
            var m = document.getElementById('_riffle_hl');
            if (m) m.outerHTML = m.innerHTML;
        })();
    """.trimIndent()

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
                    while (limit-- > 0) {
                        if (!window.find(text, false, false, false, false, false, false)) break;
                        sel = window.getSelection();
                        if (!sel || sel.rangeCount === 0) break;
                        var range = sel.getRangeAt(0);
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
                        mark.style.cssText = 'background:' + inactiveCss + ';color:inherit;';
                        try { range.surroundContents(mark); }
                        catch(e) { var frag = range.extractContents(); mark.appendChild(frag); range.insertNode(mark); }
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
                        best.style.background = activeCss;
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
                append("{id:'$safeId',t:'$safeText',b:'$safeBefore',a:'$safeAfter',c:'${ann.cssColor}',n:${if (ann.hasNote) 1 else 0}}")
            }
            append(']')
        }
        // Single quotes inside the SVG percent-encoded URI must be escaped for JS string embedding.
        val safeSvgUri = NOTE_GLYPH_SVG_DATA_URI.replace("'", "\\'")
        return """
            (function(anns) {
                var SVG_URI = '$safeSvgUri';
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
                var locateRange = function(ann) {
                    if (!flatIdx) flatIdx = buildFlat();
                    var text = flatIdx.fullText;
                    var snippet = ann.t;
                    if (!snippet) return null;
                    // Pick the occurrence whose surrounding text matches both ann.b and ann.a
                    // exactly (capture used Range.toString(), render uses the same TreeWalker
                    // representation, so the strings match byte-for-byte in normal flow). If no
                    // occurrence matches exactly (e.g. earlier-batch mark removed text the b/a
                    // referenced, or document edited between capture and render), fall through
                    // to the first occurrence — same behaviour as a legacy empty-context
                    // annotation, and never worse than the historical first-match default.
                    var matchedIdx = -1, firstIdx = -1;
                    var idx = -1;
                    while ((idx = text.indexOf(snippet, idx + 1)) !== -1) {
                        if (firstIdx < 0) firstIdx = idx;
                        if (!ann.b && !ann.a) break;
                        var beforeWin = ann.b ? text.substring(Math.max(0, idx - ann.b.length), idx) : '';
                        var afterWin = ann.a ? text.substring(idx + snippet.length, idx + snippet.length + ann.a.length) : '';
                        var beforeOk = !ann.b || beforeWin === ann.b;
                        var afterOk = !ann.a || afterWin === ann.a;
                        if (beforeOk && afterOk) { matchedIdx = idx; break; }
                    }
                    var bestIdx = matchedIdx >= 0 ? matchedIdx : firstIdx;
                    if (bestIdx < 0) return null;
                    var startIdx = bestIdx, endIdx = bestIdx + snippet.length;
                    var n0 = null, off0 = 0, n1 = null, off1 = 0;
                    for (var k = 0; k < flatIdx.nodes.length; k++) {
                        var nd = flatIdx.nodes[k];
                        if (n0 === null && startIdx < nd.start + nd.len) { n0 = nd.node; off0 = startIdx - nd.start; }
                        if (n1 === null && endIdx <= nd.start + nd.len) { n1 = nd.node; off1 = endIdx - nd.start; }
                        if (n0 && n1) break;
                    }
                    if (!n0 || !n1) return null;
                    var range = document.createRange();
                    try {
                        range.setStart(n0, off0);
                        range.setEnd(n1, off1);
                    } catch (e) { return null; }
                    return range;
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
                    var existing = document.querySelector('[data-riffle-ann="' + ann.id + '"]');
                    if (existing) {
                        // Update colour in-place — no relocation needed, no DOM structure change.
                        existing.style.background = ann.c;
                        var eg = document.querySelector('[data-riffle-note-glyph="' + ann.id + '"]');
                        if (ann.n && !eg) {
                            makeGlyph(ann.id, existing);
                        } else if (!ann.n && eg) {
                            eg.remove();
                        }
                        return;
                    }
                    // New annotation — locate the correct occurrence using before/after context.
                    var range = locateRange(ann);
                    if (!range) return;
                    if (sel) sel.removeAllRanges();
                    var mark = document.createElement('mark');
                    mark.setAttribute('data-riffle-ann', ann.id);
                    mark.style.cssText = 'background:' + ann.c + ';color:inherit;';
                    try {
                        range.surroundContents(mark);
                    } catch(e) {
                        var frag = range.extractContents();
                        mark.appendChild(frag);
                        range.insertNode(mark);
                    }
                    // The mark just split a text node — invalidate the flat index so the next
                    // locateRange() rebuilds it against the updated DOM.
                    flatIdx = null;
                    (function(markEl, annId) {
                        markEl.addEventListener('click', function(e) {
                            e.stopPropagation();
                            var r = markEl.getBoundingClientRect();
                            window.RiffleChapter.onAnnotationTap(annId, r.left, r.top, r.right, r.bottom);
                        });
                    })(mark, ann.id);
                    if (ann.n) makeGlyph(ann.id, mark);
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
    fun highlightTextJs(text: String, cssColor: String): String {
        if (text.isBlank()) return CLEAR_HIGHLIGHT_JS
        val safe = text.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r")
        return """
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
                mark.style.cssText = 'background:$cssColor;color:inherit;';
                try {
                    range.surroundContents(mark);
                } catch(e) {
                    // Range crosses an inline element boundary — extract contents into mark instead.
                    var frag = range.extractContents();
                    mark.appendChild(frag);
                    range.insertNode(mark);
                }
                sel.removeAllRanges();
            })();
        """.trimIndent()
    }
}
