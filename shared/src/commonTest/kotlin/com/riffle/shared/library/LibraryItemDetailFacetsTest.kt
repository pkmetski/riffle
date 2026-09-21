package com.riffle.shared.library

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.feature.library.DetailCapabilities
import com.riffle.feature.library.FacetType
import com.riffle.feature.library.LibraryItemDetailUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #1072 §1/§3 — `FilteredBooksViewModel` was `:app`-only and had no iOS binding, and the iOS
 * detail sheet offered exactly two controls (Read, To Read) with no metadata at all. The facet
 * chips added here are the *only* iOS entry point into the facet drill-in, so removing any of
 * them silently makes the whole screen unreachable again with nothing failing.
 *
 * These drive the real `ReadyContent`, which runs on `:shared:iosSimulatorArm64Test`.
 */
@OptIn(ExperimentalTestApi::class)
class LibraryItemDetailFacetsTest {

    private fun ready(
        author: String = "Ursula Le Guin",
        genres: List<String> = listOf("Sci-Fi", "Classics"),
        publishedYear: String? = "1974",
        language: String? = "en",
        capabilities: DetailCapabilities = DetailCapabilities.Empty,
    ) = LibraryItemDetailUiState.Ready(
        item = LibraryItem(
            id = "item-1",
            sourceId = "src-1",
            libraryId = "lib-7",
            title = "A Title",
            author = author,
            coverUrl = null,
            readingProgress = 0f,
            isCached = false,
            isDownloaded = false,
            ebookFormat = EbookFormat.Epub,
            publishedYear = publishedYear,
            genres = genres,
            language = language,
        ),
        capabilities = capabilities,
    )

    @Test
    fun tappingAGenreChipDrillsIntoThatFacet() = runComposeUiTest {
        var selected: Pair<FacetType, String>? = null
        setContent {
            ReadyContent(
                state = ready(),
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                onAddToPlaylist = null,
                onFacet = { facet, value -> selected = facet to value },
            )
        }

        onNodeWithTag("facet-Sci-Fi").performClick()

        assertEquals(FacetType.GENRE to "Sci-Fi", selected)
    }

    @Test
    fun theYearAndLanguageAreAlsoFacets() = runComposeUiTest {
        val selected = mutableListOf<Pair<FacetType, String>>()
        setContent {
            ReadyContent(
                state = ready(),
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                onAddToPlaylist = null,
                onFacet = { facet, value -> selected += facet to value },
            )
        }

        onNodeWithTag("facet-1974").performClick()
        onNodeWithTag("facet-en").performClick()

        assertEquals(listOf(FacetType.YEAR to "1974", FacetType.LANGUAGE to "en"), selected)
    }

    /**
     * `facetMatches` compares one author at a time against a `", "`-split list, so a co-authored
     * book must offer each name separately — a single chip carrying both would match nothing.
     */
    @Test
    fun eachAuthorOfACoAuthoredBookIsItsOwnFacet() = runComposeUiTest {
        var selected: Pair<FacetType, String>? = null
        setContent {
            ReadyContent(
                state = ready(author = "Ann Leckie, Becky Chambers"),
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                onAddToPlaylist = null,
                onFacet = { facet, value -> selected = facet to value },
            )
        }

        onNodeWithText("Becky Chambers").assertIsDisplayed()
        onNodeWithTag("facet-Ann Leckie").performClick()

        assertEquals(FacetType.AUTHOR to "Ann Leckie", selected)
    }

    /** An item with no genres, year or language must not render stray blank chips. */
    @Test
    fun anItemWithNoMetadataRendersNoFacetChips() = runComposeUiTest {
        var selected: Pair<FacetType, String>? = null
        setContent {
            ReadyContent(
                state = ready(author = "", genres = emptyList(), publishedYear = null, language = null),
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                onAddToPlaylist = null,
                onFacet = { facet, value -> selected = facet to value },
            )
        }

        onNodeWithText("Read").assertIsDisplayed()
        assertNull(selected)
    }

    /**
     * `DetailCapabilities` gating was honoured on zero iOS controls. Add-to-playlist is the
     * first: a Source with no `PlaylistsCapability` must not offer the button.
     */
    @Test
    fun addToPlaylistIsHiddenWhenTheSourceCannotBackIt() = runComposeUiTest {
        setContent {
            ReadyContent(
                state = ready(),
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                onAddToPlaylist = null,
                onFacet = { _, _ -> },
            )
        }

        assertTrue(
            runCatching { onNodeWithTag("detail-add-to-playlist").assertIsDisplayed() }.isFailure,
            "the add-to-playlist button must not render when the capability is absent",
        )
    }

    @Test
    fun addToPlaylistIsOfferedWhenTheSourceSupportsIt() = runComposeUiTest {
        var opened = false
        setContent {
            ReadyContent(
                state = ready(capabilities = DetailCapabilities.All),
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                onAddToPlaylist = { opened = true },
                onFacet = { _, _ -> },
            )
        }

        onNodeWithTag("detail-add-to-playlist").performClick()

        assertTrue(opened)
    }
}
