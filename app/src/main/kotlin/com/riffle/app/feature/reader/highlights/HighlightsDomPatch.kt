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
    // Update the paragraph, adjacent aside, AND figure block sharing the same data-ann-id — the
    // aside's and figure's border-left uses the same accent colour by design. Setting
    // borderLeftColor is enough since the width/style are already inline (4px solid / 2px solid
    // respectively, set at build time). `closest('p, figure')` covers text highlights (span inside
    // <p>), embedded figures inside a highlight (span inside <figure class="riffle-fig">), and the
    // <figure> itself now that we tag it with data-ann-id (fix 2026-07-10).
    return """
        |(function(){
        |  var id = $idJs;
        |  var color = $colorJs;
        |  var nodes = document.querySelectorAll('[data-ann-id="' + id + '"]');
        |  for (var i = 0; i < nodes.length; i++) {
        |    var el = nodes[i];
        |    var host = el.tagName === 'ASIDE' || el.tagName === 'FIGURE'
        |      ? el : el.closest('p, figure');
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
    // adjacency-only check misses the pair.
    //
    // For a text-figure-text (or more generally multi-chunk) annotation the aside must sit AFTER
    // the LAST DOM node that belongs to this annotation, not after the FIRST paragraph — otherwise
    // the note visually splits the annotation body (bug 2026-07-10: "adding a note to a grouped
    // annotation shows the note under the first text"). Find every element with this data-ann-id
    // and anchor the insertion to the last one's parent block (its <p> or <figure> host).
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
        |    aside.setAttribute('style',
        |      'border-left: 2px solid ' + color + ' !important; padding-left: 12px; ' +
        |      'font-style: italic; opacity: 0.75;');
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
        |  var figs = document.querySelectorAll('figure.riffle-fig[data-ann-id="' + id + '"]');
        |  for (var i = 0; i < figs.length; i++) figs[i].remove();
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
