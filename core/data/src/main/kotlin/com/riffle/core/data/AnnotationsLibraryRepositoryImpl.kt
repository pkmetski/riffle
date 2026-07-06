package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.LibraryItemDao
import com.riffle.core.domain.EbookFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class AnnotationsLibraryRepositoryImpl @Inject constructor(
    private val annotationDao: AnnotationDao,
    private val libraryItemDao: LibraryItemDao,
) : AnnotationsLibraryRepository {

    override fun observeAnnotatedBooks(serverId: String): Flow<List<AnnotatedBook>> =
        combine(
            annotationDao.observeBooksWithHighlights(serverId),
            libraryItemDao.observeByServer(serverId).map { rows ->
                rows.associateBy { it.id }
            },
        ) { summaries, itemsById ->
            summaries
                // EPUB-only for v1 (plan Q11): the DAO query stays format-agnostic so PDF highlights
                // can join later without rework, but this repo layer excludes them for now. A missing
                // library_items row (orphaned book) is kept — Q9's text-only fallback design still
                // applies, and an unknown format is not evidence the book is a PDF.
                .filter { s ->
                    val item = itemsById[s.itemId]
                    item == null || EbookFormat.from(item.ebookFormat) == EbookFormat.Epub
                }
                .map { s ->
                    val item = itemsById[s.itemId]
                    AnnotatedBook(
                        serverId = serverId,
                        itemId = s.itemId,
                        title = item?.title,
                        author = item?.author,
                        coverUrl = item?.coverUrl,
                        highlightCount = s.highlightCount,
                        latestUpdatedAt = s.latestUpdatedAt,
                    )
                }.sortedByDescending { it.latestUpdatedAt }
        }

    override fun observeAnnotatedBooks(serverId: String, libraryId: String): Flow<List<AnnotatedBook>> =
        combine(
            annotationDao.observeBooksWithHighlights(serverId),
            libraryItemDao.observeByLibraryId(serverId, libraryId).map { rows ->
                rows.associateBy { it.id }
            },
        ) { summaries, itemsById ->
            summaries
                // Stricter than the per-server overload: a summary with no matching library_items
                // row (orphaned, or belonging to a different library) is excluded entirely, since
                // there is no way to attribute it to this specific library.
                .mapNotNull { s -> itemsById[s.itemId]?.let { item -> s to item } }
                .filter { (_, item) -> EbookFormat.from(item.ebookFormat) == EbookFormat.Epub }
                .map { (s, item) ->
                    AnnotatedBook(
                        serverId = serverId,
                        itemId = s.itemId,
                        title = item.title,
                        author = item.author,
                        coverUrl = item.coverUrl,
                        highlightCount = s.highlightCount,
                        latestUpdatedAt = s.latestUpdatedAt,
                    )
                }.sortedByDescending { it.latestUpdatedAt }
        }
}
