package com.riffle.core.domain

import kotlinx.coroutines.flow.Flow

interface AnnotationStore {

    /** Live, non-deleted highlights for an ABS Library Item, oldest first. */
    fun observeHighlights(sourceId: String, itemId: String): Flow<List<Annotation>>

    /** Live, non-deleted bookmarks for an ABS Library Item, oldest first. */
    fun observeBookmarks(sourceId: String, itemId: String): Flow<List<Annotation>>

    /** Live, non-deleted highlights + bookmarks for an ABS Library Item, sorted by reading position. */
    fun observeAnnotations(sourceId: String, itemId: String): Flow<List<Annotation>>

    /** Live, non-deleted highlights + bookmarks across every item for a server, oldest first. */
    fun observeAnnotationsForSource(sourceId: String): Flow<List<Annotation>>

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
