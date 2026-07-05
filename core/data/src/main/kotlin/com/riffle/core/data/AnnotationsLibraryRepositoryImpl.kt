package com.riffle.core.data

import com.riffle.core.database.AnnotationDao
import com.riffle.core.database.LibraryItemDao
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
            summaries.map { s ->
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
