package com.riffle.core.data.localfiles

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.riffle.core.database.LibraryDao
import com.riffle.core.database.LibraryEntity
import com.riffle.core.database.LocalFilesFileFolderDao
import com.riffle.core.database.LocalFilesFolderDao
import com.riffle.core.database.LocalFilesFolderEntity
import com.riffle.core.common.Clock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adds/removes configured LocalFiles folders. Each folder maps 1:1 to a [LibraryEntity] named
 * after the folder — added together on [addFolder], deleted together on [removeFolder]. On add,
 * takes persistable read permission on the tree URI so the folder survives process death; on
 * remove, releases the grant and drops every junction row tying files to that folder.
 */
@Singleton
class LocalFilesFolderRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val folderDao: LocalFilesFolderDao,
    private val libraryDao: LibraryDao,
    private val fileFolderDao: LocalFilesFileFolderDao,
    private val clock: Clock,
) {

    /**
     * Adds [treeUri] as a monitored folder under [sourceId]. Idempotent on the treeUri: if the
     * folder is already configured, its existing libraryId is returned unchanged.
     *
     * Returns the folder's stable libraryId — the id of the [LibraryEntity] that surfaces the
     * folder's books in the drawer and catalog.
     */
    suspend fun addFolder(sourceId: String, treeUri: Uri): String {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        val treeUriStr = treeUri.toString()
        val displayName = DocumentFile.fromTreeUri(context, treeUri)?.name
            ?: treeUri.lastPathSegment
            ?: treeUriStr
        val existing = folderDao.forSource(sourceId).firstOrNull { it.treeUri == treeUriStr }
        val libraryId = existing?.libraryId ?: newFolderLibraryId()
        folderDao.upsert(
            LocalFilesFolderEntity(
                sourceId = sourceId,
                treeUri = treeUriStr,
                displayName = displayName,
                addedAtEpochMs = existing?.addedAtEpochMs ?: clock.nowMs(),
                libraryId = libraryId,
            ),
        )
        libraryDao.upsertAll(
            listOf(
                LibraryEntity(
                    id = libraryId,
                    name = displayName,
                    mediaType = "book",
                    sourceId = sourceId,
                ),
            ),
        )
        return libraryId
    }

    /**
     * Removes the folder identified by [treeUri] from [sourceId]. Drops every file-membership row
     * pointing at that folder — the scanner's post-remove sweep handles files that lost their last
     * membership. Also deletes the folder's [LibraryEntity] so it disappears from the drawer.
     */
    suspend fun removeFolder(sourceId: String, treeUri: String) {
        val folder = folderDao.forSource(sourceId).firstOrNull { it.treeUri == treeUri }
        try {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(treeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Grant may already be gone (e.g. user cleared app data). Fall through.
        }
        fileFolderDao.deleteFolder(sourceId, treeUri)
        folderDao.delete(sourceId, treeUri)
        folder?.let { libraryDao.deleteById(sourceId, it.libraryId) }
    }

    suspend fun folders(sourceId: String): List<LocalFilesFolderEntity> = folderDao.forSource(sourceId)

    private fun newFolderLibraryId(): String = LOCAL_FILES_LIBRARY_ID_PREFIX + UUID.randomUUID().toString()

    companion object {
        /**
         * Prefix for the stable per-folder [LibraryEntity.id]. The full id is
         * `local:folder:<uuid>`, generated once at folder-add time and stored on both the folder
         * row and the library row so the two stay linked across SAF re-grants.
         */
        const val LOCAL_FILES_LIBRARY_ID_PREFIX: String = "local:folder:"
    }
}
