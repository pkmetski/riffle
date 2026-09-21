package com.riffle.core.data.localfiles

/**
 * Ingests a single book file that arrived from outside the app — "Open in Riffle" from Mail,
 * Files, Safari or a Finder drop on iOS; an `ACTION_VIEW`/`ACTION_SEND` intent on Android.
 *
 * Neither platform had any such path before: the only way a file could enter the library was to
 * pick a whole folder. The file lands in the app-owned [ManagedImportsFolder], which is a Local
 * Files folder like any other, so the book is browsable, removable and rescannable through the
 * machinery that already exists rather than through a second parallel store.
 */
interface OpenInImporter {
    /**
     * @param locator a platform-resolvable handle for the incoming bytes — a `content://` URI on
     *   Android, an absolute filesystem path on iOS.
     * @param displayName the file name to present and to derive the title from.
     */
    suspend fun importFile(locator: String, displayName: String): OpenInImportResult
}

sealed interface OpenInImportResult {
    /** The file is now in the library. */
    data class Imported(val title: String) : OpenInImportResult

    /** The identical bytes were already in the library (content-identity hash matched). */
    data class AlreadyPresent(val title: String) : OpenInImportResult

    /** Not a book Riffle can open. Distinct from [Failed] so the message can say why. */
    data class Unsupported(val displayName: String) : OpenInImportResult

    data class Failed(val displayName: String, val reason: String) : OpenInImportResult
}

/**
 * The app-owned directory incoming files are copied into, registered as an ordinary Local Files
 * folder so the existing scanner, catalog, folder manager and removal paths all apply to it.
 *
 * It is app-owned rather than a user-picked folder because the OS hands us a file with no
 * durable location: iOS's inbox copy is deleted after the launch that received it, and Android's
 * `content://` grant is single-use.
 */
interface ManagedImportsFolder {
    /** Creates the directory if it does not exist and returns its walkable identifier. */
    suspend fun ensure(): FolderUri

    /**
     * Copies the bytes named by [locator] into the managed directory under a collision-free name
     * derived from [displayName]. Returns the name it was actually written under.
     *
     * Throws when the bytes cannot be read or written — the caller turns that into
     * [OpenInImportResult.Failed].
     */
    suspend fun place(locator: String, displayName: String): String
}

/**
 * Facts about the managed imports folder that both platforms — and the Android SAF machinery
 * that has to make an exception for it — need to agree on.
 */
object ManagedImportsFolders {
    /** The directory leaf, which is also the name of the library the folder surfaces as. */
    const val FOLDER_NAME: String = "Riffle Imports"

    /**
     * True when [treeUri] names a directory the app itself owns rather than a folder the user
     * granted through the system picker.
     *
     * Android's Local Files plumbing assumes every folder is a SAF tree: it takes a persistable
     * URI grant on add, releases it on remove, reports a folder unhealthy when the grant is
     * missing, and walks it with `DocumentFile.fromTreeUri`. None of that applies to a directory
     * inside our own container — taking a grant on a `file://` URI throws, and "no grant" would
     * permanently mark the imports folder as needing attention. Callers use this to branch.
     */
    fun isAppOwned(treeUri: String): Boolean = !treeUri.startsWith("content:")
}

/**
 * Which file names "Open in Riffle" accepts, by extension.
 *
 * Extension-only, and deliberately so: this is the gate that decides whether to copy the bytes at
 * all, and it runs before anything has been read. [FileClassifier] still sniffs magic bytes when
 * the scanner ingests the copy, so a `.epub` that is not a zip is rejected there — this just
 * stops the app from copying a 2 GB video into its own container first.
 */
object OpenInFileTypes {
    /** Lower-case, without the dot. Mirrors [FileClassifier]'s recognised kinds. */
    val EXTENSIONS: Set<String> = setOf("epub", "pdf", "cbz", "cbr")

    fun isSupported(displayName: String): Boolean = extensionOf(displayName) in EXTENSIONS

    /** "" when the name has no extension (a bare name is not a book). */
    fun extensionOf(displayName: String): String {
        val dot = displayName.lastIndexOf('.')
        if (dot <= 0 || dot == displayName.lastIndex) return ""
        return displayName.substring(dot + 1).lowercase()
    }

    /** The title the library shows for [displayName] — the name minus its extension. */
    fun titleOf(displayName: String): String =
        displayName.substringBeforeLast('.').ifBlank { displayName }

    /**
     * `"book.epub"`, `"book (2).epub"`, `"book (3).epub"`, … — the next name that is not in
     * [taken]. Keeps the extension so the scanner still classifies the copy correctly.
     */
    fun uniqueNameIn(taken: Set<String>, displayName: String): String {
        if (displayName !in taken) return displayName
        val stem = displayName.substringBeforeLast('.', displayName)
        val ext = if (stem == displayName) "" else "." + displayName.substringAfterLast('.')
        var n = 2
        while (true) {
            val candidate = "$stem ($n)$ext"
            if (candidate !in taken) return candidate
            n++
        }
    }
}

/**
 * The one [OpenInImporter] both platforms run.
 *
 * Copies the incoming bytes into the managed folder, then re-runs the ordinary Local Files
 * install for that folder — which ensures the LocalFiles Source exists, registers the folder (and
 * its library) if this is the first import, and scans. The scan is what classifies, hashes and
 * upserts the book, so an import produces exactly the same rows a folder pick would.
 */
class SharedOpenInImporter(
    private val importsFolder: ManagedImportsFolder,
    private val installer: LocalFilesInstallerInterface,
) : OpenInImporter {

    override suspend fun importFile(locator: String, displayName: String): OpenInImportResult {
        if (!OpenInFileTypes.isSupported(displayName)) {
            return OpenInImportResult.Unsupported(displayName)
        }
        val folder = try {
            importsFolder.ensure()
        } catch (e: Exception) {
            return OpenInImportResult.Failed(displayName, e.message ?: "cannot open imports folder")
        }
        val placedName = try {
            importsFolder.place(locator, displayName)
        } catch (e: Exception) {
            return OpenInImportResult.Failed(displayName, e.message ?: "copy failed")
        }
        val report = try {
            installer.installFolder(folder)
        } catch (e: Exception) {
            return OpenInImportResult.Failed(displayName, e.message ?: "scan failed")
        }
        return openInOutcome(report, placedName)
    }
}

/**
 * Turns the scanner's counts into the outcome the UI reports.
 *
 * `added == 0 && failures == 0` is the duplicate case: the scan recognised the copy but its
 * content-identity hash already had a row, so nothing new appeared. That is a success, not a
 * silent no-op, and saying so is the difference between "nothing happened" and "you already have
 * this".
 *
 * Extracted so the mapping is assertable without a filesystem — the counts are the only thing
 * standing between a failed import and a reassuring message.
 */
fun openInOutcome(
    report: LocalFilesInstallerInterface.InstallReport,
    placedName: String,
): OpenInImportResult {
    val title = OpenInFileTypes.titleOf(placedName)
    return when {
        report.added > 0 -> OpenInImportResult.Imported(title)
        report.failures > 0 -> OpenInImportResult.Failed(placedName, "scan reported a failure")
        else -> OpenInImportResult.AlreadyPresent(title)
    }
}
