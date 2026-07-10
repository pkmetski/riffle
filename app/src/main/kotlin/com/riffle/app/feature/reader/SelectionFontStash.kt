package com.riffle.app.feature.reader

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide singleton that carries the computed `font-family` at the most recent live text
 * selection's start element from a reader WebView to [EpubReaderViewModel.createHighlight] /
 * `toggleBookmark`. Populated by both selection readers (continuous [ChapterWebView] and
 * paginated action-mode in [EpubReaderScreen]) whenever they read a completed selection payload
 * (`ff` field of the JSON stashed by [ReaderWebViewScripts.SELECTION_SPAN_TRACKER_JS]).
 *
 * Mirrors [SelectionFiguresStash]'s shape — only one reader session is active at a time, so a
 * singleton is safe. Contents cleared by the ViewModel after consumption. Empty string means
 * the JS side had no value (e.g. no live selection at read time); callers fall back to the
 * publication's body font (issue #484 backfill path).
 */
internal object SelectionFontStash {
    private val current = AtomicReference<String>("")

    fun set(fontFamily: String) {
        current.set(fontFamily)
    }

    fun consume(): String = current.getAndSet("")
}
