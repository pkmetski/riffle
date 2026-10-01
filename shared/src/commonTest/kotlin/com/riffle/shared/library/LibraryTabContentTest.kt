package com.riffle.shared.library

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescriptionExactly
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.domain.AnnotatedBook
import com.riffle.core.models.CatalogPlaylist
import com.riffle.core.models.Collection
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.core.models.Series
import com.riffle.feature.designsystem.TestTags
import com.riffle.feature.library.AnnotationsListUiState
import com.riffle.feature.library.LibraryProjection
import com.riffle.feature.library.LibrarySectionType
import com.riffle.feature.library.tabIndexForAnnotations
import com.riffle.feature.library.tabIndexForPlaylists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
                onPlaylistSelected = {},
                onSearchAnnotations = {},
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
                onPlaylistSelected = {},
                onSearchAnnotations = {},
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
                onPlaylistSelected = {},
                onSearchAnnotations = {},
            )
        }

        onNodeWithText(ANNOTATIONS_EMPTY_LABEL).assertIsDisplayed()
    }

    /**
     * #1072 §1 — `AnnotationSearchViewModel` had no iOS binding *and* no iOS surface could have
     * opened it: Android reaches the results screen from its library search bar's "Show all"
     * affordance and iOS has no search bar at all. This field is the whole entry point, so
     * removing it (the revert) makes the screen unreachable again with nothing else failing.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun theAnnotationsTabSearchFieldOpensTheResultsScreen() = runComposeUiTest {
        var searched: String? = null
        setContent {
            LibraryTabContent(
                selectedTab = tabIndexForAnnotations(),
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
                onPlaylistSelected = {},
                onSearchAnnotations = { searched = it },
            )
        }

        onNodeWithTag(TestTags.ANNOTATIONS_SEARCH_FIELD).performTextInput("margin")
        onNodeWithTag("annotation-search-submit").performClick()

        assertEquals("margin", searched)
    }

    /**
     * A blank query is a dead end — the ViewModel short-circuits it and the results screen can
     * only say "no annotations for """ — so submitting nothing must not navigate.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun submittingABlankAnnotationQueryDoesNotNavigate() = runComposeUiTest {
        var searched: String? = null
        setContent {
            LibraryTabContent(
                selectedTab = tabIndexForAnnotations(),
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
                onPlaylistSelected = {},
                onSearchAnnotations = { searched = it },
            )
        }

        onNodeWithTag("annotation-search-submit").performClick()

        assertNull(searched)
    }

    /**
     * The Playlists tab (index 6) fell through the `when`'s `else` and silently rendered the Home
     * tab (#1071 §8). Nothing pinned the tab-index → content routing, only the index constant and
     * the tab's visibility — which is why extracting the `when` into [LibraryTabContent] was able
     * to drop the branch again without a single test noticing.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun playlistsTabRendersThePlaylistsAndNotTheHomeTab() = runComposeUiTest {
        setContent {
            LibraryTabContent(
                selectedTab = tabIndexForPlaylists(),
                projection = LibraryProjection.Empty,
                playlists = listOf(
                    CatalogPlaylist(id = "p1", rootId = "lib1", name = "Evening Queue", bookCount = 4),
                ),
                annotationsState = AnnotationsListUiState(loading = false, books = emptyList()),
                coversAreSquare = false,
                linkedItemIds = emptySet(),
                onItemSelected = {},
                onAnnotatedBookSelected = { _, _ -> },
                onSeriesSelected = {},
                onCollectionSelected = {},
                onSectionSeeMore = {},
                onPlaylistSelected = {},
                onSearchAnnotations = {},
            )
        }

        onNodeWithText("Evening Queue").assertIsDisplayed()
        // "4 items", not the "4 book(s)" the deleted iOS-only copy of this tab printed: the tab
        // body is now :feature:library-ui's PlaylistsTabContent, the same one Android renders,
        // and its count line comes from the shared `playlistItemCountLabel`. The claim this test
        // pins — index 6 routes to the playlists body and not the Home tab — is unchanged.
        onNodeWithText("4 items").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun playlistsTabShowsItsOwnEmptyStateRatherThanTheHomeTab() = runComposeUiTest {
        setContent {
            LibraryTabContent(
                selectedTab = tabIndexForPlaylists(),
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
                onPlaylistSelected = {},
                onSearchAnnotations = {},
            )
        }

        // Android's copy, for the same reason as the count line above: one empty state for both
        // hosts rather than two wordings.
        onNodeWithText("No playlists yet. Create one from any item.").assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun homeTabHidesRecentlyAddedSectionWhenShowRecentlyAddedIsFalse() = runComposeUiTest {
        // Web sources (O'Reilly, etc.) set showRecentlyAdded = false. Items arrive in
        // library_items only when the user opens them, so the section would reflect open
        // history rather than anything the source published — misleading.
        val recentlyAddedItem = LibraryItem(
            id = "ra-1", libraryId = "lib", title = "New Book", author = "Author",
            coverUrl = null, readingProgress = 0f, isCached = false, isDownloaded = false,
            ebookFormat = EbookFormat.Epub,
        )
        setContent {
            LibraryTabContent(
                selectedTab = 0,
                projection = LibraryProjection.Empty.copy(recentlyAdded = listOf(recentlyAddedItem)),
                playlists = emptyList(),
                annotationsState = AnnotationsListUiState(loading = false, books = emptyList()),
                coversAreSquare = false,
                linkedItemIds = emptySet(),
                showRecentlyAdded = false,
                onItemSelected = {},
                onAnnotatedBookSelected = { _, _ -> },
                onSeriesSelected = {},
                onCollectionSelected = {},
                onSectionSeeMore = {},
                onPlaylistSelected = {},
                onSearchAnnotations = {},
            )
        }

        onNodeWithTag(sectionHeaderTag(LibrarySectionType.RECENTLY_ADDED)).assertDoesNotExist()
    }

    /**
     * The series tab must show series names in a grid (backed by covers), not a plain text list.
     * The series name must be visible so the grid tile is meaningful even when a cover is absent.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun seriesTabShowsSeriesNameInGrid() = runComposeUiTest {
        val series = Series(id = "s1", libraryId = "lib", name = "The Expanse", coverUrl = null, bookCount = 9)
        setContent {
            LibraryTabContent(
                selectedTab = 3,
                projection = LibraryProjection.Empty.copy(series = listOf(series)),
                playlists = emptyList(),
                annotationsState = AnnotationsListUiState(loading = false, books = emptyList()),
                coversAreSquare = false,
                linkedItemIds = emptySet(),
                onItemSelected = {},
                onAnnotatedBookSelected = { _, _ -> },
                onSeriesSelected = {},
                onCollectionSelected = {},
                onSectionSeeMore = {},
                onPlaylistSelected = {},
                onSearchAnnotations = {},
            )
        }

        // The tile is one clickable element labelled exactly the name. The caption Text is kept
        // out of the merge on purpose: Compose's iOS accessibility bridge concatenates a merged
        // node's contentDescription and text, so a merged caption yields "The Expanse, The Expanse" and
        // XCUITest's app.buttons["The Expanse"] no longer matches (see BookCoverTile).
        onNode(hasContentDescriptionExactly("The Expanse")).assertIsDisplayed().assertHasClickAction()
        onNodeWithText("The Expanse").assertDoesNotExist()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tappingSeriesInGridNavigatesToThatSeries() = runComposeUiTest {
        val series = Series(id = "s1", libraryId = "lib", name = "Foundation", coverUrl = null, bookCount = 5)
        var selected: Series? = null
        setContent {
            LibraryTabContent(
                selectedTab = 3,
                projection = LibraryProjection.Empty.copy(series = listOf(series)),
                playlists = emptyList(),
                annotationsState = AnnotationsListUiState(loading = false, books = emptyList()),
                coversAreSquare = false,
                linkedItemIds = emptySet(),
                onItemSelected = {},
                onAnnotatedBookSelected = { _, _ -> },
                onSeriesSelected = { selected = it },
                onCollectionSelected = {},
                onSectionSeeMore = {},
                onPlaylistSelected = {},
                onSearchAnnotations = {},
            )
        }

        onNode(hasContentDescriptionExactly("Foundation")).performClick()
        assertEquals(series, selected)
    }

    /**
     * The Collections tab must show collection names in a grid with a tile per collection,
     * not a plain text list. The collection name must be visible so the tile is meaningful
     * even when covers are absent.
     *
     * Before this fix, iOS rendered a plain `LazyColumn` with only the collection name in a `Text`
     * row. The regression to pin: tab index 4 must route to the grid body, and the grid must show
     * the collection name so the tile is meaningful. Reverting the change back to `LazyColumn` with
     * no `collectionGridTile` tag makes the second assertion go red.
     */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun collectionsTabShowsCollectionNameInGridTile() = runComposeUiTest {
        val collection = Collection(id = "c1", libraryId = "lib1", name = "Fantasy Favourites", bookCount = 3)
        setContent {
            LibraryTabContent(
                selectedTab = 4,
                projection = LibraryProjection.Empty.copy(collections = listOf(collection)),
                playlists = emptyList(),
                annotationsState = AnnotationsListUiState(loading = false, books = emptyList()),
                coversAreSquare = false,
                linkedItemIds = emptySet(),
                onItemSelected = {},
                onAnnotatedBookSelected = { _, _ -> },
                onSeriesSelected = {},
                onCollectionSelected = {},
                onSectionSeeMore = {},
                onPlaylistSelected = {},
                onSearchAnnotations = {},
            )
        }

        // The tile is one clickable element labelled exactly the name. The caption Text is kept
        // out of the merge on purpose: Compose's iOS accessibility bridge concatenates a merged
        // node's contentDescription and text, so a merged caption yields "Fantasy Favourites, Fantasy Favourites" and
        // XCUITest's app.buttons["Fantasy Favourites"] no longer matches (see BookCoverTile).
        onNode(hasContentDescriptionExactly("Fantasy Favourites")).assertIsDisplayed().assertHasClickAction()
        onNodeWithText("Fantasy Favourites").assertDoesNotExist()
        // The tile tag proves the grid path rendered, not the plain list path.
        onNodeWithTag(TestTags.collectionGridTile("c1")).assertIsDisplayed()
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun tappingCollectionInGridNavigatesToThatCollection() = runComposeUiTest {
        val collection = Collection(id = "c1", libraryId = "lib1", name = "Sci-Fi Picks", bookCount = 5)
        var selected: Collection? = null
        setContent {
            LibraryTabContent(
                selectedTab = 4,
                projection = LibraryProjection.Empty.copy(collections = listOf(collection)),
                playlists = emptyList(),
                annotationsState = AnnotationsListUiState(loading = false, books = emptyList()),
                coversAreSquare = false,
                linkedItemIds = emptySet(),
                onItemSelected = {},
                onAnnotatedBookSelected = { _, _ -> },
                onSeriesSelected = {},
                onCollectionSelected = { selected = it },
                onSectionSeeMore = {},
                onPlaylistSelected = {},
                onSearchAnnotations = {},
            )
        }

        onNodeWithTag(TestTags.collectionGridTile("c1")).performClick()
        assertEquals(collection, selected, "Tapping a collection tile must fire onCollectionSelected")
    }

    /** The To Read tab's empty copy, which the same merge nearly reverted to the tab's title. */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun toReadTabShowsItsEmptyStateCopy() = runComposeUiTest {
        setContent {
            LibraryTabContent(
                selectedTab = 1,
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
                onPlaylistSelected = {},
                onSearchAnnotations = {},
            )
        }

        onNodeWithText("Nothing in To Read").assertIsDisplayed()
    }
}
