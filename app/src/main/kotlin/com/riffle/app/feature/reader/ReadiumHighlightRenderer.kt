package com.riffle.app.feature.reader

import com.riffle.core.domain.EmphasisStyle
import com.riffle.core.domain.HighlightColor
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

    private var hasSentenceDecoration = false
    private var hasAnnotationDecorations = false
    private var hasEmphasisDecorations = false
    private var hasNoteGlyphDecorations = false
    private var hasSearchDecorations = false
    /** Monotonic counter for [applyEmphasisCompanions] calls. The settle-loop breaks when the
     *  counter moves, so a rapid re-toggle within the 2.6s window doesn't repaint a
     *  since-cleared decoration. See code-review F6. */
    private var emphasisApplyGeneration: Long = 0L

    override suspend fun applySentenceHighlight(
        fragmentRef: String?,
        quotes: Map<String, SentenceQuote>,
        color: HighlightColor,
    ) {
        if (fragmentRef == null) {
            if (!hasSentenceDecoration) return
            applyDecorationsBlock(emptyList(), "readaloud")
            hasSentenceDecoration = false
            return
        }
        val sid = fragmentRef.substringAfter('#', "")
        // Try full ref (Cadence's DomSentenceSource — keyed by "href#cd-N") before falling
        // back to sid alone (Readaloud's sidecar — keyed by "sN").
        val quote = quotes[fragmentRef] ?: quotes[sid]
        val locator = fragmentLocator(fragmentRef, quote) ?: return
        val decoration = Decoration(
            id = "readaloud_active",
            locator = locator,
            style = HighlightTintStyle(tint = color.argb),
        )
        applyDecorationsWithClear(listOf(decoration), "readaloud")
        hasSentenceDecoration = true
    }

    override suspend fun applyAnnotations(
        renders: List<EpubReaderViewModel.HighlightRender>,
    ) {
        if (renders.isEmpty()) {
            if (!hasAnnotationDecorations) return
            applyDecorationsBlock(emptyList(), "annotations")
            hasAnnotationDecorations = false
            return
        }
        // Highlights mode: accent-bar highlights emit NO Readium decoration — the visible bar and
        // the tap dispatch both live in the synthesised HTML (see HighlightsPublicationFactory +
        // AnnotationTapUrl). Applying an empty decoration list still clears any previously-applied
        // decorations for this group; if the list is empty we take the early-return above.
        val decorations = renders.mapNotNull { h ->
            if (h.useAccentBarStyle) return@mapNotNull null
            Decoration(
                id = h.id,
                locator = h.locator,
                style = HighlightTintStyle(tint = HighlightColor.fromToken(h.color).argb),
            )
        }
        if (decorations.isEmpty()) {
            if (hasAnnotationDecorations) {
                applyDecorationsBlock(emptyList(), "annotations")
                hasAnnotationDecorations = false
            }
            return
        }
        // Initial apply uses clear+apply too — Readium's decoration diff treats an identical
        // (id, locator, style) list as a no-op, so a re-fire of the same list (theme change bumps
        // pageLoadGeneration, LaunchedEffect keys the same renders) would keep the stale pre-reflow
        // rects until the 400ms settle tick fires. Matches [applySentenceHighlight]'s pre-clear semantics.
        applyDecorationsWithClear(decorations, "annotations")
        hasAnnotationDecorations = true
        // ADR 0046: layered emphasis paints via companion decorations. Underline uses Readium's
        // built-in Style.Underline; strike/bold/italic v1 render as tinted overlays (bold + italic
        // are approximations pending true DOM mutation — captured as follow-up).
        applyEmphasisCompanions(renders)
        // Readium fixes decoration rects at applyDecorations time. When the first apply runs before
        // reflow has fully settled (fresh navigator, chapter change, orientation flip), the rects
        // land against a still-shifting layout and the highlight is either invisible or in the wrong
        // place. Same settle window as [applySearch] — clear+re-apply on each tick so the diff
        // forces Readium to re-measure and re-position the boxes.
        val stamp = currentNavigatorStamp()
        for (settleDelayMs in longArrayOf(400L, 600L, 700L, 900L)) {
            delay(settleDelayMs)
            if (currentNavigatorStamp() !== stamp) break
            applyDecorationsWithClear(decorations, "annotations")
        }
    }

    override suspend fun applyNoteGlyphs(
        renders: List<EpubReaderViewModel.HighlightRender>,
    ) {
        // Highlights mode (useAccentBarStyle) skips glyphs: the note is already visible as an
        // <aside> in the synthesised HTML, and a glyph in the left gutter would overlap the
        // accent-bar tap span and swallow taps that should open the highlight-actions popup.
        val noted = renders.filter { it.note != null && !it.useAccentBarStyle }
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

    override fun highlightSearchMatch(href: String, text: String) {
        // Readium shows all search results via DecorableNavigator; per-match highlighting is
        // not needed — the active result is distinguished by color in applySearch.
    }

    private suspend fun applyDecorationsWithClear(decorations: List<Decoration>, group: String) {
        applyDecorationsBlock(emptyList(), group)
        applyDecorationsBlock(decorations, group)
    }

    /**
     * ADR 0046: paint emphasis marks as companion decorations layered over the highlight decoration.
     * Only [EmphasisStyle.UNDERLINE] uses Readium's built-in [Decoration.Style.Underline]; the
     * other three styles are painted as tinted [Decoration.Style.Highlight] overlays with
     * distinctive colors so the user sees SOMETHING for every stored style — proper text-style
     * reflow for bold/italic/strike requires WebView DOM mutation and is captured as a follow-up.
     * Grouped separately from "annotations" so a highlight recolor doesn't rebuild the emphasis
     * overlays and vice versa.
     */
    private suspend fun applyEmphasisCompanions(renders: List<EpubReaderViewModel.HighlightRender>) {
        val myGeneration = ++emphasisApplyGeneration
        val decorations = renders.flatMap { h ->
            if (h.emphasisStyles.isEmpty()) return@flatMap emptyList()
            buildList {
                if (EmphasisStyle.UNDERLINE in h.emphasisStyles) {
                    add(
                        Decoration(
                            id = "${h.id}#u",
                            locator = h.locator,
                            style = Decoration.Style.Underline(tint = EMPHASIS_UNDERLINE_ARGB),
                        )
                    )
                }
                if (EmphasisStyle.STRIKE in h.emphasisStyles) {
                    add(
                        Decoration(
                            id = "${h.id}#s",
                            locator = h.locator,
                            style = Decoration.Style.Highlight(tint = EMPHASIS_STRIKE_ARGB),
                        )
                    )
                }
                if (EmphasisStyle.BOLD in h.emphasisStyles) {
                    add(
                        Decoration(
                            id = "${h.id}#b",
                            locator = h.locator,
                            style = Decoration.Style.Highlight(tint = EMPHASIS_BOLD_ARGB),
                        )
                    )
                }
                if (EmphasisStyle.ITALIC in h.emphasisStyles) {
                    add(
                        Decoration(
                            id = "${h.id}#i",
                            locator = h.locator,
                            style = Decoration.Style.Highlight(tint = EMPHASIS_ITALIC_ARGB),
                        )
                    )
                }
            }
        }
        if (decorations.isEmpty()) {
            if (hasEmphasisDecorations) {
                applyDecorationsBlock(emptyList(), "emphasis")
                hasEmphasisDecorations = false
            }
            return
        }
        applyDecorationsWithClear(decorations, "emphasis")
        hasEmphasisDecorations = true
        // Same settle window as highlights so decoration rects follow post-reflow layout. Break
        // as soon as either the navigator instance OR the emphasis generation moves — the latter
        // means a fresh applyEmphasisCompanions call has landed with a different decoration list,
        // and continuing to repaint THIS list would fight it.
        val stamp = currentNavigatorStamp()
        for (settleDelayMs in longArrayOf(400L, 600L, 700L, 900L)) {
            delay(settleDelayMs)
            if (currentNavigatorStamp() !== stamp) break
            if (myGeneration != emphasisApplyGeneration) break
            applyDecorationsWithClear(decorations, "emphasis")
        }
    }

    companion object {
        // Distinct low-alpha tints per emphasis so bold/italic/strike are visually differentiable
        // when layered over a highlight. Values kept within Readium's Highlight alpha budget (~0.3).
        // v1 approximations — replaced by true text-style overlay when DOM mutation lands.
        private const val EMPHASIS_UNDERLINE_ARGB: Int = 0xFF1976D2.toInt() // solid line color, alpha handled by Readium
        private const val EMPHASIS_STRIKE_ARGB: Int = 0x66E53935.toInt()    // faint red wash
        private const val EMPHASIS_BOLD_ARGB: Int = 0x66FF9800.toInt()      // faint amber wash
        private const val EMPHASIS_ITALIC_ARGB: Int = 0x668E24AA.toInt()    // faint purple wash
    }
}
