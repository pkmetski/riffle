package com.riffle.shared.reader

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.models.Annotation
import com.riffle.core.models.EmphasisStyle
import com.riffle.feature.reader.EmphasisDomInjector
import com.riffle.feature.reader.NavigatorPageLoad
import com.riffle.feature.reader.decorations.FigureBorderDecoration
import com.riffle.feature.reader.decorations.figureBorderApplyJs
import com.riffle.feature.reader.decorations.figureBorderInjectionJs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * Paints every annotation the open book has, and keeps them painted.
 *
 * One observer over `observeAnnotations` (all types, the same flow Android's `AnnotationSession`
 * binds) rather than one per type: the emphasis layer has to be unioned onto the highlight it
 * shares a CFI with, and the figure-border pass needs highlights and image rows together, so a
 * per-type subscription would have to re-join them anyway and would repaint three times per
 * change.
 *
 * Five surfaces, because five things can be visible at once:
 *  - **highlights** — the colour wash, and a transparent-but-tappable box for a `∅` highlight;
 *  - **note glyphs** — the margin marker for a highlight carrying a note;
 *  - **bookmarks** — a gutter bar on the bookmarked paragraph;
 *  - **emphasis** — ADR 0056 underline / strike as decorations;
 *  - **figures** — CSS outlines around annotated images, plus the bold/italic DOM wrap, both
 *    injected as JavaScript because neither can be expressed as an overlay.
 *
 * Re-applied on every page-load event: Readium reports one per resource and again after a
 * reflow, and decorations for a resource that was not loaded when `apply` ran are never drawn.
 */
class AnnotationDecorationCoordinator(
    private val sourceId: String,
    private val itemId: String,
    private val annotationStore: AnnotationStore,
    private val navigator: ReadiumSwiftNavigator,
) {
    private var scope: CoroutineScope? = null

    private val _annotations = MutableStateFlow<List<Annotation>>(emptyList())

    /** Every live annotation on this book — what the annotations panel lists. */
    val annotations: StateFlow<List<Annotation>> = _annotations

    fun start() {
        val coordinatorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = coordinatorScope

        // Only these groups get taps. Registering is idempotent and survives a re-open, so it
        // happens once here rather than per emission.
        ReaderDecorationGroups.activable.forEach { navigator.observeDecorationGroup(it) }

        coordinatorScope.launch {
            combine(
                annotationStore.observeAnnotations(sourceId, itemId),
                annotationStore.observeEmphasis(sourceId, itemId),
                // Emit a seed immediately so combine doesn't wait for first real page load.
                navigator.pageLoadEvents.onStart { emit(NavigatorPageLoad(0)) },
            ) { all, emphasis, _ -> all to emphasis }
                .collect { (all, emphasis) -> apply(all, emphasis) }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        navigator.applyDecorations(ReaderDecorationGroups.highlights, emptyList())
        navigator.applyDecorations(ReaderDecorationGroups.bookmarks, emptyList())
        navigator.applyDecorations(ReaderDecorationGroups.noteGlyphs, emptyList())
        navigator.applyDecorations(ReaderDecorationGroups.emphasis, emptyList())
    }

    private suspend fun apply(all: List<Annotation>, emphasis: List<Annotation>) {
        _annotations.value = all
        val highlights = all.filter { it.type == AnnotationEntity.TYPE_HIGHLIGHT }
        val bookmarks = all.filter { it.type == AnnotationEntity.TYPE_BOOKMARK }

        navigator.applyDecorations(
            ReaderDecorationGroups.highlights,
            highlights.mapNotNull { annotationToHighlightDecoration(it) },
        )
        navigator.applyDecorations(
            ReaderDecorationGroups.noteGlyphs,
            highlights.mapNotNull { annotationToNoteGlyphDecoration(it) },
        )
        navigator.applyDecorations(
            ReaderDecorationGroups.bookmarks,
            bookmarks.map { annotationToBookmarkDecoration(it) },
        )
        navigator.applyDecorations(
            ReaderDecorationGroups.emphasis,
            emphasis.mapNotNull { annotationToEmphasisDecoration(it) },
        )

        // Figures: a CSS outline round the annotated image plus, for TYPE_IMAGE rows, a tint on
        // its caption. Not expressible as a decoration — Readium decorations anchor to a text
        // range, and an <img> has none — so Android injects CSS and iOS runs the identical
        // shared builder through the JS seam.
        navigator.evaluateJavaScriptForDecorations(figureBorderInjectionJs())
        navigator.evaluateJavaScriptForDecorations(
            figureBorderApplyJs(
                cssRules = FigureBorderDecoration.buildCssRules(all),
                svgMatches = FigureBorderDecoration.buildSvgMatches(all),
                rasterMarks = FigureBorderDecoration.buildRasterMarks(all),
            ),
        )

        // Bold and italic reflow text, so they cannot be overlays — they wrap the range in a
        // styled <span>. Must run before Readium measures the decorations above, or the tap
        // rects are baked from the pre-reflow layout; Android orders it the same way and the
        // JS queue is serialised, so issuing it last here would be wrong. It is issued after
        // only because `applyDecorations` is itself asynchronous on the Swift side and the
        // re-apply on the next page-load event settles the rects.
        navigator.evaluateJavaScriptForDecorations(
            EmphasisDomInjector.script(emphasis.mapNotNull { it.toReflowingEmphasisRange() }),
        )
    }
}

/**
 * The bold/italic half of an emphasis row, or null when it carries neither.
 *
 * Underline and strike are excluded deliberately: they are already painted by the emphasis
 * decoration group, and wrapping them in a span as well would double-draw.
 */
private fun Annotation.toReflowingEmphasisRange(): EmphasisDomInjector.EmphasisRange? {
    val styles = emphasisStyles?.filter { it == EmphasisStyle.BOLD || it == EmphasisStyle.ITALIC }
        ?.toSet()
        .orEmpty()
    if (styles.isEmpty()) return null
    return EmphasisDomInjector.EmphasisRange(
        id = id,
        textSnippet = textSnippet,
        textBefore = textBefore,
        styles = styles,
    )
}
