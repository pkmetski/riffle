package com.riffle.app.feature.reader.highlights

/**
 * Live per-annotation DOM patch applied to the currently loaded Highlights-mode chapter
 * (ADR 0041). Each patch translates ONE annotation-store change into ONE targeted
 * `document.querySelector('[data-ann-id="…"]')` mutation on the Readium WebView, so the elided
 * reader reflects colour / note / deletion / in-chapter-insert edits WITHOUT the visible reload
 * flash the earlier `reloadHighlightsView()` full rebuild produced.
 *
 * The `applyJs` string returned by each subtype is safe to run in any chapter: if the target
 * element isn't in the currently loaded DOM (because the highlight lives in a different chapter,
 * or Readium has already navigated away), the `querySelector(...)` no-ops silently. The paired
 * bytes-rewrite via [HighlightsPublicationHandle.setChapterBytes] guarantees that a subsequent
 * navigation back into the patched chapter still renders the fresh state — the DOM patch is a
 * fast in-place refresh for the visible page; the byte rewrite is the durable source of truth for
 * everything Readium will load next.
 */
sealed class HighlightsDomPatch {

    /** JavaScript to `evaluateJavascript` on the WebView. Idempotent by design. */
    abstract fun applyJs(): String

    /** Change the accent-bar palette colour on one highlight without re-rendering the paragraph. */
    data class Recolor(val annotationId: String, val accentCssRgba: String) : HighlightsDomPatch() {
        override fun applyJs(): String = buildRecolorJs(annotationId, accentCssRgba)
    }

    /**
     * Set (add / replace / clear) the inline `<aside class="riffle-note">` sibling next to the
     * highlight paragraph. [noteText] `null` removes the aside entirely; non-null creates it if
     * missing or replaces its text content if present. [accentCssRgba] is the current accent
     * colour (used for the aside's `border-left` — see the factory's baked style).
     */
    data class SetNote(
        val annotationId: String,
        val accentCssRgba: String,
        val noteText: String?,
    ) : HighlightsDomPatch() {
        override fun applyJs(): String = buildSetNoteJs(annotationId, accentCssRgba, noteText)
    }

    /** Remove the highlight `<p>` and any adjacent `<aside class="riffle-note">` for one annotation. */
    data class Remove(val annotationId: String) : HighlightsDomPatch() {
        override fun applyJs(): String = buildRemoveJs(annotationId)
    }
}

// ─── Rendering helpers — small enough to inline; kept package-private so tests can pin. ─────────

internal fun buildRecolorJs(annotationId: String, accentCssRgba: String): String {
    val idJs = jsQuoteString(annotationId)
    val colorJs = jsQuoteString(accentCssRgba)
    // Update both the paragraph and any adjacent aside sharing the same data-ann-id — the note's
    // border-left uses the same accent colour by design. Setting borderLeftColor is enough since
    // the width/style are already inline (4px solid / 2px solid respectively, set at build time).
    return """
        |(function(){
        |  var id = $idJs;
        |  var color = $colorJs;
        |  var nodes = document.querySelectorAll('[data-ann-id="' + id + '"]');
        |  for (var i = 0; i < nodes.length; i++) {
        |    var el = nodes[i];
        |    var host = el.tagName === 'ASIDE' ? el : el.closest('p');
        |    if (host) host.style.setProperty('border-left-color', color, 'important');
        |  }
        |})();
    """.trimMargin()
}

internal fun buildSetNoteJs(annotationId: String, accentCssRgba: String, noteText: String?): String {
    val idJs = jsQuoteString(annotationId)
    val colorJs = jsQuoteString(accentCssRgba)
    val noteJs = if (noteText == null) "null" else jsQuoteString(noteText)
    // Look up the aside by its own data-ann-id (not by sibling-adjacency to the paragraph);
    // Readium's HTMLInjector sometimes lands wrappers/text nodes between the two, so an
    // adjacency-only check misses the pair. When creating a new aside we still need the
    // paragraph to know WHERE to insert (right after it), so we resolve `p` via the highlight
    // span; insertion uses `p.nextSibling` which is safe even when p has no next element.
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
        |    var hlSpan = document.querySelector('span.riffle-hl[data-ann-id="' + id + '"]');
        |    if (!hlSpan) return;
        |    var p = hlSpan.closest('p');
        |    if (!p) return;
        |    aside = document.createElement('aside');
        |    aside.setAttribute('class', 'riffle-note');
        |    aside.setAttribute('data-ann-id', id);
        |    aside.setAttribute('style',
        |      'border-left: 2px solid ' + color + ' !important; padding-left: 12px; ' +
        |      'font-style: italic; opacity: 0.75;');
        |    p.parentNode.insertBefore(aside, p.nextSibling);
        |  } else {
        |    aside.style.setProperty('border-left-color', color, 'important');
        |  }
        |  aside.textContent = note;
        |})();
    """.trimMargin()
}

internal fun buildRemoveJs(annotationId: String): String {
    val idJs = jsQuoteString(annotationId)
    // Look the paragraph AND the note up independently by data-ann-id (both carry the same
    // ann-id — see the factory's renderChapterHtml). `nextElementSibling` was fragile through
    // Readium's HTMLInjector, which sometimes lands text nodes / injected wrappers between the
    // paragraph and the aside — so the aside would survive an `if (next && next.tagName ===
    // 'ASIDE' ...)` check even when it is the semantic pair. Independent lookups don't care
    // about sibling adjacency.
    return """
        |(function(){
        |  var id = $idJs;
        |  var aside = document.querySelector('aside.riffle-note[data-ann-id="' + id + '"]');
        |  if (aside) aside.remove();
        |  var hlSpan = document.querySelector('span.riffle-hl[data-ann-id="' + id + '"]');
        |  if (hlSpan) {
        |    var p = hlSpan.closest('p');
        |    if (p) p.remove();
        |  }
        |})();
    """.trimMargin()
}

/** Minimal safe JS string literal — escapes the four characters that can break out of `"..."`. */
private fun jsQuoteString(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
    return "\"$escaped\""
}
