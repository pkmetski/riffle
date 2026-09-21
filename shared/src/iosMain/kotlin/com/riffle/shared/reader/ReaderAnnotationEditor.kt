package com.riffle.shared.reader

import com.riffle.core.database.AnnotationEntity
import com.riffle.core.domain.AnnotationStore
import com.riffle.core.domain.ReaderOrientation
import com.riffle.core.domain.normalizeEpubHref
import com.riffle.core.models.Annotation
import com.riffle.core.models.EmphasisStyle
import com.riffle.core.models.HighlightColor
import com.riffle.feature.reader.ColumnSnap
import com.riffle.feature.reader.FALLBACK_ORIGIN_FONT_FAMILY
import com.riffle.feature.reader.NavigatorSelection
import com.riffle.feature.reader.bookmarkEpsFor
import com.riffle.feature.reader.buildHighlightAnchor
import com.riffle.feature.reader.draftFieldsOf
import com.riffle.feature.reader.findEnclosedFiguresInHtml
import com.riffle.feature.reader.planHighlightCommit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns the reader's gestures into stored annotations.
 *
 * This is the iOS half of what Android's `EpubReaderViewModel` does for annotations. Every
 * *decision* it makes is the shared one — [buildHighlightAnchor] resolves the selection's true
 * position and CFI, [planHighlightCommit] decides the overlap/adjacency merges and the figure
 * absorption, [bookmarkEpsFor] decides whether the current page already carries a bookmark. What
 * lives here is only the plumbing the two hosts genuinely cannot share: reading the chapter
 * source out of Readium-Swift, probing the DOM for a page anchor, and the store writes.
 *
 * Reading modes: everything here works in all of them. Paginated and scroll (which is what both
 * Vertical and Continuous map to on iOS — see [epubScrollMode]) differ only in what
 * [ColumnSnap.CAPTURE_PAGE_FRAGMENT_ANCHOR_JS] can answer, and that script deliberately returns
 * null for a scrolling document, which is exactly the legacy "no fragment anchor" bookmark shape.
 */
class ReaderAnnotationEditor(
    private val sourceId: String,
    private val itemId: String,
    private val annotationStore: AnnotationStore,
    private val navigator: ReadiumSwiftNavigator,
    /** Every live annotation on the book — supplied by [AnnotationDecorationCoordinator]. */
    private val annotations: () -> List<Annotation>,
    /** The publication's reading order, for spine-index resolution. */
    private val spineHrefs: () -> List<String>,
    /** Per-resource Readium position counts, for the bookmark epsilon. */
    private val spinePositionCounts: () -> List<Int>,
    /** The reader's configured orientation, for the bookmark epsilon. */
    private val orientation: () -> ReaderOrientation,
) {

    /**
     * Persist [selection] as a highlight, optionally with a note and ADR 0056 emphasis styles.
     *
     * [color] null is the `∅` swatch: the row is created with an empty colour token so its note
     * and emphasis survive while nothing paints — the same representation Android persists, and
     * the reason [annotationToHighlightDecoration] emits a transparent decoration for it.
     *
     * Returns the created row, or null when the chapter could not be read or no CFI range could
     * be built for the selection — persisting a highlight without a range would produce an
     * annotation that can never be rendered or navigated to.
     */
    suspend fun createHighlight(
        selection: NavigatorSelection,
        color: HighlightColor?,
        styles: Set<EmphasisStyle>,
        note: String?,
    ): Annotation? {
        val href = selection.href
        val spineIndex = spineIndexOf(href) ?: return null
        val html = navigator.readChapterHtml(href) ?: return null
        val anchor = buildHighlightAnchor(
            html = html,
            spineIndex = spineIndex,
            snippet = selection.text,
            textBefore = selection.before,
            pageProgression = selection.progression,
        ) ?: return null

        val figures = findEnclosedFiguresInHtml(
            html,
            anchor.figureRangeStartChar,
            anchor.figureRangeEndChar,
        )
        val colorToken = color?.token ?: ""
        val chapterAnnotations = annotations().filter {
            normalizeEpubHref(it.chapterHref) == normalizeEpubHref(href)
        }
        val plan = planHighlightCommit(
            html = html,
            draftFields = draftFieldsOf(
                cfiRange = anchor.cfiRange,
                textSnippet = selection.text,
                textBefore = selection.before,
                textAfter = selection.after,
                progression = anchor.progression,
                embeddedFigures = figures.ifEmpty { null },
            ),
            draftSpineIndex = spineIndex,
            draftChapterHref = href,
            draftColor = colorToken,
            draftEmphasisStyles = styles,
            candidates = chapterAnnotations.filter { it.type == AnnotationEntity.TYPE_HIGHLIGHT },
            emphasisPool = annotations().filter { it.type == AnnotationEntity.TYPE_EMPHASIS },
            imageAnnotations = chapterAnnotations.filter { it.type == AnnotationEntity.TYPE_IMAGE },
        )
        // Emphasis siblings first, so a cascade never runs after its anchor is gone.
        plan.deleteEmphasisIds.forEach { annotationStore.delete(it) }
        plan.deleteHighlightIds.forEach { annotationStore.delete(it) }
        plan.deleteImageIds.forEach { annotationStore.delete(it) }

        val fields = plan.fields
        val created = annotationStore.createHighlight(
            sourceId = sourceId,
            itemId = itemId,
            cfi = fields.cfiRange,
            textSnippet = fields.textSnippet,
            chapterHref = href,
            textBefore = fields.textBefore,
            textAfter = fields.textAfter,
            color = colorToken,
            spineIndex = spineIndex,
            progression = fields.progression,
            embeddedFigures = fields.embeddedFigures,
            originFontFamily = originFontFamily(),
            textSnippetHtml = null,
        )
        if (!note.isNullOrBlank()) annotationStore.updateNote(created.id, note)
        if (styles.isNotEmpty()) {
            annotationStore.createEmphasis(
                sourceId = sourceId,
                itemId = itemId,
                cfi = fields.cfiRange,
                textSnippet = fields.textSnippet,
                chapterHref = href,
                styles = styles,
                textBefore = fields.textBefore,
                textAfter = fields.textAfter,
                spineIndex = spineIndex,
                progression = fields.progression,
                originFontFamily = originFontFamily(),
            )
        }
        navigator.clearSelection()
        return created
    }

    suspend fun recolor(id: String, color: HighlightColor?) {
        annotationStore.recolor(id, color?.token ?: "")
    }

    suspend fun setNote(id: String, note: String?) {
        annotationStore.updateNote(id, note?.takeIf { it.isNotBlank() })
    }

    /**
     * Delete an annotation and every emphasis row anchored at the same CFI.
     *
     * ADR 0056 §4: the emphasis rows are siblings, not children — nothing in the schema removes
     * them with their anchor. Leaving them behind is what produced the "the highlight is gone
     * from the panel but the text stays bold forever" report.
     */
    suspend fun delete(id: String) {
        val target = annotations().firstOrNull { it.id == id }
        if (target != null) {
            annotations()
                .filter { it.type == AnnotationEntity.TYPE_EMPHASIS && it.cfi == target.cfi }
                .forEach { annotationStore.delete(it.id) }
        }
        annotationStore.delete(id)
    }

    /**
     * Toggle one ADR 0056 style on the emphasis row sharing [annotation]'s CFI, creating or
     * tombstoning the row as the set becomes non-empty or empty.
     *
     * An empty styles set is not a legal `TYPE_EMPHASIS` state — `AnnotationStore` rejects it —
     * so clearing the last chip deletes the row rather than writing an empty one.
     */
    suspend fun toggleEmphasis(annotation: Annotation, style: EmphasisStyle) {
        val existing = annotations()
            .firstOrNull { it.type == AnnotationEntity.TYPE_EMPHASIS && it.cfi == annotation.cfi }
        val current = existing?.emphasisStyles.orEmpty()
        val next = if (style in current) current - style else current + style
        when {
            existing == null && next.isNotEmpty() -> annotationStore.createEmphasis(
                sourceId = sourceId,
                itemId = itemId,
                cfi = annotation.cfi,
                textSnippet = annotation.textSnippet,
                chapterHref = annotation.chapterHref,
                styles = next,
                textBefore = annotation.textBefore,
                textAfter = annotation.textAfter,
                spineIndex = annotation.spineIndex,
                progression = annotation.progression,
                originFontFamily = annotation.originFontFamily ?: originFontFamily(),
            )
            existing != null && next.isEmpty() -> annotationStore.delete(existing.id)
            existing != null -> annotationStore.updateEmphasisStyles(existing.id, next)
        }
    }

    /** The emphasis styles currently layered on [annotation]'s range. */
    fun emphasisStylesFor(annotation: Annotation): Set<EmphasisStyle> = annotations()
        .filter { it.type == AnnotationEntity.TYPE_EMPHASIS && it.cfi == annotation.cfi }
        .flatMap { it.emphasisStyles.orEmpty() }
        .toSet()

    /**
     * The bookmark covering the page the reader is on, or null.
     *
     * The window is [bookmarkEpsFor], the same shared derivation that lights Android's corner
     * ribbon — so "the ribbon is lit" and "tapping it removes a bookmark" can never disagree.
     */
    fun bookmarkOnCurrentPage(): Annotation? {
        val position = navigator.snapshotPosition() ?: return null
        val hrefNorm = normalizeEpubHref(position.href)
        val eps = bookmarkEpsFor(
            orientation = orientation(),
            spineCounts = spineHrefs() to spinePositionCounts(),
            viewportFractionByHref = emptyMap(),
            chapterHref = hrefNorm,
        )
        return annotations().firstOrNull { a ->
            a.type == AnnotationEntity.TYPE_BOOKMARK &&
                normalizeEpubHref(a.chapterHref) == hrefNorm &&
                abs(a.progression - position.progression) <= eps
        }
    }

    /**
     * Bookmark the current page, or remove the bookmark already on it.
     *
     * Captures a `fragmentAnchor` — the id of the first block element visible in the current
     * column — with the shared [ColumnSnap.CAPTURE_PAGE_FRAGMENT_ANCHOR_JS], which is what makes
     * navigating back to the bookmark land on the paragraph rather than on a progression
     * estimate (PR #671). The script answers null for a scrolling document, and a null anchor is
     * the legacy shape every consumer already handles.
     *
     * Returns true when a bookmark now exists on this page.
     */
    suspend fun toggleBookmark(): Boolean {
        val existing = bookmarkOnCurrentPage()
        if (existing != null) {
            annotationStore.delete(existing.id)
            return false
        }
        val position = navigator.snapshotPosition() ?: return false
        val href = position.href
        val spineIndex = spineIndexOf(href) ?: 0
        val progression = position.progression.toDouble()
        val percent = ((position.totalProgression ?: position.progression) * 100)
            .roundToInt()
            .coerceIn(0, 100)
        annotationStore.createBookmark(
            sourceId = sourceId,
            itemId = itemId,
            // The page locator verbatim, which is what Android stores too
            // (`locator.toPayload().ebookLocation`). It carries no `!`, so
            // `annotationDecorationLocatorJson` resolves an empty CFI fragment for it and the
            // decoration anchors on `fragmentAnchor` / `progression` instead — which is the
            // right order of preference for a bookmark anyway.
            cfi = position.locatorJson,
            // A bookmark has no selected text. Android stores the 200 characters preceding the
            // page top when Readium happens to supply them; the iOS page locator carries no
            // `text` block at all, so the title below is the panel's whole label.
            textSnippet = "",
            chapterHref = href,
            spineIndex = spineIndex,
            progression = progression,
            bookmarkTitle = "$percent%",
            originFontFamily = originFontFamily(),
            fragmentAnchor = navigator.capturePageFragmentAnchor(),
        )
        return true
    }

    /** Rename a bookmark from the annotations panel. */
    suspend fun renameBookmark(id: String, title: String) {
        annotationStore.renameBookmark(id, title)
    }

    private fun spineIndexOf(href: String): Int? = spineHrefs()
        .indexOfFirst { normalizeEpubHref(it) == normalizeEpubHref(href) }
        .takeIf { it >= 0 }

    /**
     * The publisher's body font, so the Annotations View renders each excerpt in the face it
     * came from (issue #484).
     *
     * Android reads the computed font of the *selection's* start element from a JS stash it
     * maintains on `selectionchange`. iOS has no such stash, so it reads the body font, which is
     * the same value Android falls back to when its stash is empty. Only when even that fails
     * does it stamp [FALLBACK_ORIGIN_FONT_FAMILY], the sentinel the store's heal pass rewrites
     * on the next open.
     */
    private suspend fun originFontFamily(): String =
        navigator.computedBodyFontFamily()?.takeIf { it.isNotBlank() } ?: FALLBACK_ORIGIN_FONT_FAMILY
}
