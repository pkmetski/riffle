package com.riffle.feature.reader

import com.riffle.core.models.EmphasisStyle
import com.riffle.feature.reader.decorations.HIGHLIGHT_INLINE_STYLE_SUFFIX
import com.riffle.feature.reader.decorations.HIGHLIGHT_LEADING_ADJUSTMENT_HELPER_JS

/**
 * Flat, primitive-only representation of a single annotation for a continuous-mode chapter.
 *
 * This class is intentionally free of Kotlin generics so it can be constructed directly from
 * Swift via Kotlin/Native without dealing with [KotlinSet] bridging. The [emphasisTokens] field
 * carries the comma-separated [EmphasisStyle.token] values (e.g. `"bold,italic"` or `""`).
 *
 * Swift callers build a list of these and pass it to
 * [ContinuousAnnotationJs.applyAnnotationsFromInputs].
 */
data class ContinuousAnnotationInput(
    val id: String,
    val text: String,
    val cssColor: String,
    val hasNote: Boolean = false,
    val before: String = "",
    val after: String = "",
    val suppressMarkClick: Boolean = false,
    val emphasisTokens: String = "",
)

/** Minimum distance (CSS px) from the left viewport edge for the note-glyph icon. */
const val NOTE_GLYPH_VIEWPORT_INSET_PX = 12

/** SVG path for the NoteAlt icon (Apache 2.0), percent-encoded for embedding in a CSS url(). */
const val NOTE_GLYPH_SVG_DATA_URI =
    "data:image/svg+xml," +
    "%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E" +
    "%3Cpath d='M22,10l-6,-6H4C2.9,4,2,4.9,2,6v12c0,1.1,0.9,2,2,2h16c1.1,0,2,-0.9,2,-2V10Z" +
    "M16,4l4,4h-4V4ZM13,18H7v-2h6V18ZM17,14H7v-2h10V14ZM17,10H7V8h10V10Z'/%3E" +
    "%3C/svg%3E"

/**
 * JavaScript helpers for continuous-mode annotation, search, and readaloud highlights.
 *
 * All functions in this object produce JS strings to be evaluated in a continuous-mode chapter
 * WebView. They are platform-agnostic: Android evaluates them via WebView.evaluateJavascript;
 * iOS evaluates them via WKWebView.evaluateJavaScript.
 */
object ContinuousAnnotationJs {

    /** Removes the readaloud/focus highlight mark (id="_riffle_hl"). */
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

    /** Removes all annotation marks ([data-riffle-ann]) and note glyphs ([data-riffle-note-glyph]). */
    val CLEAR_ANNOTATION_HIGHLIGHTS_JS = """
        (function() {
            document.querySelectorAll('[data-riffle-note-glyph]').forEach(function(s) { s.remove(); });
            document.querySelectorAll('[data-riffle-ann]').forEach(function(m) { m.outerHTML = m.innerHTML; });
        })();
    """.trimIndent()

    private const val SAFE_WRAP_HELPER_JS = """
        if (!window.__riffleSafeWrap) window.__riffleSafeWrap = function(range, mark) {
            var sc = range.startContainer, ec = range.endContainer;
            function blockOf(n) {
                while (n && n.nodeType === 1) {
                    var d = window.getComputedStyle(n).display;
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

    /**
     * JS that marks all occurrences of [inactiveTexts] with `data-riffle-si` and promotes the
     * occurrence closest to [activeProgression] to `data-riffle-sa`.
     *
     * Call only after [CLEAR_SEARCH_HIGHLIGHTS_JS] has been evaluated, so the DOM is clean before
     * window.find traverses it.
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
                append("'${text.escapeForJs()}'")
            }
            append(']')
        }
        val activeTextJs = if (activeText != null) "'${activeText.escapeForJs()}'" else "null"
        return """
            $SAFE_WRAP_HELPER_JS
            $HIGHLIGHT_LEADING_ADJUSTMENT_HELPER_JS
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
                    var savedScrollY = window.pageYOffset || 0;
                    var limit = 500;
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
                            var skipR = document.createRange();
                            skipR.setStart(range.endContainer, range.endOffset); skipR.collapse(true);
                            sel.removeAllRanges(); sel.addRange(skipR);
                            continue;
                        }
                        window.__riffleFillInlineHighlightLeading(mark);
                        var advance = document.createRange();
                        advance.setStartAfter(mark); advance.collapse(true);
                        sel.removeAllRanges(); sel.addRange(advance);
                    }
                    window.scrollTo(0, savedScrollY);
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
                        window.__riffleFillInlineHighlightLeading(best);
                    }
                }
                if (sel) sel.removeAllRanges();
            })($textsJson,'$inactiveCssColor',$activeTextJs,${activeProgression},'$activeCssColor');
        """.trimIndent()
    }

    /**
     * JS that applies [annotations] as `<mark>` elements with smart DOM diffing: existing marks are
     * updated in-place (colour only), marks for deleted annotations are removed, and only
     * newly-added annotations go through occurrence-locating.
     */
    fun applyAnnotationHighlightsJs(annotations: List<AnnotationHighlight>): String {
        if (annotations.isEmpty()) return CLEAR_ANNOTATION_HIGHLIGHTS_JS
        val json = buildString {
            append('[')
            annotations.forEachIndexed { i, ann ->
                if (i > 0) append(',')
                val emphasisTokens = EmphasisStyle.encode(ann.emphasisStyles).orEmpty()
                append(
                    "{id:'${ann.id.escapeForJs()}',t:'${ann.text.escapeForJs()}'," +
                        "b:'${ann.before.escapeForJs()}',a:'${ann.after.escapeForJs()}'," +
                        "c:'${ann.cssColor}',n:${if (ann.hasNote) 1 else 0}," +
                        "s:${if (ann.suppressMarkClick) 1 else 0}," +
                        "e:'$emphasisTokens'}"
                )
            }
            append(']')
        }
        val safeSvgUri = NOTE_GLYPH_SVG_DATA_URI.replace("'", "\\'")
        return """
            $SAFE_WRAP_HELPER_JS
            $HIGHLIGHT_LEADING_ADJUSTMENT_HELPER_JS
            (function(anns) {
                var SVG_URI = '$safeSvgUri';
                var emphasisStyle = function(e) {
                    if (!e) return '';
                    var s = '';
                    if (e.indexOf('${EmphasisStyle.BOLD.token}') !== -1) s += 'font-weight:bold !important;';
                    if (e.indexOf('${EmphasisStyle.ITALIC.token}') !== -1) s += 'font-style:italic !important;';
                    var deco = '';
                    if (e.indexOf('${EmphasisStyle.UNDERLINE.token}') !== -1) deco += ' underline';
                    if (e.indexOf('${EmphasisStyle.STRIKE.token}') !== -1) deco += ' line-through';
                    if (deco.length > 0) {
                        s += 'text-decoration:' + deco.substring(1) + ' !important;';
                    }
                    return s;
                };
                var makeGlyph = function(id, anchorEl) {
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
                    var relLeft = Math.max(
                        markRect.left - blockRect.left - 28,
                        $NOTE_GLYPH_VIEWPORT_INSET_PX - blockRect.left
                    );
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
                    requestAnimationFrame(function() {
                        var br2 = blockEl.getBoundingClientRect();
                        var sr = s.getBoundingClientRect();
                        if (sr.left < $NOTE_GLYPH_VIEWPORT_INSET_PX) {
                            s.style.left = Math.max(0, $NOTE_GLYPH_VIEWPORT_INSET_PX - br2.left) + 'px';
                        }
                    });
                };
                var flatIdx = null;
                var buildFlat = function() {
                    var fullText = '';
                    var nodes = [];
                    if (!document.body) return { fullText: '', nodes: [] };
                    var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
                    var n;
                    while (n = w.nextNode()) {
                        if (n.parentNode && n.parentNode.closest && n.parentNode.closest('[data-riffle-ann]')) continue;
                        nodes.push({ node: n, start: fullText.length, len: n.nodeValue.length });
                        fullText += n.nodeValue;
                    }
                    return { fullText: fullText, nodes: nodes };
                };
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
                    var existingAll = document.querySelectorAll('[data-riffle-ann="' + ann.id + '"]');
                    if (existingAll.length > 0) {
                        existingAll.forEach(function(m) {
                            m.style.cssText = 'background:' + ann.c + '$HIGHLIGHT_INLINE_STYLE_SUFFIX' + emphasisStyle(ann.e);
                            window.__riffleFillInlineHighlightLeading(m);
                        });
                        var eg = document.querySelector('[data-riffle-note-glyph="' + ann.id + '"]');
                        if (ann.n && !eg && !ann.s) {
                            makeGlyph(ann.id, existingAll[0]);
                        } else if ((!ann.n || ann.s) && eg) {
                            eg.remove();
                        }
                        return;
                    }
                    var ranges = locateRanges(ann);
                    if (ranges.length === 0) return;
                    if (sel) sel.removeAllRanges();
                    var firstMark = null;
                    ranges.forEach(function(range) {
                        var mark = document.createElement('mark');
                        mark.setAttribute('data-riffle-ann', ann.id);
                        mark.style.cssText = 'background:' + ann.c + '$HIGHLIGHT_INLINE_STYLE_SUFFIX' + emphasisStyle(ann.e);
                        if (!window.__riffleSafeWrap(range, mark)) return;
                        window.__riffleFillInlineHighlightLeading(mark);
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
                    if (firstMark && ann.n && !ann.s) makeGlyph(ann.id, firstMark);
                });
                if (sel) sel.removeAllRanges();
            })($json);
        """.trimIndent()
    }

    /**
     * Highlights the DOM element with [fragmentId] by id — preferred for Cadence highlights
     * where the id is chapter-unique. Replaces any existing `_riffle_hl` mark.
     */
    fun highlightIdJs(fragmentId: String, cssColor: String): String {
        if (fragmentId.isBlank()) return CLEAR_HIGHLIGHT_JS
        val safe = fragmentId.escapeForJs()
        return """
            $SAFE_WRAP_HELPER_JS
            $HIGHLIGHT_LEADING_ADJUSTMENT_HELPER_JS
            (function() {
                var existing = document.getElementById('_riffle_hl');
                if (existing && existing.parentNode) existing.outerHTML = existing.innerHTML;
                var el = document.getElementById('$safe');
                if (!el) return;
                var range = document.createRange();
                try { range.selectNodeContents(el); } catch (e) { return; }
                var mark = document.createElement('mark');
                mark.id = '_riffle_hl';
                mark.style.cssText = 'background:$cssColor$HIGHLIGHT_INLINE_STYLE_SUFFIX';
                if (!window.__riffleSafeWrap(range, mark)) return;
                window.__riffleFillInlineHighlightLeading(mark);
                var sel = window.getSelection ? window.getSelection() : null;
                if (sel) sel.removeAllRanges();
            })();
        """.trimIndent()
    }

    /**
     * Highlights [text] via `window.find` + `<mark>` injection, replacing any existing `_riffle_hl`.
     * Uses a sentinel trick to resume find from the previous sentence's end rather than document start.
     */
    fun highlightTextJs(text: String, cssColor: String): String {
        if (text.isBlank()) return CLEAR_HIGHLIGHT_JS
        val safe = text.escapeForJs()
        return """
            $SAFE_WRAP_HELPER_JS
            $HIGHLIGHT_LEADING_ADJUSTMENT_HELPER_JS
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
                var savedScrollY = window.pageYOffset || 0;
                var found = window.find('$safe', false, false, false, false, false, false);
                window.scrollTo(0, savedScrollY);
                if (!found) return;
                sel = window.getSelection();
                if (!sel || sel.rangeCount === 0) return;
                var range = sel.getRangeAt(0);
                var mark = document.createElement('mark');
                mark.id = '_riffle_hl';
                mark.style.cssText = 'background:$cssColor$HIGHLIGHT_INLINE_STYLE_SUFFIX';
                if (!window.__riffleSafeWrap(range, mark)) { sel.removeAllRanges(); return; }
                window.__riffleFillInlineHighlightLeading(mark);
                sel.removeAllRanges();
            })();
        """.trimIndent()
    }

    /**
     * Builds annotation-highlight JS from [ContinuousAnnotationInput] objects — the Swift-friendly
     * alternative to [applyAnnotationHighlightsJs]. Converts each input to an [AnnotationHighlight]
     * (decoding [ContinuousAnnotationInput.emphasisTokens] back to [EmphasisStyle] values) and
     * delegates to the main implementation. Empty list produces [CLEAR_ANNOTATION_HIGHLIGHTS_JS].
     */
    fun applyAnnotationsFromInputs(annotations: List<ContinuousAnnotationInput>): String {
        if (annotations.isEmpty()) return CLEAR_ANNOTATION_HIGHLIGHTS_JS
        val highlights = annotations.map { a ->
            AnnotationHighlight(
                id = a.id,
                text = a.text,
                cssColor = a.cssColor,
                hasNote = a.hasNote,
                before = a.before,
                after = a.after,
                suppressMarkClick = a.suppressMarkClick,
                emphasisStyles = EmphasisStyle.decode(a.emphasisTokens) ?: emptySet(),
            )
        }
        return applyAnnotationHighlightsJs(highlights)
    }

    private fun String.escapeForJs(): String =
        replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
}
