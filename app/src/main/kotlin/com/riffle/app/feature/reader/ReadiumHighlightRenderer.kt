package com.riffle.app.feature.reader

import com.riffle.core.domain.HighlightColor
import com.riffle.core.domain.ReadaloudHighlightColor
import com.riffle.core.domain.ReaderTheme
import com.riffle.core.domain.SentenceQuote
import kotlinx.coroutines.delay
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.publication.Locator

internal class ReadiumHighlightRenderer(
    /**
     * Calls [DecorableNavigator.applyDecorations] on the current fragment, dispatching to
     * [Dispatchers.Main]. May be a no-op if the navigator is not yet available (returns without
     * calling the block in that case). The Screen provides this.
     */
    private val applyDecorationsBlock: suspend (decorations: List<Decoration>, group: String) -> Unit,
    /**
     * Builds a Readium [Locator] from a "href#spanId" ref and an optional sentence quote.
     * Mirrors the Screen-level [fragmentLocator] function.
     */
    private val fragmentLocator: (ref: String, quote: SentenceQuote?) -> Locator?,
    /**
     * Returns a stable identity token for the current navigator instance. Used by the
     * search settle loop to abort when the fragment has been replaced (page navigation,
     * rotation). In tests, always returns the same value to let the loop run to completion.
     */
    private val currentNavigatorStamp: () -> Any? = { null },
) : HighlightRenderer {

    private var hasReadaloudDecoration = false
    private var hasAnnotationDecorations = false
    private var hasNoteGlyphDecorations = false
    private var hasSearchDecorations = false

    override suspend fun applyReadaloud(
        fragmentRef: String?,
        quotes: Map<String, SentenceQuote>,
        color: ReadaloudHighlightColor,
    ) {
        if (fragmentRef == null) {
            if (!hasReadaloudDecoration) return
            applyDecorationsBlock(emptyList(), "readaloud")
            hasReadaloudDecoration = false
            return
        }
        val sid = fragmentRef.substringAfter('#', "")
        val quote = quotes[sid]
        val locator = fragmentLocator(fragmentRef, quote) ?: return
        val decoration = Decoration(
            id = "readaloud_active",
            locator = locator,
            style = HighlightTintStyle(tint = color.argb),
        )
        applyDecorationsWithClear(listOf(decoration), "readaloud")
        hasReadaloudDecoration = true
    }

    override suspend fun applyAnnotations(
        renders: List<EpubReaderViewModel.HighlightRender>,
        theme: ReaderTheme,
    ) {
        if (renders.isEmpty()) {
            if (!hasAnnotationDecorations) return
            applyDecorationsBlock(emptyList(), "annotations")
            hasAnnotationDecorations = false
            return
        }
        val decorations = renders.map { h ->
            Decoration(
                id = h.id,
                locator = h.locator,
                style = HighlightTintStyle(tint = HighlightColor.fromToken(h.color).readerTint(theme)),
            )
        }
        applyDecorationsWithClear(decorations, "annotations")
        hasAnnotationDecorations = true
    }

    override suspend fun applyNoteGlyphs(
        renders: List<EpubReaderViewModel.HighlightRender>,
    ) {
        val noted = renders.filter { it.note != null }
        if (noted.isEmpty()) {
            if (!hasNoteGlyphDecorations) return
            applyDecorationsBlock(emptyList(), "annotation-notes")
            hasNoteGlyphDecorations = false
            return
        }
        val noteDecorations = noted.map { h ->
            Decoration(id = h.id, locator = h.locator, style = NoteGlyphStyle())
        }
        applyDecorationsWithClear(noteDecorations, "annotation-notes")
        hasNoteGlyphDecorations = true
    }

    override suspend fun applySearch(results: List<Locator>, activeIndex: Int) {
        if (results.isEmpty()) {
            if (!hasSearchDecorations) return
            applyDecorationsBlock(emptyList(), "search")
            hasSearchDecorations = false
            return
        }
        val decorations = results.mapIndexed { index, locator ->
            Decoration(
                id = "search_$index",
                locator = locator,
                style = if (index == activeIndex)
                    Decoration.Style.Highlight(tint = SEARCH_ACTIVE_ARGB)
                else
                    Decoration.Style.Highlight(tint = SEARCH_INACTIVE_ARGB),
            )
        }
        applyDecorationsBlock(decorations, "search")
        hasSearchDecorations = true
        // Re-apply across a post-navigation settle window so decoration boxes track the final
        // layout (Readium fixes rects at applyDecorations time). See the comment at the original
        // search LaunchedEffect in EpubReaderScreen for full rationale.
        val stamp = currentNavigatorStamp()
        for (settleDelayMs in longArrayOf(400L, 600L, 700L, 900L)) {
            delay(settleDelayMs)
            if (currentNavigatorStamp() !== stamp) break
            applyDecorationsWithClear(decorations, "search")
        }
    }

    override fun highlightSearchMatch(href: String, text: String, cssColor: String) {
        // Readium shows all search results via DecorableNavigator; per-match highlighting is
        // not needed — the active result is distinguished by color in applySearch.
    }

    private suspend fun applyDecorationsWithClear(decorations: List<Decoration>, group: String) {
        applyDecorationsBlock(emptyList(), group)
        applyDecorationsBlock(decorations, group)
    }
}
