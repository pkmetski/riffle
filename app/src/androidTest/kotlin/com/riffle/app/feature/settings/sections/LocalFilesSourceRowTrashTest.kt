package com.riffle.app.feature.settings.sections

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.riffle.app.feature.settings.LibraryUiItem
import com.riffle.core.database.LocalFilesFolderEntity
import com.riffle.core.models.Library
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins the "at least one folder must remain" invariant on the per-folder trash icon in the
 * Local Files source row. If the trash on the last remaining folder becomes tappable, a user can
 * strand the source with zero libraries; the swipe-to-remove gesture on the source row header is
 * the correct escape hatch instead.
 */
@RunWith(AndroidJUnit4::class)
class LocalFilesSourceRowTrashTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val source = Source(
        id = "local-files",
        url = SourceUrl.parse("http://local-files.invalid")!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = "",
        type = SourceType.LOCAL_FILES,
    )

    private fun folder(index: Int) = LocalFilesFolderEntity(
        sourceId = source.id,
        treeUri = "content://tree/$index",
        displayName = "Folder $index",
        addedAtEpochMs = 0L,
        libraryId = "lib-$index",
    )

    private fun libraryItem(index: Int, itemCount: Int) = LibraryUiItem(
        library = Library(id = "lib-$index", name = "Folder $index", mediaType = "book", isUnsupported = false),
        isVisible = true,
        switchEnabled = itemCount > 1,
    )

    @Test
    fun trashIcon_disabled_whenOnlyOneFolder() {
        composeTestRule.setContent {
            LocalFilesSourceRow(
                source = source,
                folders = listOf(folder(1)),
                folderHealth = emptyMap(),
                libraryItems = listOf(libraryItem(1, itemCount = 1)),
                isExpanded = true,
                onToggleExpanded = {},
                onAddFolder = {},
                onRemoveFolder = {},
                onRemoveSource = {},
                onSetLibraryVisible = { _, _ -> },
                onReorderLibraries = {},
            )
        }

        composeTestRule.onAllNodesWithContentDescription("Remove folder")[0].assertIsNotEnabled()
    }

    @Test
    fun trashIcon_enabled_whenTwoOrMoreFolders() {
        composeTestRule.setContent {
            LocalFilesSourceRow(
                source = source,
                folders = listOf(folder(1), folder(2)),
                folderHealth = emptyMap(),
                libraryItems = listOf(libraryItem(1, itemCount = 2), libraryItem(2, itemCount = 2)),
                isExpanded = true,
                onToggleExpanded = {},
                onAddFolder = {},
                onRemoveFolder = {},
                onRemoveSource = {},
                onSetLibraryVisible = { _, _ -> },
                onReorderLibraries = {},
            )
        }

        val trashNodes = composeTestRule.onAllNodesWithContentDescription("Remove folder")
        trashNodes[0].assertIsEnabled()
        trashNodes[1].assertIsEnabled()
    }

    @Test
    fun trashIcon_onLastFolder_doesNotOpenRemovalDialog() {
        var removeCallCount = 0
        composeTestRule.setContent {
            LocalFilesSourceRow(
                source = source,
                folders = listOf(folder(1)),
                folderHealth = emptyMap(),
                libraryItems = listOf(libraryItem(1, itemCount = 1)),
                isExpanded = true,
                onToggleExpanded = {},
                onAddFolder = {},
                onRemoveFolder = { removeCallCount++ },
                onRemoveSource = {},
                onSetLibraryVisible = { _, _ -> },
                onReorderLibraries = {},
            )
        }

        composeTestRule.onAllNodesWithContentDescription("Remove folder")[0].performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Remove folder?").assertDoesNotExist()
        assert(removeCallCount == 0) { "onRemoveFolder must not fire when the trash is disabled" }
    }

    /**
     * Pins the removal of the dedicated "Remove Local Files source" button from the expanded row
     * body. Whole-source removal now goes through the standard swipe gesture on the row header
     * (same as every other Source row); re-introducing the in-body button would flip this red.
     */
    @Test
    fun expandedBody_doesNotShowDedicatedRemoveSourceButton() {
        composeTestRule.setContent {
            LocalFilesSourceRow(
                source = source,
                folders = listOf(folder(1)),
                folderHealth = emptyMap(),
                libraryItems = listOf(libraryItem(1, itemCount = 1)),
                isExpanded = true,
                onToggleExpanded = {},
                onAddFolder = {},
                onRemoveFolder = {},
                onRemoveSource = {},
                onSetLibraryVisible = { _, _ -> },
                onReorderLibraries = {},
            )
        }

        composeTestRule.onNodeWithText("Remove Local Files source").assertDoesNotExist()
    }
}
