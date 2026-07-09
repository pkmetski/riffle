package com.riffle.core.data.localfiles

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Real [FolderWalker] backed by SAF `DocumentFile`. Walks recursively; yields every non-directory
 * child file with a stream opener that reads from the shared `ContentResolver`.
 */
class SafFolderWalker @Inject constructor(
    @ApplicationContext private val context: Context,
) : FolderWalker {

    override suspend fun walk(treeUri: String): List<WalkedFile> = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
            ?: throw IllegalStateException("Cannot open tree URI: $treeUri")
        val out = mutableListOf<WalkedFile>()
        walkInto(root, out)
        out
    }

    private fun walkInto(node: DocumentFile, out: MutableList<WalkedFile>) {
        for (child in node.listFiles()) {
            if (child.isDirectory) {
                walkInto(child, out)
            } else if (child.isFile) {
                val name = child.name ?: continue
                out += WalkedFile(
                    originalUri = child.uri.toString(),
                    displayName = name,
                    sizeBytes = child.length(),
                    mtimeEpochMs = child.lastModified(),
                    openStream = {
                        // Called on whatever dispatcher the caller (the scanner) uses. The scanner's
                        // head-read + copy-in wraps their own IO in withContext(IO), so we don't
                        // need to redispatch here.
                        context.contentResolver.openInputStream(child.uri)
                            ?: throw IllegalStateException("Cannot open input stream: ${child.uri}")
                    },
                )
            }
        }
    }
}
