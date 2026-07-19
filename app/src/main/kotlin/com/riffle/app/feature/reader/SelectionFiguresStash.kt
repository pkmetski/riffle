package com.riffle.app.feature.reader

import com.riffle.core.models.EmbeddedFigure
import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide singleton that carries the figures enclosed by the most recent live text selection
 * from a reader WebView to [EpubReaderViewModel.createHighlight]. Populated by
 * [ChapterWebView] whenever it reads a completed selection payload (see
 * [ReaderWebViewScripts.SELECTION_SPAN_TRACKER_JS] which walks the range for `<img>` / `<svg>` /
 * `<picture>` / `<figure>` nodes and rasterises each raster figure into a data URI while the
 * selection is still live).
 *
 * Only one reader session is active at a time, so a singleton is safe. Contents are cleared by
 * the ViewModel after they're consumed by a highlight-create call.
 */
internal object SelectionFiguresStash {
    private val current = AtomicReference<List<EmbeddedFigure>>(emptyList())

    fun set(figures: List<EmbeddedFigure>) {
        current.set(figures)
    }

    fun consume(): List<EmbeddedFigure> = current.getAndSet(emptyList())
}
