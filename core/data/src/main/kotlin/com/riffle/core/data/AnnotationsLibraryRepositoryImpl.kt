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
}
