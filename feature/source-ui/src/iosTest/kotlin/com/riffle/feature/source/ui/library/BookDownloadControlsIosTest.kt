package com.riffle.feature.source.ui.library

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.feature.library.DetailCapabilities
import com.riffle.feature.library.DownloadState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The download controls, driven on iOS.
 *
 * Android's counterparts are the instrumentation tests in `app/src/androidTest` — notably
 * `ReadaloudDownloadButtonTest`, which now exercises the same shared `ReadaloudDownloadButton`.
 * Nothing on iOS could start a download at all before this row existed: `IosDownloadManagerImpl`,
 * `IosEpubRepositoryImpl.downloadEpub`, `IosReadaloudOfflineDownloader` and
 * `IosAudiobookDownloadRepositoryImpl` were all real, all Koin-bound and all unreachable.
 *
 * Runs as part of `:feature:source-ui:iosSimulatorArm64Test`.
 */
@OptIn(ExperimentalTestApi::class)
class BookDownloadControlsIosTest {

    private fun item(listenable: Boolean = false) = LibraryItem(
        id = "item-1",
        sourceId = "src-1",
        libraryId = "lib-1",
        title = "T",
        author = "A",
        coverUrl = null,
        readingProgress = 0f,
        isCached = false,
        isDownloaded = false,
        ebookFormat = EbookFormat.Epub,
        hasAudio = listenable,
    )

    @Test
    fun tappingTheEbookControlStartsTheDownload() = runComposeUiTest {
        var started = 0
        setContent {
            BookDownloadControls(
                item = item(),
                capabilities = DetailCapabilities.All,
                isOffline = false,
                downloadState = DownloadState.NotDownloaded,
                audiobookDownloadState = null,
                readaloudDownloadState = null,
                onDownloadEbook = { started++ },
                onRemoveEbook = {},
                onDownloadAudiobook = {},
                onRemoveAudiobook = {},
                onDownloadReadaloud = {},
                onRemoveReadaloud = {},
            )
        }
        onNodeWithTag("BookDownloadControls.Ebook").assertIsDisplayed().performClick()
        assertEquals(1, started)
    }

    @Test
    fun tappingADownloadedEbookControlRemovesIt() = runComposeUiTest {
        var removed = 0
        setContent {
            BookDownloadControls(
                item = item(),
                capabilities = DetailCapabilities.All,
                isOffline = false,
                downloadState = DownloadState.Downloaded,
                audiobookDownloadState = null,
                readaloudDownloadState = null,
                onDownloadEbook = {},
                onRemoveEbook = { removed++ },
                onDownloadAudiobook = {},
                onRemoveAudiobook = {},
                onDownloadReadaloud = {},
                onRemoveReadaloud = {},
            )
        }
        onNodeWithTag("BookDownloadControls.Ebook").performClick()
        assertEquals(1, removed)
    }

    @Test
    fun anInProgressDownloadShowsItsLivePercent() = runComposeUiTest {
        setContent {
            BookDownloadControls(
                item = item(),
                capabilities = DetailCapabilities.All,
                isOffline = false,
                downloadState = DownloadState.InProgress(42),
                audiobookDownloadState = null,
                readaloudDownloadState = null,
                onDownloadEbook = {},
                onRemoveEbook = {},
                onDownloadAudiobook = {},
                onRemoveAudiobook = {},
                onDownloadReadaloud = {},
                onRemoveReadaloud = {},
            )
        }
        onNodeWithText("42%").assertIsDisplayed()
    }

    @Test
    fun eachFormatGetsItsOwnControlAndItsOwnCallback() = runComposeUiTest {
        var ebook = 0
        var audiobook = 0
        var readaloud = 0
        setContent {
            BookDownloadControls(
                item = item(listenable = true),
                capabilities = DetailCapabilities.All,
                isOffline = false,
                downloadState = DownloadState.NotDownloaded,
                audiobookDownloadState = DownloadState.NotDownloaded,
                readaloudDownloadState = DownloadState.NotDownloaded,
                onDownloadEbook = { ebook++ },
                onRemoveEbook = {},
                onDownloadAudiobook = { audiobook++ },
                onRemoveAudiobook = {},
                onDownloadReadaloud = { readaloud++ },
                onRemoveReadaloud = {},
            )
        }
        onNodeWithTag("BookDownloadControls.Ebook").performClick()
        onNodeWithTag("BookDownloadControls.Audiobook").performClick()
        onNodeWithTag("BookDownloadControls.Readaloud").performClick()
        assertEquals(1, ebook, "ebook")
        assertEquals(1, audiobook, "audiobook")
        assertEquals(1, readaloud, "readaloud")
    }

    @Test
    fun offlineBlocksAnAudiobookThatIsNotStoredYet() = runComposeUiTest {
        var audiobook = 0
        setContent {
            BookDownloadControls(
                item = item(listenable = true),
                capabilities = DetailCapabilities.All,
                isOffline = true,
                downloadState = DownloadState.NotDownloaded,
                audiobookDownloadState = DownloadState.NotDownloaded,
                readaloudDownloadState = null,
                onDownloadEbook = {},
                onRemoveEbook = {},
                onDownloadAudiobook = { audiobook++ },
                onRemoveAudiobook = {},
                onDownloadReadaloud = {},
                onRemoveReadaloud = {},
            )
        }
        onNodeWithTag("BookDownloadControls.Audiobook").performClick()
        assertEquals(0, audiobook, "a tap with no network must not start an audiobook download")
    }

    @Test
    fun aSourceWithoutDownloadsRendersNothingAtAll() = runComposeUiTest {
        setContent {
            BookDownloadControls(
                item = item(),
                capabilities = DetailCapabilities.Empty,
                isOffline = false,
                downloadState = DownloadState.NotDownloaded,
                audiobookDownloadState = null,
                readaloudDownloadState = null,
                onDownloadEbook = {},
                onRemoveEbook = {},
                onDownloadAudiobook = {},
                onRemoveAudiobook = {},
                onDownloadReadaloud = {},
                onRemoveReadaloud = {},
            )
        }
        // LocalFiles has no local store; an empty Row with 8dp spacing would still be a tap
        // target in a layout that expects nothing there.
        onNodeWithTag("BookDownloadControls").assertDoesNotExist()
    }
}
