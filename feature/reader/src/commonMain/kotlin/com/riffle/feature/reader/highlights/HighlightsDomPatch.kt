package com.riffle.feature.reader.highlights

import com.riffle.core.models.EmphasisStyle

/**
 * Live per-annotation DOM patch applied to the currently loaded Highlights-mode chapter
 * (ADR 0048). Each patch translates ONE annotation-store change into ONE targeted
 * `document.querySelector('[data-ann-id="…"]')` mutation on the reader WebView, so the elided
 * reader reflects colour / note / deletion / emphasis edits WITHOUT a visible reload flash.
 *
 * The [applyJs] string returned by each subtype is safe to run in any chapter: if the target
 * element isn't in the currently loaded DOM the `querySelector(...)` no-ops silently.
 */
sealed class HighlightsDomPatch {

    /** JavaScript to evaluate on the WebView. Idempotent by design. */
    abstract fun applyJs(): String

    data class Recolor(
        val annotationId: String,
        val accentCssRgba: String,
        val barWidthPx: String = "4px",
    ) : HighlightsDomPatch() {
        override fun applyJs(): String = buildRecolorJs(annotationId, accentCssRgba, barWidthPx)
    }

    data class SetNote(
        val annotationId: String,
        val accentCssRgba: String,
        val noteText: String?,
    ) : HighlightsDomPatch() {
        override fun applyJs(): String = buildSetNoteJs(annotationId, accentCssRgba, noteText)
    }

    data class Remove(val annotationId: String) : HighlightsDomPatch() {
        override fun applyJs(): String = buildRemoveJs(annotationId)
    }

    data class SetEmphasis(val annotationId: String, val emphasisCss: String) : HighlightsDomPatch() {
        override fun applyJs(): String = buildSetEmphasisJs(annotationId, emphasisCss)
    }
}

internal fun buildRecolorJs(annotationId: String, accentCssRgba: String, barWidthPx: String = "4px"): String {
    val idJs = jsQuoteString(annotationId)
    val colorJs = jsQuoteString(accentCssRgba)
    val widthJs = jsQuoteString(barWidthPx)
    return """
        |(function(){
        |  var id = $idJs;
        |  var color = $colorJs;
        |  var width = $widthJs;
        |  var nodes = document.querySelectorAll('[data-ann-id="' + id + '"]');
        |  for (var i = 0; i < nodes.length; i++) {
        |    var el = nodes[i];
        |    var host = el.tagName === 'ASIDE' || el.tagName === 'FIGURE'
        |      ? el : el.closest('p, figure');
        |    if (host) {
        |      host.style.setProperty('border-left-color', color, 'important');
        |      if (host.tagName !== 'ASIDE') host.style.setProperty('border-left-width', width, 'important');
        |    }
        |  }
        |})();
    """.trimMargin()
}

internal fun buildSetNoteJs(annotationId: String, accentCssRgba: String, noteText: String?): String {
    val idJs = jsQuoteString(annotationId)
    val colorJs = jsQuoteString(accentCssRgba)
    val noteJs = if (noteText == null) "null" else jsQuoteString(noteText)
    return """
        |(function(){
        |  var id = $idJs;
        |  var color = $colorJs;
        |  var note = $noteJs;
        |  var aside = document.querySelector('aside.riffle-note[data-ann-id="' + id + '"]');
        |  if (note === null) {
        |    if (aside) aside.remove();
        |    return;
        |  }
        |  if (!aside) {
        |    var nodes = document.querySelectorAll('[data-ann-id="' + id + '"]');
        |    var anchorHost = null;
        |    for (var i = nodes.length - 1; i >= 0; i--) {
        |      var el = nodes[i];
        |      if (el.tagName === 'ASIDE') continue;
        |      anchorHost = el.tagName === 'FIGURE' ? el : el.closest('p, figure');
        |      if (anchorHost) break;
        |    }
        |    if (!anchorHost) return;
        |    aside = document.createElement('aside');
        |    aside.setAttribute('class', 'riffle-note');
        |    aside.setAttribute('data-ann-id', id);
        |    aside.setAttribute('role', 'note');
        |    aside.setAttribute('aria-label', '$ELIDED_NOTE_ARIA_LABEL');
        |    aside.setAttribute('style',
        |      'border-left: 2px solid ' + color + ' !important;');
        |    anchorHost.parentNode.insertBefore(aside, anchorHost.nextSibling);
        |  } else {
        |    aside.style.setProperty('border-left-color', color, 'important');
        |  }
        |  aside.textContent = note;
        |})();
    """.trimMargin()
}

internal fun buildRemoveJs(annotationId: String): String {
    val idJs = jsQuoteString(annotationId)
    return """
        |(function(){
        |  var id = $idJs;
        |  var aside = document.querySelector('aside.riffle-note[data-ann-id="' + id + '"]');
        |  if (aside) aside.remove();
        |  var spans = document.querySelectorAll('span.riffle-hl[data-ann-id="' + id + '"]');
        |  var seen = [];
        |  for (var i = 0; i < spans.length; i++) {
        |    var p = spans[i].closest('p');
        |    if (p && seen.indexOf(p) === -1) { seen.push(p); p.remove(); }
        |  }
        |  var figs = document.querySelectorAll('figure.riffle-fig[data-ann-id="' + id + '"]');
        |  for (var j = 0; j < figs.length; j++) figs[j].remove();
        |})();
    """.trimMargin()
}

internal fun buildSetEmphasisJs(annotationId: String, emphasisCss: String): String {
    val idJs = jsQuoteString(annotationId)
    val cssJs = jsQuoteString(emphasisCss)
    return """
        |(function(){
        |  var id = $idJs;
        |  var css = $cssJs;
        |  var spans = document.querySelectorAll('span.riffle-hl[data-ann-id="' + id + '"]');
        |  for (var i = 0; i < spans.length; i++) { spans[i].style.cssText = css; }
        |})();
    """.trimMargin()
}

fun buildEmphasisInlineCss(styles: Set<EmphasisStyle>): String {
    if (styles.isEmpty()) return ""
    val sb = StringBuilder()
    if (EmphasisStyle.BOLD in styles) sb.append("font-weight:bold !important;")
    if (EmphasisStyle.ITALIC in styles) sb.append("font-style:italic !important;")
    val decos = buildList {
        if (EmphasisStyle.UNDERLINE in styles) add("underline")
        if (EmphasisStyle.STRIKE in styles) add("line-through")
    }
    if (decos.isNotEmpty()) sb.append("text-decoration:${decos.joinToString(" ")} !important;")
    return sb.toString()
}

/** Minimal safe JS string literal. */
internal fun jsQuoteString(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
    return "\"$escaped\""
}

// Constants referenced by both DomPatch and ElidedChapterHtmlBuilder.
const val ELIDED_NOTE_LABEL = "Note"
const val ELIDED_NOTE_ARIA_LABEL = "Note on this highlight"
