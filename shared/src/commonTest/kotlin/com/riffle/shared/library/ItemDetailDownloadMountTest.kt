package com.riffle.shared.library

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.feature.library.DetailCapabilities
import com.riffle.feature.library.DownloadState
import com.riffle.feature.library.LibraryItemDetailUiState
import kotlin.test.Test

/**
 * The item detail sheet mounts a download control and reports what a finished download did.
 *
 * Both were absent on iOS: the screen collected only `uiState` and never
 * `downloadState`/`audiobookDownloadState`/`readaloudDownloadState`, so `IosDownloadManagerImpl`,
 * `IosEpubRepositoryImpl.downloadEpub`, `IosReadaloudOfflineDownloader` and
 * `IosAudiobookDownloadRepositoryImpl` — all real, all Koin-bound — were unreachable, and files
 * only ever reached the evictable cache as a side effect of opening a book.
 *
 * Runs as part of `:shared:iosSimulatorArm64Test`.
 */
@OptIn(ExperimentalTestApi::class)
class ItemDetailDownloadMountTest {

    private fun readyState() = LibraryItemDetailUiState.Ready(
        item = LibraryItem(
            id = "i1",
            sourceId = "s1",
            libraryId = "l1",
            title = "T",
            author = "A",
            coverUrl = null,
            readingProgress = 0f,
            isCached = false,
            isDownloaded = false,
            ebookFormat = EbookFormat.Epub,
        ),
        capabilities = DetailCapabilities.All,
    )

    @Test
    fun theSheetRendersWhateverDownloadControlItIsGiven() = runComposeUiTest {
        setContent {
            ReadyContent(
                state = readyState(),
                token = "",
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                downloadControls = { BasicText("DOWNLOAD-SLOT") },
                downloadState = DownloadState.NotDownloaded,
                onAddToPlaylist = null,
                onFacet = { _, _ -> },
            )
        }
        onNodeWithText("DOWNLOAD-SLOT").assertIsDisplayed()
    }

    @Test
    fun aDownloadThatLandsSaysSo() = runComposeUiTest {
        var state by mutableStateOf<DownloadState>(DownloadState.InProgress(90))
        setContent {
            ReadyContent(
                state = readyState(),
                token = "",
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                downloadControls = {},
                downloadState = state,
                onAddToPlaylist = null,
                onFacet = { _, _ -> },
            )
        }
        state = DownloadState.Downloaded
        waitForIdle()
        onNodeWithText("Download complete").assertIsDisplayed()
    }

    @Test
    fun aDownloadThatFailsIsNotSilent() = runComposeUiTest {
        // The only observable difference between "finished" and "failed" is which terminal state
        // the ring lands in; without this the ring just turns back into an outlined circle.
        var state by mutableStateOf<DownloadState>(DownloadState.InProgress(90))
        setContent {
            ReadyContent(
                state = readyState(),
                token = "",
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                downloadControls = {},
                downloadState = state,
                onAddToPlaylist = null,
                onFacet = { _, _ -> },
            )
        }
        state = DownloadState.NotDownloaded
        waitForIdle()
        onNodeWithText("Download failed").assertIsDisplayed()
    }

    @Test
    fun justOpeningTheSheetSaysNothing() = runComposeUiTest {
        setContent {
            ReadyContent(
                state = readyState(),
                token = "",
                onBack = {},
                onRead = {},
                onToggleToRead = {},
                downloadControls = {},
                downloadState = DownloadState.Downloaded,
                onAddToPlaylist = null,
                onFacet = { _, _ -> },
            )
        }
        onNodeWithText("Download complete").assertDoesNotExist()
    }
}
