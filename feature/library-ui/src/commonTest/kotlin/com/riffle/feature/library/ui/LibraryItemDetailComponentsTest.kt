package com.riffle.feature.library.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.feature.library.DownloadState
import com.riffle.feature.library.FacetType
import com.riffle.feature.library.bookDownloadOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Regression tests for item-detail component behaviour, migrated from
 * shared/src/commonTest when LibraryItemDetailScreen moved from :shared to :feature:library-ui.
 *
 * - Facet metadata (author, genre, year, language) renders and fires callbacks via MetadataLines
 * - Download outcome state machine (bookDownloadOutcome pure function)
 */
@OptIn(ExperimentalTestApi::class)
class LibraryItemDetailComponentsTest {

    private fun item(
        author: String = "Ursula Le Guin",
        genres: List<String> = listOf("Sci-Fi", "Classics"),
        publishedYear: String? = "1974",
        language: String? = "en",
    ) = LibraryItem(
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
    )

    // ── MetadataLines — facet callbacks ──────────────────────────────────────

    @Test
    fun tappingAYearFacetFiresCallback() = runComposeUiTest {
        var selected: Pair<FacetType, String>? = null
        setContent {
            MetadataLines(item = item(), epubVersion = null, onFacet = { f, v -> selected = f to v })
        }
        onNodeWithText("1974").performClick()
        assertEquals(FacetType.YEAR to "1974", selected)
    }

    @Test
    fun tappingALanguageFacetFiresCallback() = runComposeUiTest {
        var selected: Pair<FacetType, String>? = null
        setContent {
            MetadataLines(item = item(), epubVersion = null, onFacet = { f, v -> selected = f to v })
        }
        onNodeWithText("en").performClick()
        assertEquals(FacetType.LANGUAGE to "en", selected)
    }

    @Test
    fun anItemWithNoMetadataRendersNoFacetRows() = runComposeUiTest {
        var selected: Pair<FacetType, String>? = null
        setContent {
            MetadataLines(
                item = item(genres = emptyList(), publishedYear = null, language = null),
                epubVersion = null,
                onFacet = { f, v -> selected = f to v },
            )
        }
        onNodeWithText("1974").assertDoesNotExist()
        onNodeWithText("en").assertDoesNotExist()
        assertNull(selected)
    }

    @Test
    fun eachAuthorOfACoAuthoredBookIsItsOwnBylinePart() = runComposeUiTest {
        setContent {
            AuthorByline(author = "Ann Leckie, Becky Chambers", onAuthorClick = {})
        }
        onNodeWithText("Ann Leckie", substring = true).assertIsDisplayed()
        onNodeWithText("Becky Chambers", substring = true).assertIsDisplayed()
    }

    // ── bookDownloadOutcome — pure state machine ──────────────────────────────

    @Test
    fun downloadOutcomeIsCompletedWhenStateTransitionsToDownloaded() {
        assertEquals(
            com.riffle.feature.library.BookDownloadOutcome.Completed,
            bookDownloadOutcome(
                previous = DownloadState.InProgress(90),
                current = DownloadState.Downloaded,
            ),
        )
    }

    @Test
    fun downloadOutcomeIsFailedWhenStateTransitionsFromInProgressToNotDownloaded() {
        assertEquals(
            com.riffle.feature.library.BookDownloadOutcome.Failed,
            bookDownloadOutcome(
                previous = DownloadState.InProgress(50),
                current = DownloadState.NotDownloaded,
            ),
        )
    }

    @Test
    fun downloadOutcomeIsNullWhenPreviousStateIsNull() {
        assertNull(bookDownloadOutcome(previous = null, current = DownloadState.Downloaded))
    }

    @Test
    fun downloadOutcomeIsNullWhenNoPreviousInProgress() {
        assertNull(
            bookDownloadOutcome(
                previous = DownloadState.NotDownloaded,
                current = DownloadState.Downloaded,
            ),
        )
    }

    // ── MetadataLines — format labels ────────────────────────────────────────

    @Test
    fun epubFormatLabelIsShownInMetadataLines() = runComposeUiTest {
        setContent {
            MetadataLines(
                item = item(genres = emptyList(), publishedYear = null, language = null).copy(
                    ebookFormat = EbookFormat.Epub,
                ),
                epubVersion = null,
                onFacet = { _, _ -> },
            )
        }
        onNodeWithText("EPUB").assertIsDisplayed()
    }

    @Test
    fun pdfFormatLabelIsShownInMetadataLines() = runComposeUiTest {
        setContent {
            MetadataLines(
                item = item(genres = emptyList(), publishedYear = null, language = null).copy(
                    ebookFormat = EbookFormat.Pdf,
                ),
                epubVersion = null,
                onFacet = { _, _ -> },
            )
        }
        onNodeWithText("PDF").assertIsDisplayed()
    }

    @Test
    fun cbzFormatLabelIsShownInMetadataLines() = runComposeUiTest {
        setContent {
            MetadataLines(
                item = item(genres = emptyList(), publishedYear = null, language = null).copy(
                    ebookFormat = EbookFormat.Cbz,
                ),
                epubVersion = null,
                onFacet = { _, _ -> },
            )
        }
        onNodeWithText("CBZ").assertIsDisplayed()
    }

    // ── PublicationFactsLine — page counts ───────────────────────────────────

    @Test
    fun pageCountIsShownForPdfWhenAvailable() = runComposeUiTest {
        setContent {
            PublicationFactsLine(
                item = item(genres = emptyList(), publishedYear = null, language = null).copy(
                    ebookFormat = EbookFormat.Pdf,
                    pageCount = 320,
                ),
                estimatedTotalReadingTimeSec = null,
                pdfPageCount = null,
            )
        }
        onNodeWithText("320 pages").assertIsDisplayed()
    }

    @Test
    fun pageCountIsShownForCbzWhenAvailable() = runComposeUiTest {
        setContent {
            PublicationFactsLine(
                item = item(genres = emptyList(), publishedYear = null, language = null).copy(
                    ebookFormat = EbookFormat.Cbz,
                    pageCount = 48,
                ),
                estimatedTotalReadingTimeSec = null,
                pdfPageCount = null,
            )
        }
        onNodeWithText("48 pages").assertIsDisplayed()
    }

    @Test
    fun pageCountIsNotShownForEpub() = runComposeUiTest {
        setContent {
            PublicationFactsLine(
                item = item(genres = emptyList(), publishedYear = null, language = null).copy(
                    ebookFormat = EbookFormat.Epub,
                    pageCount = 300,
                ),
                estimatedTotalReadingTimeSec = null,
                pdfPageCount = null,
            )
        }
        onNodeWithText("300 pages").assertDoesNotExist()
    }

    // ── AudiobookDurationLine ─────────────────────────────────────────────────

    @Test
    fun audiobookDurationIsShownForAudiobookOnlyItems() = runComposeUiTest {
        setContent {
            AudiobookDurationLine(
                item = item(genres = emptyList(), publishedYear = null, language = null).copy(
                    ebookFormat = EbookFormat.Unsupported,
                    audioDurationSec = 7_560.0,
                    hasAudio = true,
                ),
            )
        }
        onNodeWithText("2h 6m", substring = true).assertIsDisplayed()
    }
}
