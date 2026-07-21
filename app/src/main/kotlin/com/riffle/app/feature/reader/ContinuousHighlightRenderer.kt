package com.riffle.app.feature.reader

import com.riffle.core.models.HighlightColor
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

    /** Href of the chapter that holds the current sentence highlight, for clearing on chapter change. */
    private var prevSentenceHref: String? = null

    companion object {
        /**
         * CSS colour token emitted for Highlights-mode (ADR 0041) annotation marks in continuous
         * mode. The `<mark>` still wraps the text so annotation locator resolution works, but the
         * fill is transparent — paginated/vertical don't paint any decoration for accent-bar
         * highlights either. `internal` for regression pinning.
         */
        internal const val ACCENT_BAR_TRANSPARENT_CSS = "transparent"
    }

    override suspend fun applySentenceHighlight(
        fragmentRef: String?,
        quotes: Map<String, SentenceQuote>,
        color: HighlightColor,
    ) {
        val target = targetProvider() ?: return
        if (fragmentRef == null) {
            prevSentenceHref?.let { target.clearHighlightInChapter(it) }
            prevSentenceHref = null
            return
        }
        val chapterHref = fragmentRef.substringBefore('#')
        val sid = fragmentRef.substringAfter('#', "")
        if (sid.isBlank()) return
        // Look up first by full ref (Cadence's DomSentenceSource — keyed by "href#cd-N"), then
        // by sid alone (Readaloud's sidecar — keyed by "sN"). Same renderer serves both features.
        val text = (quotes[fragmentRef] ?: quotes[sid])?.highlight ?: return

        val prev = prevSentenceHref
        if (prev != null && prev != chapterHref) target.clearHighlightInChapter(prev)
        prevSentenceHref = chapterHref

        // Pass the fragment id so paint uses getElementById (chapter-unique) instead of
        // window.find (which lands on the first occurrence anywhere in the doc). For Cadence
        // this is `cd-N`; for Readaloud it's the sidecar's `sN`. Both are stable ids the
        // tokeniser or sidecar wrote onto the DOM.
        target.highlightInChapter(chapterHref, sid, text, color.argb.toCssRgba())
    }

    override suspend fun applyAnnotations(
        renders: List<EpubReaderViewModel.HighlightRender>,
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
                        // Highlights-mode (ADR 0041): paginated/vertical emit no Readium decoration
                        // and rely entirely on the synthesised HTML's border-left as the visible
                        // accent bar. Continuous still wraps the text itself in a <mark>, so a
                        // palette background here would paint on-text fill that the other modes
                        // don't paint. Emit a transparent background instead — the mark stays only
                        // to satisfy annotation locator resolution; tap dispatch is owned by the
                        // accent-bar span baked into the synthesised HTML.
                        //
                        // ADR 0046 §4: the `∅` (no colour) pick also emits a transparent
                        // background. Without this branch, `HighlightColor.fromToken("")` falls
                        // back to YELLOW and the mark keeps painting yellow after the user removed
                        // the colour — the exact bug that "removing color keeps it yellow"
                        // triggers in continuous mode. The mark is retained (never dropped) so
                        // layered emphasis and tap-to-edit still work on a colourless annotation.
                        cssColor = if (h.useAccentBarStyle || h.color.isEmpty()) {
                            ACCENT_BAR_TRANSPARENT_CSS
                        } else {
                            HighlightColor.fromToken(h.color).argb.toCssRgba()
                        },
                        hasNote = h.note != null,
                        before = h.locator.text.before.orEmpty(),
                        after = h.locator.text.after.orEmpty(),
                        // Highlights-mode: the on-text tap listener MUST NOT fire — the accent-bar
                        // span baked into the synthesised HTML owns tap dispatch. See
                        // AnnotationHighlight.suppressMarkClick.
                        suppressMarkClick = h.useAccentBarStyle,
                        emphasisStyles = h.emphasisStyles,
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
        if (results.isEmpty() || activeIndex < 0 || activeIndex >= results.size) {
            target.applySearchHighlights(null)
            return
        }
        val activeLocator = results[activeIndex]
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
