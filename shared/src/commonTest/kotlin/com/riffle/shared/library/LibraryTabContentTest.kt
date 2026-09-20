package com.riffle.shared.library

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.domain.AnnotatedBook
import com.riffle.feature.library.AnnotationsListUiState
import com.riffle.feature.library.LibraryProjection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression for the structurally-empty Annotations tab on iOS (#1071 §9).
 *
 * The tab used to render `LibraryProjection.annotations`, which `LibraryFilterEngine` gates on a
 * non-blank search query. iOS has no search field, so the query is always blank and the list was
 * always empty — while tab *visibility* came from `observeAnnotatedBooks`, so the tab button was
 * there for anyone who had highlights.
 *
 * These render the real composable with the projection empty (what a blank query produces) and an
 * `AnnotationsListUiState` carrying one book, exactly as the two flows behave on a real device.
 */
class LibraryTabContentTest {

    private val annotatedBook = AnnotatedBook(
        sourceId = "source1",
        itemId = "item1",
        title = "Annotated Title",
        author = "Some Author",
        coverUrl = null,
        highlightCount = 3,
        latestUpdatedAt = 0L,
    )

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun annotationsTabRendersAnnotatedBooksWhileTheSearchProjectionIsEmpty() = runComposeUiTest {
        setContent {
            LibraryTabContent(
                selectedTab = tabIndexForAnnotations(),
                // Blank query ⇒ LibraryFilterEngine emits an empty annotations list.
                projection = LibraryProjection.Empty,
                playlists = emptyList(),
                annotationsState = AnnotationsListUiState(loading = false, books = listOf(annotatedBook)),
                coversAreSquare = false,
                linkedItemIds = emptySet(),
                onItemSelected = {},
                onAnnotatedBookSelected = { _, _ -> },
                onSeriesSelected = {},
                onCollectionSelected = {},
                onSectionSeeMore = {},
            )
        }

        onNodeWithText("Annotated Title").assertIsDisplayed()
        onNodeWithText("Some Author").assertIsDisplayed()
        onNodeWithText("3").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun annotationsTabOpensTheBookItWasTapped() = runComposeUiTest {
        var opened: Pair<String, String>? = null
        setContent {
            LibraryTabContent(
                selectedTab = tabIndexForAnnotations(),
                projection = LibraryProjection.Empty,
                playlists = emptyList(),
                annotationsState = AnnotationsListUiState(loading = false, books = listOf(annotatedBook)),
                coversAreSquare = false,
                linkedItemIds = emptySet(),
                onItemSelected = {},
                onAnnotatedBookSelected = { sourceId, itemId -> opened = sourceId to itemId },
                onSeriesSelected = {},
                onCollectionSelected = {},
                onSectionSeeMore = {},
            )
        }

        onNodeWithText("Annotated Title").performClick()

        assertEquals("source1" to "item1", opened, "Tapping an annotated book must open its detail sheet")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun annotationsTabShowsTheEmptyStateWhenThereAreNoAnnotatedBooks() = runComposeUiTest {
        setContent {
            LibraryTabContent(
                selectedTab = tabIndexForAnnotations(),
                projection = LibraryProjection.Empty,
                playlists = emptyList(),
                annotationsState = AnnotationsListUiState(loading = false, books = emptyList()),
                coversAreSquare = false,
                linkedItemIds = emptySet(),
                onItemSelected = {},
                onAnnotatedBookSelected = { _, _ -> },
                onSeriesSelected = {},
                onCollectionSelected = {},
                onSectionSeeMore = {},
            )
        }

        onNodeWithText(ANNOTATIONS_EMPTY_LABEL).assertIsDisplayed()
    }
}
