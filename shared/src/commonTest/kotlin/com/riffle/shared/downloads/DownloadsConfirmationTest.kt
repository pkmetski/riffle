package com.riffle.shared.downloads

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.models.EbookFormat
import com.riffle.core.models.LibraryItem
import com.riffle.feature.downloads.DownloadsUiState
import com.riffle.feature.downloads.LocalItemUi
import com.riffle.feature.downloads.LocalMediaType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * "Remove all downloads" and "Clear all cached" on iOS.
 *
 * Both wipe every offline copy on the device and both used to fire straight off a bare
 * `clickable` here, while Android wrapped the identical `removeAllDownloads()` call in an
 * `AlertDialog`. iOS had 0 `AlertDialog`s guarding destructive actions against Android's 29.
 *
 * Android's counterpart is `app/src/main/.../downloads/DownloadsScreen.kt`'s own confirmation,
 * pinned by its harness coverage; this drives the shared screen on the iOS path.
 *
 * Runs as part of `:shared:iosSimulatorArm64Test`.
 */
@OptIn(ExperimentalTestApi::class)
class DownloadsConfirmationTest {

    private fun downloadedState() = DownloadsUiState(
        downloadedItems = listOf(
            LocalItemUi(
                sourceId = "s1",
                item = LibraryItem(
                    id = "i1",
                    sourceId = "s1",
                    libraryId = "l1",
                    title = "T",
                    author = "A",
                    coverUrl = null,
                    readingProgress = 0f,
                    isCached = false,
                    isDownloaded = true,
                    ebookFormat = EbookFormat.Epub,
                ),
                sizeBytes = 1_024L,
                mediaTypes = setOf(LocalMediaType.Epub),
            ),
        ),
        cachedItems = listOf(
            LocalItemUi(
                sourceId = "s1",
                item = LibraryItem(
                    id = "i2",
                    sourceId = "s1",
                    libraryId = "l1",
                    title = "C",
                    author = "A",
                    coverUrl = null,
                    readingProgress = 0f,
                    isCached = true,
                    isDownloaded = false,
                    ebookFormat = EbookFormat.Epub,
                ),
                sizeBytes = 512L,
                mediaTypes = setOf(LocalMediaType.Epub),
            ),
        ),
    )

    @Test
    fun removeAllDownloadsAsksBeforeWipingTheDevice() = runComposeUiTest {
        var removedAll = 0
        setContent {
            DownloadsContent(
                state = downloadedState(),
                onBack = {},
                onSetCacheAutoClear = {},
                onRemoveDownloadedItem = {},
                onRemoveCachedItem = {},
                onRemoveAllDownloads = { removedAll++ },
                onClearAllCached = {},
            )
        }
        onNodeWithTag("DownloadsScreen.RemoveAllDownloads").assertIsDisplayed().performClick()
        assertEquals(0, removedAll, "the first tap must open a confirmation, not delete everything")
        onNodeWithTag("DownloadsScreen.ConfirmRemoveAllDownloads").assertIsDisplayed().performClick()
        assertEquals(1, removedAll)
    }

    @Test
    fun clearAllCachedAsksBeforeWipingTheCache() = runComposeUiTest {
        var cleared = 0
        setContent {
            DownloadsContent(
                state = downloadedState(),
                onBack = {},
                onSetCacheAutoClear = {},
                onRemoveDownloadedItem = {},
                onRemoveCachedItem = {},
                onRemoveAllDownloads = {},
                onClearAllCached = { cleared++ },
            )
        }
        onNodeWithTag("DownloadsScreen.ClearAllCached").performClick()
        assertEquals(0, cleared)
        onNodeWithTag("DownloadsScreen.ConfirmClearAllCached").performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun theConfirmationIsNotShownUntilTheAffordanceIsTapped() = runComposeUiTest {
        setContent {
            DownloadsContent(
                state = downloadedState(),
                onBack = {},
                onSetCacheAutoClear = {},
                onRemoveDownloadedItem = {},
                onRemoveCachedItem = {},
                onRemoveAllDownloads = {},
                onClearAllCached = {},
            )
        }
        onNodeWithTag("DownloadsScreen.ConfirmRemoveAllDownloads").assertDoesNotExist()
        onNodeWithTag("DownloadsScreen.ConfirmClearAllCached").assertDoesNotExist()
    }
}
