package com.riffle.core.data.localfiles

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * "Open in Riffle" — the shared importer both platforms run.
 *
 * Neither platform had any way to receive a book from outside the app before: iOS declared no
 * document types at all and Android's manifest had a single LAUNCHER intent-filter, so the folder
 * picker was the only entry point into the library.
 *
 * Runs on `iosSimulatorArm64` (`:core:data:iosSimulatorArm64Test`) and on the Android host test
 * JVM, because the decision it encodes is identical on both.
 */
class OpenInImporterTest {

    private class RecordingFolder(
        private val failOnPlace: Boolean = false,
    ) : ManagedImportsFolder {
        var ensureCalls = 0
        val placed = mutableListOf<Pair<String, String>>()
        val existing = mutableSetOf<String>()

        override suspend fun ensure(): FolderUri {
            ensureCalls++
            return FolderUri("file:///imports")
        }

        override suspend fun place(locator: String, displayName: String): String {
            if (failOnPlace) error("disk full")
            val name = OpenInFileTypes.uniqueNameIn(existing, displayName)
            existing += name
            placed += locator to name
            return name
        }
    }

    private class FakeInstaller(
        private val report: LocalFilesInstallerInterface.InstallReport,
        private val throws: Boolean = false,
    ) : LocalFilesInstallerInterface {
        val installed = mutableListOf<FolderUri>()
        override suspend fun installFolder(folderUri: FolderUri): LocalFilesInstallerInterface.InstallReport {
            if (throws) error("scan blew up")
            installed += folderUri
            return report
        }
    }

    private fun report(added: Int, failures: Int = 0) =
        LocalFilesInstallerInterface.InstallReport(added = added, failures = failures)

    @Test
    fun aSupportedBookIsCopiedIntoTheManagedFolderAndScanned() = runTest {
        val folder = RecordingFolder()
        val installer = FakeInstaller(report(added = 1))
        val result = SharedOpenInImporter(folder, installer).importFile("content://inbox/1", "Novel.epub")

        assertEquals(OpenInImportResult.Imported("Novel"), result)
        assertEquals(listOf("content://inbox/1" to "Novel.epub"), folder.placed)
        assertEquals(listOf(FolderUri("file:///imports")), installer.installed)
    }

    @Test
    fun anUnsupportedFileNeverTouchesTheFolderOrTheScanner() = runTest {
        val folder = RecordingFolder()
        val installer = FakeInstaller(report(added = 1))
        val result = SharedOpenInImporter(folder, installer).importFile("content://inbox/1", "holiday.mp4")

        assertEquals(OpenInImportResult.Unsupported("holiday.mp4"), result)
        assertEquals(0, folder.ensureCalls, "a 2 GB video must not be copied into our container first")
        assertTrue(folder.placed.isEmpty())
        assertTrue(installer.installed.isEmpty())
    }

    @Test
    fun everySupportedExtensionIsAccepted() = runTest {
        val folder = RecordingFolder()
        val installer = FakeInstaller(report(added = 1))
        val importer = SharedOpenInImporter(folder, installer)
        for (name in listOf("a.epub", "b.pdf", "c.cbz", "d.cbr", "E.EPUB")) {
            assertTrue(
                importer.importFile("content://x", name) !is OpenInImportResult.Unsupported,
                "$name should be importable",
            )
        }
    }

    @Test
    fun aDuplicateBookIsReportedAsPresentRatherThanAsSilence() = runTest {
        // The scan recognised the copy but its content-identity hash already had a row, so
        // nothing new appeared. Reporting nothing is what makes an import look broken.
        val result = SharedOpenInImporter(RecordingFolder(), FakeInstaller(report(added = 0)))
            .importFile("content://inbox/1", "Novel.epub")
        assertEquals(OpenInImportResult.AlreadyPresent("Novel"), result)
    }

    @Test
    fun aScanFailureIsReportedAsAFailureNotAsADuplicate() = runTest {
        val result = SharedOpenInImporter(RecordingFolder(), FakeInstaller(report(added = 0, failures = 1)))
            .importFile("content://inbox/1", "Novel.epub")
        assertTrue(result is OpenInImportResult.Failed, result.toString())
    }

    @Test
    fun aCopyFailureIsCaughtAndReported() = runTest {
        val result = SharedOpenInImporter(RecordingFolder(failOnPlace = true), FakeInstaller(report(added = 1)))
            .importFile("content://inbox/1", "Novel.epub")
        assertEquals(OpenInImportResult.Failed("Novel.epub", "disk full"), result)
    }

    @Test
    fun aScanThrowIsCaughtAndReported() = runTest {
        val result = SharedOpenInImporter(RecordingFolder(), FakeInstaller(report(added = 1), throws = true))
            .importFile("content://inbox/1", "Novel.epub")
        assertEquals(OpenInImportResult.Failed("Novel.epub", "scan blew up"), result)
    }

    @Test
    fun aSecondFileWithTheSameNameGetsADistinctNameInTheFolder() = runTest {
        val folder = RecordingFolder()
        val importer = SharedOpenInImporter(folder, FakeInstaller(report(added = 1)))
        importer.importFile("content://inbox/1", "Novel.epub")
        val second = importer.importFile("content://inbox/2", "Novel.epub")

        assertEquals(OpenInImportResult.Imported("Novel (2)"), second)
        assertEquals(listOf("Novel.epub", "Novel (2).epub"), folder.placed.map { it.second })
    }

    // ── The pure helpers the platform folders share ───────────────────────────────────────

    @Test
    fun extensionMatchingIsCaseInsensitiveAndIgnoresDotfiles() {
        assertTrue(OpenInFileTypes.isSupported("Book.EPUB"))
        assertTrue(OpenInFileTypes.isSupported("a.b.pdf"))
        assertFalse(OpenInFileTypes.isSupported("epub"))
        assertFalse(OpenInFileTypes.isSupported(".epub"), "a dotfile named .epub has no extension")
        assertFalse(OpenInFileTypes.isSupported("book."))
        assertFalse(OpenInFileTypes.isSupported(""))
    }

    @Test
    fun uniqueNameKeepsTheExtensionSoTheScannerStillClassifiesTheCopy() {
        assertEquals("a.epub", OpenInFileTypes.uniqueNameIn(emptySet(), "a.epub"))
        assertEquals("a (2).epub", OpenInFileTypes.uniqueNameIn(setOf("a.epub"), "a.epub"))
        assertEquals("a (3).epub", OpenInFileTypes.uniqueNameIn(setOf("a.epub", "a (2).epub"), "a.epub"))
        assertEquals("noext (2)", OpenInFileTypes.uniqueNameIn(setOf("noext"), "noext"))
    }

    @Test
    fun titleIsTheNameWithoutItsExtension() {
        assertEquals("Novel", OpenInFileTypes.titleOf("Novel.epub"))
        assertEquals("a.b", OpenInFileTypes.titleOf("a.b.pdf"))
        assertEquals(".epub", OpenInFileTypes.titleOf(".epub"), "never render an empty title")
    }

    @Test
    fun theManagedFolderIsRecognisedAsAppOwnedSoTheSafGrantDanceIsSkipped() {
        // Taking a persistable URI grant on a file:// URI throws SecurityException, and a
        // grant-based health check would mark the imports folder as needing attention forever.
        assertTrue(ManagedImportsFolders.isAppOwned("file:///data/user/0/com.riffle.app/files/Riffle%20Imports"))
        assertTrue(ManagedImportsFolders.isAppOwned("/var/mobile/Documents/Riffle Imports"))
        assertFalse(ManagedImportsFolders.isAppOwned("content://com.android.externalstorage.documents/tree/primary%3ABooks"))
    }

    @Test
    fun openInOutcomeMapsTheScannerCountsOntoTheUserFacingResult() {
        assertEquals(OpenInImportResult.Imported("x"), openInOutcome(report(added = 1), "x.epub"))
        assertEquals(OpenInImportResult.AlreadyPresent("x"), openInOutcome(report(added = 0), "x.epub"))
        assertTrue(openInOutcome(report(added = 0, failures = 2), "x.epub") is OpenInImportResult.Failed)
    }
}
