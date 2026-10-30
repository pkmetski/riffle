package com.riffle.app.feature.reader

import com.riffle.feature.reader.NOTE_GLYPH_DECORATION_GROUP
import com.riffle.core.models.EmphasisStyle
import com.riffle.core.models.HighlightColor
import com.riffle.core.domain.SentenceQuote
import com.riffle.feature.reader.HighlightRender
import com.riffle.feature.reader.decorations.installHighlightLeadingObserverJs
import org.json.JSONObject
import org.readium.r2.navigator.Decoration
import org.readium.r2.shared.publication.Locator
import com.riffle.feature.reader.EmphasisDomInjector

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
     * ADR 0056: evaluates arbitrary JS on the current chapter's WebView. Used to wrap emphasis
     * ranges in styled `<span>`s so bold/italic actually reflow the underlying text — overlay
     * decorations can't do that. Null (test / no-navigator paths) → the injector is skipped and
     * emphasis falls back to overlay-only rendering.
     */
    private val evaluateJavascript: (suspend (String) -> Unit)? = null,
    /**
     * Provides the [EpubReaderViewModel.HighlightRender]s' textSnippet / textBefore for the DOM
     * wrap script. Not on the render itself because the injector needs the raw annotation strings
     * (Locator's `text.highlight` may be reflow-truncated or missing on early emits).
     */
    private val emphasisRangeProvider: () -> List<EmphasisDomInjector.EmphasisRange> = { emptyList() },
    /**
     * Converts a [HighlightRender.locatorJson] string to a [Locator]. Injected so tests can
     * supply a fake that bypasses [android.net.Uri] (unavailable in JVM unit tests).
     * Production default uses [Locator.fromJSON].
     */
    private val locatorFromJson: (String) -> Locator? = { json ->
        Locator.fromJSON(JSONObject(json))
    },
) : HighlightRenderer {

    private var hasSentenceDecoration = false
    private var hasAnnotationDecorations = false
    private var hasEmphasisDecorations = false
    private var hasNoteGlyphDecorations = false
    private var hasSearchDecorations = false

    override suspend fun applySentenceHighlight(
        fragmentRef: String?,
        quotes: Map<String, SentenceQuote>,
        color: HighlightColor,
    ) {
        installLeadingObserver()
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
        renders: List<HighlightRender>,
    ) {
        // Install the MutationObserver that expands decoration boxes to full line-height
        // as a microtask (before paint) whenever Readium adds .riffle-highlight-tint elements.
        // Must happen before any applyDecorationsBlock call. The JS-side guard prevents
        // double-installation; on a new page load window is fresh so the guard is absent.
        installLeadingObserver()
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
            // ADR 0056 §4:
            //  - Real colour token: paint the saturated highlight.
            //  - Empty colour + this render is currently being edited (sheet is open on it):
            //    paint the temporary ∅-editing wash so the user can see the range they're
            //    working on. Wash vanishes as soon as the sheet dismisses.
            //  - Empty colour + NOT being edited: emit a fully-transparent decoration so the
            //    range stays tappable but nothing paints; layered emphasis (bold/italic via DOM,
            //    underline/strike via companion decorations) is the only visible surface.
            val locator = locatorFromJson(h.locatorJson) ?: return@mapNotNull null
            val tint = when {
                h.color.isNotEmpty() -> HighlightColor.fromToken(h.color).argb
                h.isBeingEdited -> EMPTY_COLOR_EDITING_HINT_ARGB
                else -> 0x00000000
            }
            Decoration(
                id = h.id,
                locator = locator,
                style = HighlightTintStyle(tint = tint),
            )
        }
        if (decorations.isEmpty()) {
            if (hasAnnotationDecorations) {
                applyDecorationsBlock(emptyList(), "annotations")
                hasAnnotationDecorations = false
            }
            return
        }
        // Queue bold/italic DOM injection BEFORE Readium measures decoration positions.
        // EmphasisDomInjector wraps bold/italic text in <span> elements, causing text reflow.
        // evaluateJavascript calls are serialized through the same WebView JS thread queue, so
        // injecting first guarantees bold/italic spans exist when Readium's applyDecorations JS
        // runs — the measured tap-target rects reflect the already-reflowed layout.
        // Without this ordering, rects are baked from the pre-bold DOM, then the DOM reflows after,
        // leaving tap targets at stale coordinates (the annotation-not-tappable bug).
        injectEmphasisDom()
        // Pass decorations as-is; Readium diffs the new list against its internal state and
        // emits only Added/Removed/Updated changes in a single requestAnimationFrame — no flash.
        // Layout repositioning after page load or CSS reflow is handled automatically by Readium's
        // ResizeObserver-based requestLayout, which removes and re-creates all elements within a
        // single rAF when the column layout changes. Forcing repositioning by stamping a generation
        // marker into locator.title caused Readium to emit Updated (remove + add) for all 119+
        // decorations on each of 4 re-applies, producing visible flashing even though each
        // individual rAF was atomic.
        applyDecorationsBlock(decorations, "annotations")
        hasAnnotationDecorations = true
        // ADR 0056: layered emphasis paints via companion decorations. Underline uses Readium's
        // built-in Style.Underline; strike/bold/italic v1 render as tinted overlays (bold + italic
        // are approximations pending true DOM mutation — captured as follow-up).
        applyEmphasisCompanions(renders)
        // Post-apply layout shifts (page-load completion, reflow, orientation) are covered by the
        // outer LaunchedEffect in EpubReaderScreen re-keying on pageLoadGeneration / reflowGeneration
        // and re-invoking applyAnnotations. A fixed local settle loop here forced 2.6s of
        // clear+re-apply JS on cold open and pinned the WebView main thread — the source of the
        // 2-3s input sluggishness when opening a book with highlights.
    }

    override suspend fun applyNoteGlyphs(
        renders: List<HighlightRender>,
    ) {
        // Highlights mode (useAccentBarStyle) skips glyphs: the note is already visible as an
        // <aside> in the synthesised HTML, and a glyph in the left gutter would overlap the
        // accent-bar tap span and swallow taps that should open the highlight-actions popup.
        val noted = renders.filter { it.note != null && !it.useAccentBarStyle }
        if (noted.isEmpty()) {
            if (!hasNoteGlyphDecorations) return
            applyDecorationsBlock(emptyList(), NOTE_GLYPH_DECORATION_GROUP)
            hasNoteGlyphDecorations = false
            return
        }
        val noteDecorations = noted.mapNotNull { h ->
            val locator = locatorFromJson(h.locatorJson) ?: return@mapNotNull null
            Decoration(id = h.id, locator = locator, style = NoteGlyphStyle())
        }
        applyDecorationsBlock(noteDecorations, NOTE_GLYPH_DECORATION_GROUP)
        evaluateJavascript?.invoke(noteGlyphViewportClampAfterApplyJs())
        evaluateJavascript?.invoke(noteGlyphFocusAfterApplyJs())
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
        // Post-navigation layout shifts are covered by the outer LaunchedEffect in
        // EpubReaderScreen re-keying on pageLoadGeneration / reflowGeneration and re-invoking
        // applySearch. Local settle loop removed for parity with applyAnnotations.
    }

    override fun highlightSearchMatch(href: String, text: String) {
        // Readium shows all search results via DecorableNavigator; per-match highlighting is
        // not needed — the active result is distinguished by color in applySearch.
    }

    private suspend fun applyDecorationsWithClear(decorations: List<Decoration>, group: String) {
        applyDecorationsBlock(emptyList(), group)
        applyDecorationsBlock(decorations, group)
    }

    private suspend fun installLeadingObserver() {
        evaluateJavascript?.invoke(installHighlightLeadingObserverJs())
    }

    /**
     * Called before [applyDecorationsWithClear] so Readium measures tap-target rects from the
     * already-reflowed (post-bold/italic) DOM rather than from a pre-reflow snapshot.
     */
    private suspend fun injectEmphasisDom() {
        evaluateJavascript?.let { runJs ->
            val ranges = emphasisRangeProvider()
                .filter { it.styles.any { s -> s == EmphasisStyle.BOLD || s == EmphasisStyle.ITALIC } }
            runJs(EmphasisDomInjector.script(ranges))
        }
    }

    private suspend fun applyEmphasisCompanions(renders: List<HighlightRender>) {
        val decorations = renders.flatMap { h ->
            if (h.emphasisStyles.isEmpty()) return@flatMap emptyList<Decoration>()
            val locator = locatorFromJson(h.locatorJson) ?: return@flatMap emptyList()
            buildList {
                if (EmphasisStyle.UNDERLINE in h.emphasisStyles) {
                    add(
                        Decoration(
                            id = "${h.id}#u",
                            locator = locator,
                            style = Decoration.Style.Underline(tint = EMPHASIS_UNDERLINE_ARGB),
                        )
                    )
                }
                if (EmphasisStyle.STRIKE in h.emphasisStyles) {
                    add(
                        Decoration(
                            id = "${h.id}#s",
                            locator = locator,
                            style = EmphasisStrikeStyle(tint = EMPHASIS_STRIKE_ARGB),
                        )
                    )
                }
                // ADR 0056: bold and italic reflow text via DOM span injection ([injectEmphasisDom])
                // rather than painting tint overlays — a tint overlay on top of real bold text
                // would add a distracting colored background.
            }
        }
        // Apply overlay decorations (underline / strike). Empty list = clear the group.
        if (decorations.isEmpty()) {
            if (hasEmphasisDecorations) {
                applyDecorationsBlock(emptyList(), "emphasis")
                hasEmphasisDecorations = false
            }
        } else {
            applyDecorationsBlock(decorations, "emphasis")
            hasEmphasisDecorations = true
        }
        // Bold/italic DOM injection is handled by [injectEmphasisDom] in [applyAnnotations],
        // called before [applyDecorationsWithClear] so Readium measures tap-target rects from the
        // already-reflowed DOM. Nothing to do here — this function only applies overlay decorations.
        // Post-apply layout shifts are covered by the outer LaunchedEffect re-keying on
        // pageLoadGeneration / reflowGeneration. Local settle loop removed for parity with
        // applyAnnotations — see the comment there for rationale.
    }

    companion object {
        // Distinct low-alpha tints per emphasis so bold/italic/strike are visually differentiable
        // when layered over a highlight. Values kept within Readium's Highlight alpha budget (~0.3).
        // v1 approximations — replaced by true text-style overlay when DOM mutation lands.
        private const val EMPHASIS_UNDERLINE_ARGB: Int = 0xFF1976D2.toInt() // solid line color
        private const val EMPHASIS_STRIKE_ARGB: Int = 0xFFE53935.toInt()    // solid red strike line
        // ADR 0056: temporary neutral wash painted only while the sheet is open on a ∅-color
        // annotation, so the user can see the range they're working on. Not persistent — a
        // ∅ annotation with the sheet closed and no emphasis draws nothing at all.
        internal const val EMPTY_COLOR_EDITING_HINT_ARGB: Int = 0x30808080
        // Bold and italic don't paint overlays anymore — they reflow the underlying text via
        // the DOM injector. See [EmphasisDomInjector].
    }
}
