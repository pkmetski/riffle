package com.riffle.core.data

import com.riffle.core.database.BookHighlightSummary
import com.riffle.core.database.LibraryItemEntity
import com.riffle.core.models.EbookFormat
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
        ebookFormat: String = EbookFormat.Epub.toStorageString(),
    ) = LibraryItemEntity(
        sourceId = "S1",
        id = id,
        libraryId = "lib1",
        title = title,
        author = author,
        coverUrl = coverUrl,
        readingProgress = 0f,
        ebookFormat = ebookFormat,
        addedAt = 0L,
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

    // Fix C regression: a HIGHLIGHT row on a PDF item (a real DB state observed in the wild — the
    // Task 1 DAO query filters only by type/deleted, not format) must not surface in the Annotations
    // list per the plan's Q11 "EPUB-only for v1". The DAO itself stays format-agnostic (so PDF
    // highlights can join later without rework); this repo layer is where the exclusion lives.
    @Test
    fun excludesPdfItemsFromAnnotatedBooks() = runTest {
        val annDao = FakeAnnotationDao().apply {
            emitBooksWithHighlights(
                "S1",
                listOf(
                    BookHighlightSummary("A", 2, 200), // EPUB, kept
                    BookHighlightSummary("P", 1, 300), // PDF, excluded
                ),
            )
        }
        val libDao = FakeLibraryItemDao().apply {
            emit(
                "S1",
                listOf(
                    libItem("A", title = "Alpha", author = "AA", coverUrl = "urlA"),
                    libItem(
                        "P",
                        title = "PDF Book",
                        author = "PP",
                        coverUrl = "urlP",
                        ebookFormat = EbookFormat.Pdf.toStorageString(),
                    ),
                ),
            )
        }
        val repo = AnnotationsLibraryRepositoryImpl(annDao, libDao)

        val result = repo.observeAnnotatedBooks("S1").first()

        assertEquals(listOf("A"), result.map { it.itemId })
    }

    // Step A (per-Library scoping): the new (sourceId, libraryId) overload must only surface books
    // whose library_items row lives in the requested library. A book with a live highlight but no
    // matching library_items row can't be attributed to any specific library, so — unlike the
    // per-server variant, which keeps such orphans as text-only cards — it must be excluded here.
    @Test
    fun libraryScopedObserveExcludesBooksFromOtherLibraries() = runTest {
        val annDao = FakeAnnotationDao().apply {
            emitBooksWithHighlights(
                "S1",
                listOf(
                    BookHighlightSummary("A", 2, 200), // lib1
                    BookHighlightSummary("B", 1, 300), // lib2
                ),
            )
        }
        val libDao = FakeLibraryItemDao().apply {
            upsertAll(
                listOf(
                    libItem("A", title = "Alpha", author = "AA", coverUrl = "urlA"),
                    libItem("B", title = "Bravo", author = "BB", coverUrl = "urlB").copy(libraryId = "lib2"),
                ),
            )
        }
        val repo = AnnotationsLibraryRepositoryImpl(annDao, libDao)

        val result = repo.observeAnnotatedBooks("S1", "lib1").first()

        assertEquals(listOf("A"), result.map { it.itemId })
    }

    @Test
    fun libraryScopedObserveExcludesOrphanedRowsWithNoLibraryItemsMatch() = runTest {
        val annDao = FakeAnnotationDao().apply {
            emitBooksWithHighlights(
                "S1",
                listOf(
                    BookHighlightSummary("A", 2, 200), // lib1, has library_items row
                    BookHighlightSummary("Z", 4, 999), // no library_items row at all → excluded
                ),
            )
        }
        val libDao = FakeLibraryItemDao().apply {
            upsertAll(
                listOf(
                    libItem("A", title = "Alpha", author = "AA", coverUrl = "urlA"),
                ),
            )
        }
        val repo = AnnotationsLibraryRepositoryImpl(annDao, libDao)

        val result = repo.observeAnnotatedBooks("S1", "lib1").first()

        assertEquals(listOf("A"), result.map { it.itemId })
    }

    @Test
    fun libraryScopedObserveIncludesMatchingLibraryAndServer() = runTest {
        val annDao = FakeAnnotationDao().apply {
            emitBooksWithHighlights(
                "S1",
                listOf(
                    BookHighlightSummary("A", 2, 200),
                    BookHighlightSummary("B", 1, 300),
                ),
            )
        }
        val libDao = FakeLibraryItemDao().apply {
            upsertAll(
                listOf(
                    libItem("A", title = "Alpha", author = "AA", coverUrl = "urlA"),
                    libItem("B", title = "Bravo", author = "BB", coverUrl = "urlB"),
                ),
            )
        }
        val repo = AnnotationsLibraryRepositoryImpl(annDao, libDao)

        val result = repo.observeAnnotatedBooks("S1", "lib1").first()

        assertEquals(listOf("B", "A"), result.map { it.itemId })
    }
}
