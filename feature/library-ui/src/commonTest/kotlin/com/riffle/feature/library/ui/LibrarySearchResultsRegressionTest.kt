package com.riffle.feature.library.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.models.Annotation
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Series
import com.riffle.feature.library.AnnotationSearchResult
import kotlin.test.Test

/**
 * Regression tests for SearchResultsContent, which was dropped when the shared screen swap
 * (#1197) replaced the Android-only screen. The original screen showed separate sections for
 * Books, Series, and Collections, and capped annotations at ANNOTATION_PREVIEW_CAP.
 */
@OptIn(ExperimentalTestApi::class)
class LibrarySearchResultsRegressionTest {

    private fun item(id: String = "item-1", title: String = "A Book") = LibraryItem(
        id = id,
        sourceId = "src-1",
        libraryId = "lib-1",
        title = title,
        author = "Author",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
    )

    private fun series(id: String = "s1", name: String = "A Series") = Series(
        id = id,
        name = name,
        coverUrl = null,
        bookCount = 2,
        libraryId = "lib-1",
    )

    private fun annotation(id: String = "ann-1", snippet: String = "text") = AnnotationSearchResult(
        annotation = Annotation(
            id = id,
            sourceId = "src-1",
            itemId = "item-1",
            type = "HIGHLIGHT",
            cfi = "epubcfi(/6/2!/4/1:0)",
            color = "#FFD700",
            note = null,
            textSnippet = snippet,
            textBefore = "",
            textAfter = "",
            chapterHref = "chapter1.html",
            spineIndex = 0,
            progression = 0.0,
            bookmarkTitle = "",
            createdAt = 0L,
            updatedAt = 0L,
        ),
        bookTitle = "A Book",
        bookCoverUrl = null,
    )

    // ── "No results" only when all empty ──────────────────────────────────────

    @Test
    fun noResultsShownWhenAllEmpty() = runComposeUiTest {
        setContent {
            SearchResultsContent(
                query = "xyz",
                filteredSeries = emptyList(),
                filteredCollections = emptyList(),
                filteredItems = emptyList(),
                annotations = emptyList(),
                audiobookBookmarks = emptyList(),
                seriesCoverUrls = emptyMap(),
                token = "",
                linkedItemIds = emptySet(),
                onSeriesSelected = {},
                onCollectionSelected = {},
                onItemSelected = {},
                onAnnotationSelected = {},
                onAudiobookBookmarkSelected = {},
                onShowAllAnnotations = {},
            )
        }
        onNodeWithText("No results", substring = true).assertExists()
    }

    @Test
    fun noResultsNotShownWhenBooksPresent() = runComposeUiTest {
        setContent {
            SearchResultsContent(
                query = "book",
                filteredSeries = emptyList(),
                filteredCollections = emptyList(),
                filteredItems = listOf(item()),
                annotations = emptyList(),
                audiobookBookmarks = emptyList(),
                seriesCoverUrls = emptyMap(),
                token = "",
                linkedItemIds = emptySet(),
                onSeriesSelected = {},
                onCollectionSelected = {},
                onItemSelected = {},
                onAnnotationSelected = {},
                onAudiobookBookmarkSelected = {},
                onShowAllAnnotations = {},
            )
        }
        onNodeWithText("A Book").assertIsDisplayed()
        onNodeWithText("No results", substring = true).assertDoesNotExist()
    }

    // ── Books section header ───────────────────────────────────────────────────

    @Test
    fun booksSectionHeaderAppearsWhenBooksPresent() = runComposeUiTest {
        setContent {
            SearchResultsContent(
                query = "q",
                filteredSeries = emptyList(),
                filteredCollections = emptyList(),
                filteredItems = listOf(item()),
                annotations = emptyList(),
                audiobookBookmarks = emptyList(),
                seriesCoverUrls = emptyMap(),
                token = "",
                linkedItemIds = emptySet(),
                onSeriesSelected = {},
                onCollectionSelected = {},
                onItemSelected = {},
                onAnnotationSelected = {},
                onAudiobookBookmarkSelected = {},
                onShowAllAnnotations = {},
            )
        }
        onNodeWithText("Books").assertIsDisplayed()
    }

    // ── Series section header ─────────────────────────────────────────────────

    @Test
    fun seriesSectionHeaderAppearsWhenSeriesPresent() = runComposeUiTest {
        setContent {
            SearchResultsContent(
                query = "q",
                filteredSeries = listOf(series()),
                filteredCollections = emptyList(),
                filteredItems = emptyList(),
                annotations = emptyList(),
                audiobookBookmarks = emptyList(),
                seriesCoverUrls = emptyMap(),
                token = "",
                linkedItemIds = emptySet(),
                onSeriesSelected = {},
                onCollectionSelected = {},
                onItemSelected = {},
                onAnnotationSelected = {},
                onAudiobookBookmarkSelected = {},
                onShowAllAnnotations = {},
            )
        }
        onNodeWithText("A Series").assertIsDisplayed()
    }

    // ── Annotations cap and "Show all" ───────────────────────────────────────

    @Test
    fun showAllAbsentWhenAnnotationsAtOrBelowCap() = runComposeUiTest {
        val anns = (1..ANNOTATION_PREVIEW_CAP).map { annotation(id = "ann-$it", snippet = "snippet $it") }
        setContent {
            SearchResultsContent(
                query = "q",
                filteredSeries = emptyList(),
                filteredCollections = emptyList(),
                filteredItems = emptyList(),
                annotations = anns,
                audiobookBookmarks = emptyList(),
                seriesCoverUrls = emptyMap(),
                token = "",
                linkedItemIds = emptySet(),
                onSeriesSelected = {},
                onCollectionSelected = {},
                onItemSelected = {},
                onAnnotationSelected = {},
                onAudiobookBookmarkSelected = {},
                onShowAllAnnotations = {},
            )
        }
        onNodeWithText("Show all ${ANNOTATION_PREVIEW_CAP} annotations").assertDoesNotExist()
    }

    @Test
    fun showAllAppearsWhenAnnotationsExceedCap() = runComposeUiTest {
        val total = ANNOTATION_PREVIEW_CAP + 1
        val anns = (1..total).map { annotation(id = "ann-$it", snippet = "snippet $it") }
        setContent {
            SearchResultsContent(
                query = "q",
                filteredSeries = emptyList(),
                filteredCollections = emptyList(),
                filteredItems = emptyList(),
                annotations = anns,
                audiobookBookmarks = emptyList(),
                seriesCoverUrls = emptyMap(),
                token = "",
                linkedItemIds = emptySet(),
                onSeriesSelected = {},
                onCollectionSelected = {},
                onItemSelected = {},
                onAnnotationSelected = {},
                onAudiobookBookmarkSelected = {},
                onShowAllAnnotations = {},
            )
        }
        onNodeWithText("Show all $total annotations").assertIsDisplayed()
    }
}
