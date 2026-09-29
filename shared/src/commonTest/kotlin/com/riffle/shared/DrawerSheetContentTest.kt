package com.riffle.shared

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.models.Library
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.core.models.ServerType
import kotlin.test.Test

/**
 * Regression for the drawer library list showing when Riffle is active.
 *
 * When the user opens the Riffle aggregated view, the individual library entries in the drawer are
 * meaningless — tapping one would leave the cross-library aggregate and enter a single library.
 * The fix gates the list on `!isRiffleActive`. These tests pin both the hide (bug path) and the
 * show (normal path) so that reverting the gate makes one of them go red.
 */
@OptIn(ExperimentalTestApi::class)
class DrawerSheetContentTest {

    private val testLibrary = Library(
        id = "lib1",
        name = "My Test Library",
        mediaType = "book",
        isUnsupported = false,
    )

    private val testSource = Source(
        id = "src1",
        url = SourceUrl.parse("http://abs.local:13378")!!,
        isActive = true,
        insecureConnectionAllowed = true,
        username = "test",
        type = SourceType.ABS,
        serverType = ServerType.AUDIOBOOKSHELF,
    )

    @Test
    fun libraryListIsHiddenWhenRiffleIsActive() = runComposeUiTest {
        setContent {
            DrawerSheetContent(
                activeServer = testSource,
                allServers = listOf(testSource),
                visibleLibraries = listOf(testLibrary),
                activeLibraryId = null,
                isRiffleActive = true,
                onServerSelected = {},
                onLibrarySelected = {},
                onNavigateToSettings = {},
                onNavigateToDownloads = {},
            )
        }

        onNodeWithText("My Test Library").assertDoesNotExist()
    }

    @Test
    fun libraryListIsShownWhenRiffleIsNotActive() = runComposeUiTest {
        setContent {
            DrawerSheetContent(
                activeServer = testSource,
                allServers = listOf(testSource),
                visibleLibraries = listOf(testLibrary),
                activeLibraryId = null,
                isRiffleActive = false,
                onServerSelected = {},
                onLibrarySelected = {},
                onNavigateToSettings = {},
                onNavigateToDownloads = {},
            )
        }

        onNodeWithText("My Test Library").assertIsDisplayed()
    }
}
