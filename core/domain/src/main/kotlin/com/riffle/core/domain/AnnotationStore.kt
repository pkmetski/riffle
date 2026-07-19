package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow
import com.riffle.core.models.Annotation
import com.riffle.core.models.EmbeddedFigure
import com.riffle.core.models.EmphasisStyle

interface AnnotationStore {

    /** Live, non-deleted highlights for an ABS Library Item, oldest first. */
    fun observeHighlights(sourceId: String, itemId: String): Flow<List<Annotation>>

    /** Live, non-deleted bookmarks for an ABS Library Item, oldest first. */
    fun observeBookmarks(sourceId: String, itemId: String): Flow<List<Annotation>>

    /** Live, non-deleted highlights + bookmarks for an ABS Library Item, sorted by reading position. */
    fun observeAnnotations(sourceId: String, itemId: String): Flow<List<Annotation>>

    /** Live, non-deleted highlights + bookmarks across every item for a server, oldest first. */
    fun observeAnnotationsForSource(sourceId: String): Flow<List<Annotation>>

    /** Live, non-deleted emphasis annotations for an ABS Library Item, oldest first (ADR 0046).
     *  Default = empty flow so pre-existing test fakes that don't exercise emphasis aren't forced
     *  to implement it; the production impl [com.riffle.core.data.AnnotationStoreImpl] overrides
     *  with the real query. */
    fun observeEmphasis(sourceId: String, itemId: String): Flow<List<Annotation>> =
        kotlinx.coroutines.flow.emptyFlow()

    suspend fun createHighlight(
        sourceId: String,
        itemId: String,
        cfi: String,
        textSnippet: String,
        chapterHref: String,
        textBefore: String = "",
        textAfter: String = "",
        color: String = DEFAULT_COLOR,
        spineIndex: Int = 0,
        progression: Double = 0.0,
        /** Figures enclosed by the highlight's CFI range (Task 7), or null when none / not yet
         *  resolved. An empty list is normalized to null on the persisted entity. */
        embeddedFigures: List<EmbeddedFigure>? = null,
        /** Computed `font-family` at the source range's start element (per [issue 484](
         *  https://github.com/pkmetski/riffle/issues/484)). Non-null contract: the reader's
         *  write path must resolve this from the WebView's `getComputedStyle` at selection
         *  time and fall back to the book's body font — never null in production. */
        originFontFamily: String,
    ): Annotation

    suspend fun createBookmark(
        sourceId: String,
        itemId: String,
        cfi: String,
        textSnippet: String,
        chapterHref: String,
        spineIndex: Int,
        progression: Double,
        bookmarkTitle: String,
        /** Computed `font-family` at the bookmark's anchor element. Same non-null contract as
         *  [createHighlight]. */
        originFontFamily: String,
    ): Annotation

    /**
     * Create a `TYPE_IMAGE` annotation from a figure long-press. Exactly one of [imageHref] /
     * [imageSvg] should be non-null (raster figure vs. inline SVG) — [embeddedFigures] never
     * applies here, that field is TYPE_HIGHLIGHT-only.
     */
    suspend fun createImageAnnotation(
        sourceId: String,
        itemId: String,
        cfi: String,
        textSnippet: String,
        chapterHref: String,
        spineIndex: Int,
        progression: Double,
        imageHref: String?,
        imageSvg: String?,
        imageBytes: String?,
        color: String = DEFAULT_COLOR,
    ): Annotation

    /**
     * Backfill `originFontFamily` on every legacy null-font annotation on this book with the
     * book's computed body font (issue #484). Called once per Publication load once the reader
     * WebView has reported its `getComputedStyle(document.body).fontFamily`. Returns the number
     * of rows updated (0 on subsequent opens once the whole book is populated).
     */
    suspend fun backfillNullOriginFontFamily(
        sourceId: String,
        itemId: String,
        fontFamily: String,
    ): Int

    /**
     * Heal every row whose `originFontFamily` is the [sentinel] "no captured value" marker
     * (`"serif"` — see `EpubReaderViewModel.FALLBACK_ORIGIN_FONT_FAMILY`) to the freshly
     * reported publisher font [fontFamily]. Runs alongside [backfillNullOriginFontFamily] on
     * every first-per-session body-font report so books whose annotations were all created
     * before a live selection could feed the font stash (bookmarks, caption highlights, or a
     * selectionchange race) get corrected without the user having to create a new annotation.
     * No-op — and returns 0 — when [fontFamily] equals the sentinel value.
     */
    suspend fun healSentinelOriginFontFamily(
        sourceId: String,
        itemId: String,
        sentinel: String,
        fontFamily: String,
    ): Int

    /**
     * Legacy-annotation cleanup: rewrite an existing `TYPE_IMAGE` annotation into a
     * `TYPE_HIGHLIGHT` covering [textSnippet] with [figure] as its sole embeddedFigure. The
     * annotation's id is preserved so sync sees the change as an update (bumped updatedAt +
     * provenance), not a delete/create. Clears `imageHref` / `imageSvg` / `imageBytes` — those
     * columns are TYPE_HIGHLIGHT-illegal — since the figure now lives in `embeddedFigures`.
     * Returns the rewritten annotation, or `null` when [id] does not resolve to a live TYPE_IMAGE.
     */
    suspend fun upgradeImageToCaptionHighlight(
        id: String,
        cfi: String,
        textSnippet: String,
        textBefore: String,
        textAfter: String,
        figure: EmbeddedFigure,
    ): Annotation?

    /**
     * Union [newFigures] into an existing `TYPE_HIGHLIGHT`'s `embeddedFigures`, deduping by
     * href-filename (raster) and svg-prefix (inline SVG). Preserves the annotation's id, bumps
     * `updatedAt` + provenance, and returns the merged annotation. Returns `null` when [id] does
     * not resolve to a live TYPE_HIGHLIGHT. Used to absorb a figure long-press into a highlight
     * that already covers its caption text (deduping the "caption-highlight duplicate" class of
     * bug that shipped in the initial 2026-07-14 caption-annotation change).
     */
    suspend fun mergeFiguresIntoHighlight(
        id: String,
        newFigures: List<EmbeddedFigure>,
    ): Annotation?

    /**
     * Create a `TYPE_EMPHASIS` annotation (ADR 0046). The [styles] set MUST be non-empty — an
     * empty emphasis row is not a legal state; the ViewModel garbage-collects on sheet dismiss
     * before calling into the store. Range shape mirrors [createHighlight] (CFI range + snippet +
     * before/after context) so the auto-merge and same-range-write guards can be applied
     * identically to emphasis rows.
     */
    suspend fun createEmphasis(
        sourceId: String,
        itemId: String,
        cfi: String,
        textSnippet: String,
        chapterHref: String,
        styles: Set<EmphasisStyle>,
        textBefore: String = "",
        textAfter: String = "",
        spineIndex: Int = 0,
        progression: Double = 0.0,
        originFontFamily: String,
    ): Annotation = throw NotImplementedError("createEmphasis not implemented in this AnnotationStore")

    /**
     * Replace the styles set of a live `TYPE_EMPHASIS` row, bumping updatedAt + provenance so the
     * change propagates. Passing an empty set is a caller error — the ViewModel decides whether to
     * tombstone the row via [delete] or keep it alive during editing.
     */
    suspend fun updateEmphasisStyles(id: String, styles: Set<EmphasisStyle>): Unit =
        throw NotImplementedError("updateEmphasisStyles not implemented in this AnnotationStore")

    suspend fun delete(id: String)
    suspend fun recolor(id: String, color: String)
    suspend fun updateNote(id: String, note: String?)

    /** Update the user-editable title of a bookmark, bumping its updatedAt. */
    suspend fun renameBookmark(id: String, title: String)

    /** One-shot lookup of the annotation that exactly matches [cfi] for this item, or null. Used
     *  at open-from-library so the continuous reader can scroll to the annotation's DOM decoration
     *  (a precise post-reflow Y) instead of guessing from char-fraction × measured-WebView-height. */
    suspend fun findByItemAndCfi(sourceId: String, itemId: String, cfi: String): Annotation?

    /**
     * One-shot lookup of the live `TYPE_IMAGE` annotation already anchored to this figure in this
     * chapter, or null if the figure hasn't been annotated yet. Callers pass exactly one of
     * [imageHref] / [imageSvg] — mirroring [createImageAnnotation]'s raster-vs-svg split. Used to
     * dispatch edit-vs-create on a figure long-press instead of stacking a duplicate annotation.
     */
    suspend fun findImageAnnotationForFigure(
        sourceId: String,
        itemId: String,
        chapterHref: String,
        imageHref: String?,
        imageSvg: String?,
    ): Annotation?

    companion object {
        const val DEFAULT_COLOR = "yellow"
    }
}
