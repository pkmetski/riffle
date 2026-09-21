package com.riffle.core.data.localfiles

import android.content.Context
import android.net.Uri
import com.riffle.core.domain.DispatcherProvider
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Android "Open in Riffle" destination: `<filesDir>/Riffle Imports`.
 *
 * App-private rather than a SAF tree, because an incoming `ACTION_VIEW`/`ACTION_SEND` URI carries
 * a single-use read grant that is gone by the next launch — the bytes have to be copied somewhere
 * we own, immediately. The directory is then registered as an ordinary Local Files folder, so
 * browse, removal and rescan all work through the existing machinery.
 */
class AndroidManagedImportsFolder(
    private val context: Context,
    private val dispatchers: DispatcherProvider,
) : ManagedImportsFolder {

    private val dir: File get() = File(context.filesDir, ManagedImportsFolders.FOLDER_NAME)

    override suspend fun ensure(): FolderUri = withContext(dispatchers.io) {
        val target = dir
        if (!target.exists() && !target.mkdirs()) {
            error("Cannot create ${target.absolutePath}")
        }
        FolderUri(Uri.fromFile(target).toString())
    }

    override suspend fun place(locator: String, displayName: String): String = withContext(dispatchers.io) {
        val target = dir
        val taken = target.list()?.toSet().orEmpty()
        val name = OpenInFileTypes.uniqueNameIn(taken, displayName)
        val dest = File(target, name)
        val input = context.contentResolver.openInputStream(Uri.parse(locator))
            ?: error("Cannot open input stream: $locator")
        try {
            input.use { src -> dest.outputStream().use { dst -> src.copyTo(dst) } }
        } catch (e: Exception) {
            dest.delete()
            throw e
        }
        name
    }
}

/**
 * [FolderWalker] for an app-owned directory addressed by a `file://` URI.
 *
 * `SafFolderWalker` cannot walk one — `DocumentFile.fromTreeUri` returns null for a non-tree URI
 * — and the managed imports folder is exactly such a directory, so the scanner needs a walker
 * that speaks plain files. Same contract: throws when the *root* cannot be listed (so the
 * scanner's stale sweep stays disabled rather than hard-deleting every imported book), skips an
 * unreadable subdirectory.
 */
class ManagedFolderWalker(
    private val dispatchers: DispatcherProvider,
) : FolderWalker {

    override suspend fun walk(treeUri: String): List<WalkedFile> = withContext(dispatchers.io) {
        val root = File(Uri.parse(treeUri).path ?: throw IllegalStateException("Not a file URI: $treeUri"))
        if (!root.isDirectory) throw IllegalStateException("Cannot list folder: $treeUri")
        val out = mutableListOf<WalkedFile>()
        walkInto(root, out)
        out
    }

    private fun walkInto(dir: File, out: MutableList<WalkedFile>) {
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                walkInto(child, out)
            } else {
                out += WalkedFile(
                    originalUri = Uri.fromFile(child).toString(),
                    displayName = child.name,
                    sizeBytes = child.length(),
                    mtimeEpochMs = child.lastModified(),
                    openStream = { child.inputStream() },
                )
            }
        }
    }
}

/**
 * Routes each folder to the walker that can read it: user-picked SAF trees to [SafFolderWalker],
 * the app-owned imports folder to [ManagedFolderWalker].
 *
 * A single walker cannot do both, and the scanner iterates every configured folder in one pass,
 * so the choice has to be per-folder rather than per-installation.
 */
class SchemeDispatchingFolderWalker(
    private val saf: FolderWalker,
    private val managed: FolderWalker,
) : FolderWalker {
    override suspend fun walk(treeUri: String): List<WalkedFile> =
        if (ManagedImportsFolders.isAppOwned(treeUri)) managed.walk(treeUri) else saf.walk(treeUri)
}
