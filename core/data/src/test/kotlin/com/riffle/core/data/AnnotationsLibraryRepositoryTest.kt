package com.riffle.core.data

import com.riffle.core.database.BookHighlightSummary
import com.riffle.core.database.LibraryItemEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AnnotationsLibraryRepositoryTest {

    private fun libItem(
        id: String,
        title: String,
        author: String,
        coverUrl: String?,
    ) = LibraryItemEntity(
        serverId = "S1",
        id = id,
        libraryId = "lib1",
        title = title,
        author = author,
        coverUrl = coverUrl,
        readingProgress = 0f,
    )

    @Test
    fun mergesSummariesWithLibraryItemsAndSortsByLatestUpdatedAt() = runTest {
        val annDao = FakeAnnotationDao().apply {
            emitBooksWithHighlights(
                "S1",
                listOf(
                    BookHighlightSummary("A", 2, 200),
                    BookHighlightSummary("B", 1, 300),
                    BookHighlightSummary("Z", 4, 999), // no library_items row → text-only card
                ),
            )
        }
        val libDao = FakeLibraryItemDao().apply {
            emit(
                "S1",
                listOf(
                    libItem("A", title = "Alpha", author = "AA", coverUrl = "urlA"),
                    libItem("B", title = "Bravo", author = "BB", coverUrl = "urlB"),
                ),
            )
        }
        val repo = AnnotationsLibraryRepositoryImpl(annDao, libDao)

        val result = repo.observeAnnotatedBooks("S1").first()

        assertEquals(listOf("Z", "B", "A"), result.map { it.itemId })
        assertEquals(AnnotatedBook("S1", "Z", null, null, null, 4, 999), result[0])
        assertEquals("Alpha", result[2].title)
        assertEquals(2, result[2].highlightCount)
    }
}
