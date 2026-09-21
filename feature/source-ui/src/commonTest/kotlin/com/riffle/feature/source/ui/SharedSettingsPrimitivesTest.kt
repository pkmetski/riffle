package com.riffle.feature.source.ui

import com.riffle.core.data.localfiles.OpenInImportResult
import com.riffle.core.database.LocalFilesFolderEntity
import com.riffle.core.domain.AbsWebSourceDescriptor
import com.riffle.core.domain.LocalFilesWebSourceDescriptor
import com.riffle.core.models.Library
import com.riffle.core.models.Source
import com.riffle.core.models.SourceType
import com.riffle.core.models.SourceUrl
import com.riffle.feature.settings.LibraryUiItem
import com.riffle.feature.source.ui.generated.resources.Res
import com.riffle.feature.source.ui.generated.resources.ui_import_added
import com.riffle.feature.source.ui.settings.configuredSourceSubtitle
import com.riffle.feature.source.ui.settings.localFilesFolderRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The derivations behind the Settings surfaces this module now owns for both hosts.
 *
 * Each one used to be Android-only: iOS's Sources list was a flat "type · authority + Remove"
 * row that showed no server version, filtered the Local Files source out entirely, and had no
 * transient-feedback primitive of any kind (0 SnackbarHosts against Android's 12).
 *
 * Runs on `iosSimulatorArm64` (`:feature:source-ui:iosSimulatorArm64Test`) and on the Android
 * host-test JVM.
 */
class SharedSettingsPrimitivesTest {

    // ── Sources list ──────────────────────────────────────────────────────────────────────

    private fun source(url: String, username: String) = Source(
        id = "s1",
        url = SourceUrl.parse(url)!!,
        isActive = true,
        insecureConnectionAllowed = false,
        username = username,
        type = SourceType.ABS,
    )

    @Test
    fun configuredSourceSubtitleCarriesTheServerVersion() {
        // `serverVersions` was collected on both hosts and rendered only on Android.
        assertEquals(
            "alice · https://abs.example.com · v2.15.1",
            configuredSourceSubtitle(
                source = source("https://abs.example.com", "alice"),
                descriptor = AbsWebSourceDescriptor,
                sourceVersion = "2.15.1",
            ),
        )
    }

    @Test
    fun configuredSourceSubtitleOmitsTheVersionSegmentWhenItIsUnknown() {
        assertEquals(
            "alice · https://abs.example.com",
            configuredSourceSubtitle(
                source = source("https://abs.example.com", "alice"),
                descriptor = AbsWebSourceDescriptor,
                sourceVersion = null,
            ),
        )
    }

    @Test
    fun configuredSourceSubtitleOmitsTheUsernameSegmentWhenThereIsNone() {
        assertEquals(
            "https://abs.example.com",
            configuredSourceSubtitle(
                source = source("https://abs.example.com", ""),
                descriptor = AbsWebSourceDescriptor,
                sourceVersion = null,
            ),
        )
    }

    @Test
    fun configuredSourceSubtitleIsEmptyForASourceWithNoNetworkHost() {
        assertEquals(
            "",
            configuredSourceSubtitle(
                source = source("https://localfiles.invalid", ""),
                descriptor = LocalFilesWebSourceDescriptor,
                sourceVersion = "9",
            ),
        )
    }

    // ── Local files folder ordering ───────────────────────────────────────────────────────

    private fun folder(treeUri: String, libraryId: String) = LocalFilesFolderEntity(
        sourceId = "s1",
        treeUri = treeUri,
        displayName = treeUri.substringAfterLast('/'),
        addedAtEpochMs = 0L,
        libraryId = libraryId,
    )

    private fun libraryItem(id: String, name: String) = LibraryUiItem(
        library = Library(id = id, name = name, mediaType = "book", isUnsupported = false),
        isVisible = true,
        switchEnabled = true,
    )

    @Test
    fun folderRowsFollowTheSavedLibraryOrderNotTheFolderInsertionOrder() {
        val folders = listOf(folder("/a", "lib-a"), folder("/b", "lib-b"))
        val libraries = listOf(libraryItem("lib-b", "B"), libraryItem("lib-a", "A"))

        val rows = localFilesFolderRows(folders, libraries)

        assertEquals(listOf("/b", "/a"), rows.map { it.folder.treeUri })
        assertEquals(listOf(0, 1), rows.map { it.libraryIndex })
    }

    @Test
    fun aFolderWithNoMatchingLibraryStillRendersAtTheEnd() {
        // The DAO flows land independently; a folder that briefly has no library row must not
        // disappear from the list, or the user cannot remove it.
        val folders = listOf(folder("/a", "lib-a"), folder("/orphan", "lib-missing"))
        val libraries = listOf(libraryItem("lib-a", "A"))

        val rows = localFilesFolderRows(folders, libraries)

        assertEquals(listOf("/a", "/orphan"), rows.map { it.folder.treeUri })
        assertNull(rows.last().libraryItem)
        assertEquals(-1, rows.last().libraryIndex)
    }

    @Test
    fun folderRowsAreEmptyWhenThereAreNoFolders() {
        assertTrue(localFilesFolderRows(emptyList(), listOf(libraryItem("lib-a", "A"))).isEmpty())
    }

    // ── Transient messages ────────────────────────────────────────────────────────────────

    @Test
    fun messagesAreShownOldestFirstAndConsumedOneAtATime() {
        val messages = TransientMessages()
        val first = messages.show("one")
        messages.show("two")

        assertEquals("one", messages.current?.text)
        messages.consume(first)
        assertEquals("two", messages.current?.text)
    }

    @Test
    fun postingWhileOneIsShowingQueuesRatherThanDropping() {
        // These carry download and import outcomes; losing one means an operation failed with
        // nothing said about it.
        val messages = TransientMessages()
        messages.show("a")
        messages.show("b")
        messages.show("c")
        assertEquals(listOf("a", "b", "c"), messages.queue.value.map { it.text })
    }

    @Test
    fun repeatingTheSameTextStillProducesADistinctMessage() {
        val messages = TransientMessages()
        val first = messages.show("Download failed")
        val second = messages.show("Download failed")
        assertTrue(first != second, "a second failure must re-show, not be swallowed as a duplicate")
    }

    @Test
    fun consumingAnUnknownIdDoesNotEatTheNextMessage() {
        val messages = TransientMessages()
        val first = messages.show("one")
        messages.show("two")
        messages.consume(first)
        messages.consume(first)
        assertEquals(listOf("two"), messages.queue.value.map { it.text })
    }

    @Test
    fun clearDropsEverything() {
        val messages = TransientMessages()
        messages.show("one")
        messages.show("two")
        messages.clear()
        assertNull(messages.current)
    }

    // ── Import outcome copy ───────────────────────────────────────────────────────────────

    @Test
    fun anImportedAndAnAlreadyPresentBookReadTheSame() {
        // From the reader's point of view the book *is* in the library either way; a separate
        // "already there" phrasing reads as an error for a completely successful outcome.
        assertEquals(
            openInImportMessage(OpenInImportResult.Imported("Novel")),
            openInImportMessage(OpenInImportResult.AlreadyPresent("Novel")),
        )
    }

    @Test
    fun anUnsupportedFileAndAFailedImportReadDifferently() {
        val unsupported = openInImportMessage(OpenInImportResult.Unsupported("clip.mp4"))
        val failed = openInImportMessage(OpenInImportResult.Failed("Novel.epub", "disk full"))
        assertTrue(unsupported.resource != failed.resource, "'not a book' is not the same as 'it broke'")
        assertEquals("clip.mp4", unsupported.argument)
        assertEquals("Novel.epub", failed.argument)
    }

    @Test
    fun importMessagesAreLocalisedResourcesNotKotlinLiterals() {
        // Every user-visible string in `shared` used to be a Kotlin literal. The import feedback
        // is new copy and must not reintroduce that.
        assertEquals(
            Res.string.ui_import_added,
            openInImportMessage(OpenInImportResult.Imported("Novel")).resource,
        )
    }
}
