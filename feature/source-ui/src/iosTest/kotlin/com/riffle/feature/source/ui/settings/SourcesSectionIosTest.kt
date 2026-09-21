package com.riffle.feature.source.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.riffle.core.database.LocalFilesFolderEntity
import com.riffle.core.models.Library
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.feature.settings.LibraryUiItem
import com.riffle.feature.source.ui.ConfirmDestructiveDialog
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared Sources list, driven on iOS.
 *
 * Android's counterparts are `app/src/androidTest`'s `LocalFilesSourceRowTrashTest`,
 * `ChitankaSourceRowTest`, `SwipeToDeleteRowTest` and `ServerSettingsExpansionTest`, which now
 * drive these same composables through `createComposeRule`.
 *
 * Before this, iOS's Settings rendered a flat "type · authority + Remove" list that filtered the
 * Local Files source out entirely — so `localFilesFolders`, `localFilesFolderHealth`,
 * `removeLocalFolder()` and `removeLocalFilesSource()` had zero callers on this platform despite
 * every collaborator being injected.
 *
 * Runs as part of `:feature:source-ui:iosSimulatorArm64Test`.
 */
@OptIn(ExperimentalTestApi::class)
class SourcesSectionIosTest {

    private val localFilesSource = Source(
        id = "local",
        url = SourceUrl.parse("https://localfiles.invalid")!!,
        isActive = false,
        insecureConnectionAllowed = false,
        username = "",
        type = SourceType.LOCAL_FILES,
    )

    private fun folder(treeUri: String, libraryId: String, name: String) = LocalFilesFolderEntity(
        sourceId = "local",
        treeUri = treeUri,
        displayName = name,
        addedAtEpochMs = 0L,
        libraryId = libraryId,
    )

    private fun libraryItem(id: String, name: String) = LibraryUiItem(
        library = Library(id = id, name = name, mediaType = "book", isUnsupported = false),
        isVisible = true,
        switchEnabled = true,
    )

    private fun section(
        folders: List<LocalFilesFolderEntity>,
        folderHealth: Map<String, Boolean> = emptyMap(),
        expanded: Boolean = true,
        onAddSourcePicker: () -> Unit = {},
        onRemoveFolder: (String) -> Unit = {},
    ): @Composable () -> Unit = {
        val expandedSources = mutableStateMapOf<String, Boolean>()
        if (expanded) expandedSources["local"] = true
        Column {
            SourcesSection(
                servers = listOf(localFilesSource),
                localFilesSource = localFilesSource,
                localFilesFolders = folders,
                localFilesFolderHealth = folderHealth,
                singletonWebSources = emptyList(),
                sourceVersions = emptyMap(),
                libraryItemsBySource = mapOf("local" to folders.map { libraryItem(it.libraryId, it.displayName) }),
                readaloudSummaries = emptyMap(),
                expandedSources = expandedSources,
                onNavigateToAddSourcePicker = onAddSourcePicker,
                onNavigateToAddLocalFolder = {},
                onOpenReadaloudMatches = {},
                onRemoveSource = {},
                onRemoveLocalFolder = onRemoveFolder,
                onRemoveLocalFilesSource = {},
                onSetLibraryVisible = { _, _, _ -> },
                onReorderLibraries = { _, _ -> },
            )
        }
    }

    @Test
    fun theLocalFilesSourceHasARowAtAll() = runComposeUiTest {
        setContent { section(folders = listOf(folder("/books", "lib-1", "Books")), expanded = false)() }
        onNodeWithTag("LocalFilesSourceRow").assertIsDisplayed()
    }

    @Test
    fun expandingTheLocalFilesRowRevealsItsFolders() = runComposeUiTest {
        setContent { section(folders = listOf(folder("/books", "lib-1", "Books")))() }
        onNodeWithTag("LocalFilesFolder./books").assertIsDisplayed()
    }

    @Test
    fun collapsedTheFoldersAreNotRendered() = runComposeUiTest {
        setContent { section(folders = listOf(folder("/books", "lib-1", "Books")), expanded = false)() }
        onNodeWithTag("LocalFilesFolder./books").assertDoesNotExist()
    }

    @Test
    fun removingAFolderAsksFirstAndOnlyActsOnConfirm() = runComposeUiTest {
        val removed = mutableListOf<String>()
        setContent {
            section(
                folders = listOf(folder("/books", "lib-1", "Books"), folder("/more", "lib-2", "More")),
                onRemoveFolder = { removed += it },
            )()
        }
        onNodeWithTag("LocalFilesFolder.Remove./books").performClick()
        assertEquals(emptyList(), removed, "the trash icon must not delete on the first tap")
        onNodeWithTag("LocalFilesSourceRow.ConfirmRemoveFolder").performClick()
        assertEquals(listOf("/books"), removed)
    }

    @Test
    fun anUnhealthyFolderSaysSoInsteadOfShowingItsPath() = runComposeUiTest {
        setContent {
            section(
                folders = listOf(folder("/books", "lib-1", "Books")),
                folderHealth = mapOf("/books" to false),
            )()
        }
        onNodeWithText("Permission revoked — remove and re-add").assertIsDisplayed()
    }

    @Test
    fun addSourceIsReachableFromTheList() = runComposeUiTest {
        var picked = 0
        setContent { section(folders = emptyList(), onAddSourcePicker = { picked++ })() }
        onNodeWithTag("SourcesSection.AddSource").performClick()
        assertEquals(1, picked)
    }

    // ── The shared confirmation primitive ─────────────────────────────────────────────────

    @Test
    fun theConfirmationDialogActsOnConfirmAndDismissesItself() = runComposeUiTest {
        var confirmed = 0
        var dismissed = 0
        setContent {
            ConfirmDestructiveDialog(
                title = "Remove everything?",
                message = "This cannot be undone.",
                confirmLabel = "Remove all",
                onConfirm = { confirmed++ },
                onDismiss = { dismissed++ },
                testTag = "Test.Confirm",
            )
        }
        onNodeWithText("Remove everything?").assertIsDisplayed()
        onNodeWithTag("Test.Confirm").performClick()
        assertEquals(1, confirmed)
        assertEquals(1, dismissed, "the dialog must close itself; its owner is usually gone by then")
    }

    @Test
    fun theConfirmationDialogCancelDoesNotAct() = runComposeUiTest {
        var confirmed = 0
        var dismissed = 0
        setContent {
            ConfirmDestructiveDialog(
                title = "Remove everything?",
                message = "This cannot be undone.",
                confirmLabel = "Remove all",
                onConfirm = { confirmed++ },
                onDismiss = { dismissed++ },
                testTag = "Test.Confirm",
            )
        }
        onNodeWithText("Cancel").performClick()
        assertEquals(0, confirmed)
        assertEquals(1, dismissed)
    }
}
