package com.riffle.app.feature.reader

import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.ReadaloudHighlightColor
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.SentenceQuote
import org.readium.r2.shared.publication.Locator

/**
 * [HighlightRenderer] for continuous mode.
 *
 * Continuous mode does not use Readium's DecorableNavigator — instead it drives highlights
 * directly into each [ChapterWebView] via JS injection methods exposed through
 * [ContinuousHighlightTarget].
 *
 * [targetProvider] is called lazily on each render call so the renderer can be constructed
 * before the view exists, and so tests can inject a fake without Android dependencies.
 */
internal class ContinuousHighlightRenderer(
    private val targetProvider: () -> ContinuousHighlightTarget?,
) : HighlightRenderer {

    /** Href of the chapter that holds the current readaloud highlight, for clearing on chapter change. */
    private var prevReadaloudHref: String? = null

    override suspend fun applyReadaloud(
        fragmentRef: String?,
        quotes: Map<String, SentenceQuote>,
        color: ReadaloudHighlightColor,
    ) {
        val target = targetProvider() ?: return
        if (fragmentRef == null) {
            prevReadaloudHref?.let { target.clearHighlightInChapter(it) }
            prevReadaloudHref = null
            return
        }
        val chapterHref = fragmentRef.substringBefore('#')
        val sid = fragmentRef.substringAfter('#', "")
        if (sid.isBlank()) return
        val text = quotes[sid]?.highlight ?: return

        val prev = prevReadaloudHref
        if (prev != null && prev != chapterHref) target.clearHighlightInChapter(prev)
        prevReadaloudHref = chapterHref

        target.highlightInChapter(chapterHref, text, color.argb.toCssRgba())
    }

    override suspend fun applyAnnotations(
        renders: List<EpubReaderViewModel.HighlightRender>,
        theme: ReaderTheme,
    ) {
        val target = targetProvider() ?: return
        val annotationsByHref = renders
            .filter { !it.locator.text.highlight.isNullOrBlank() }
            .groupBy { it.locator.href.toString() }
            .mapValues { (_, items) ->
                items.map { h ->
                    AnnotationHighlight(
                        id = h.id,
                        text = h.locator.text.highlight!!,
                        cssColor = HighlightColor.fromToken(h.color).readerTint(theme).toCssRgba(),
                        hasNote = h.note != null,
                    )
                }
            }
        target.applyAnnotationHighlights(annotationsByHref)
    }

    override suspend fun applyNoteGlyphs(renders: List<EpubReaderViewModel.HighlightRender>) {
        // No-op: glyphs are emitted inside applyAnnotations via applyAnnotationHighlightsJs,
        // so no separate pass is needed.
    }

    override suspend fun applySearch(results: List<Locator>, activeIndex: Int) {
        val target = targetProvider() ?: return
        if (results.isEmpty() || activeIndex < 0) {
            target.applySearchHighlights(null)
            return
        }
        val activeLocator = results.getOrNull(activeIndex) ?: return
        val activeText = activeLocator.text.highlight?.take(40) ?: return
        if (activeText.isBlank()) return

        val activeHref = activeLocator.href.toString()
        val activeProgression = activeLocator.locations.progression?.toFloat() ?: 0f

        val resultsByHref = results
            .groupBy { it.href.toString() }
            .mapValues { (_, locators) ->
                locators.mapNotNull { it.text.highlight?.take(40)?.takeIf { t -> t.isNotBlank() } }
                    .distinct()
            }
            .filterValues { it.isNotEmpty() }

        target.applySearchHighlights(
            SearchHighlightsState(
                resultsByHref = resultsByHref,
                activeHref = activeHref,
                activeText = activeText,
                activeProgression = activeProgression,
                activeCssColor = SEARCH_ACTIVE_ARGB.toCssRgbaWithAlpha(SEARCH_DECORATION_ALPHA),
                inactiveCssColor = SEARCH_INACTIVE_ARGB.toCssRgbaWithAlpha(SEARCH_DECORATION_ALPHA),
            )
        )
    }

    override fun highlightSearchMatch(href: String, text: String) {
        // Highlights for all results (inactive) and the active match are applied via applySearch,
        // which fires from the LaunchedEffect on currentSearchIndex change. No separate per-match
        // call is needed in continuous mode.
    }
}
