package com.riffle.shared.library

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.feature.library.DetailCapabilities
import com.riffle.feature.library.DownloadState
import com.riffle.feature.library.LibraryItemDetailUiState
import com.riffle.feature.designsystem.TestTags
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression tests for the format label and publication-facts lines added in the item-detail parity
 * pass.
 *
 * Before these lines existed, iOS showed no format indicator at all (no "EPUB", "PDF", or "CBZ")
 * and no page count for fixed-layout books. Android renders both via `FormatLine` and
 * `PublicationFactsLine`. Reverting the shared implementation must make these tests go red.
 */
@OptIn(ExperimentalTestApi::class)
class LibraryItemDetailFormatLineTest {

    private fun readyState(
        format: EbookFormat = EbookFormat.Epub,
        pageCount: Int? = null,
        audioDurationSec: Double = 0.0,
        hasAudio: Boolean = false,
    ) = LibraryItemDetailUiState.Ready(
        item = LibraryItem(
            id = "item-1",
            sourceId = "src-1",
            libraryId = "lib-1",
            title = "A Book",
            author = "An Author",
            coverUrl = null,
            readingProgress = 0f,
            isCached = false,
            isDownloaded = false,
            ebookFormat = format,
            pageCount = pageCount,
            audioDurationSec = audioDurationSec,
            hasAudio = hasAudio,
        ),
        capabilities = DetailCapabilities.Empty,
    )

    // ── Format line ─────────────────────────────────────────────────────────────

    @Test
    fun epubFormatLabelIsShown() = runComposeUiTest {
        setContent {
            ReadyContent(
                state = readyState(format = EbookFormat.Epub),
                token = "",
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                downloadControls = {},
                downloadState = DownloadState.NotDownloaded,
                onAddToPlaylist = null,
                onFacet = { _, _ -> },
            )
        }

        onNodeWithText("EPUB").assertIsDisplayed()
    }

    @Test
    fun pdfFormatLabelIsShown() = runComposeUiTest {
        setContent {
            ReadyContent(
                state = readyState(format = EbookFormat.Pdf),
                token = "",
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                downloadControls = {},
                downloadState = DownloadState.NotDownloaded,
                onAddToPlaylist = null,
                onFacet = { _, _ -> },
            )
        }

        onNodeWithText("PDF").assertIsDisplayed()
    }

    @Test
    fun cbzFormatLabelIsShown() = runComposeUiTest {
        setContent {
            ReadyContent(
                state = readyState(format = EbookFormat.Cbz),
                token = "",
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                downloadControls = {},
                downloadState = DownloadState.NotDownloaded,
                onAddToPlaylist = null,
                onFacet = { _, _ -> },
            )
        }

        onNodeWithText("CBZ").assertIsDisplayed()
    }

    // ── Publication facts ────────────────────────────────────────────────────────

    @Test
    fun pageCountIsShownForPdfWhenAvailable() = runComposeUiTest {
        setContent {
            ReadyContent(
                state = readyState(format = EbookFormat.Pdf, pageCount = 320),
                token = "",
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                downloadControls = {},
                downloadState = DownloadState.NotDownloaded,
                onAddToPlaylist = null,
                onFacet = { _, _ -> },
            )
        }

        onNodeWithText("320 pages").assertIsDisplayed()
    }

    @Test
    fun pageCountIsShownForCbzWhenAvailable() = runComposeUiTest {
        setContent {
            ReadyContent(
                state = readyState(format = EbookFormat.Cbz, pageCount = 48),
                token = "",
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                downloadControls = {},
                downloadState = DownloadState.NotDownloaded,
                onAddToPlaylist = null,
                onFacet = { _, _ -> },
            )
        }

        onNodeWithText("48 pages").assertIsDisplayed()
    }

    @Test
    fun pageCountIsNotShownForEpub() = runComposeUiTest {
        setContent {
            ReadyContent(
                state = readyState(format = EbookFormat.Epub, pageCount = 300),
                token = "",
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                downloadControls = {},
                downloadState = DownloadState.NotDownloaded,
                onAddToPlaylist = null,
                onFacet = { _, _ -> },
            )
        }

        // EPUB is reflowable — "pages" is not a meaningful metric and must not appear.
        onNodeWithText("300 pages").assertDoesNotExist()
    }

    @Test
    fun audiobookDurationIsShownForAudiobookOnlyItems() = runComposeUiTest {
        setContent {
            ReadyContent(
                state = readyState(
                    // EbookFormat.Unsupported makes isReadable=false; hasAudio=true makes
                    // isListenable=true; together they satisfy isAudiobookOnly.
                    format = EbookFormat.Unsupported,
                    audioDurationSec = 7_560.0,
                    hasAudio = true,
                ),
                token = "",
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                downloadControls = {},
                downloadState = DownloadState.NotDownloaded,
                onAddToPlaylist = null,
                onFacet = { _, _ -> },
            )
        }

        // 7560 s = 2h 6m
        onNodeWithText("2h 6m").assertIsDisplayed()
    }

    // ── Layout order: Read button precedes title ────────────────────────────────

    /**
     * Android's phone-portrait layout shows the action row (CTA buttons) before the title so the
     * primary action is prominent before the user reads anything. The iOS screen used to put the
     * title first, which is the reverse of Android's order. Pin the new order so it cannot regress.
     *
     * We verify ordering in the semantics tree by comparing the vertical position of the Read
     * button node against the title node: on a vertically scrolling column, a node that appears
     * earlier has a smaller Y coordinate.
     */
    @Test
    fun readButtonAppearsBeforeTitle() = runComposeUiTest {
        setContent {
            ReadyContent(
                state = readyState(),
                token = "",
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                downloadControls = {},
                downloadState = DownloadState.NotDownloaded,
                onAddToPlaylist = null,
                onFacet = { _, _ -> },
            )
        }

        val readButton = onNodeWithTag(TestTags.BOOK_DETAIL_OPEN).fetchSemanticsNode()
        // BOOK_DETAIL_TITLE is on the body title (not the TopAppBar title, which also shows the
        // same text). Using the tag avoids an ambiguous multi-node match.
        val titleNode = onNodeWithTag(TestTags.BOOK_DETAIL_TITLE).fetchSemanticsNode()

        val readY = readButton.boundsInRoot.top
        val titleY = titleNode.boundsInRoot.top
        assertTrue(
            readY < titleY,
            "Read button (y=$readY) must appear above title (y=$titleY) in the layout, " +
                "matching Android's phone-portrait order",
        )
    }
}
