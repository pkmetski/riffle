package com.riffle.app.feature.reader

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide singleton carrying the inline-formatted excerpt HTML for the most recent live
 * selection (issue: elided view drops italics) from a reader WebView into
 * [EpubReaderViewModel.createHighlight]. The JS extractor in
 * [ReaderWebViewScripts.SELECTION_SPAN_TRACKER_JS] walks `Range.cloneContents()` and emits ONLY
 * a fixed allowlist of inline elements (`<em>`/`<i>`/`<strong>`/`<b>`/`<sup>`/`<sub>`/`<u>`/`<s>`)
 * with text nodes XML-escaped; the render side re-enforces the same allowlist via
 * `sanitizeInlineSnippetHtml` as defence-in-depth. Empty string means the extractor found no
 * inline formatting on top of the plaintext, and the elided view falls back to the plain
 * `textSnippet` render path.
 *
 * Mirrors [SelectionFontStash] and [SelectionFiguresStash] — only one reader session is active
 * at a time so a singleton is safe. Contents cleared by the ViewModel after consumption.
 */
internal object SelectionSnippetHtmlStash {
    private val current = AtomicReference<String>("")

    fun set(html: String) {
        current.set(html)
    }

    fun consume(): String = current.getAndSet("")
}
